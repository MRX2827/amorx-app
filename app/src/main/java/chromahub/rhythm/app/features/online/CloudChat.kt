package chromahub.rhythm.app.features.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChatPeer(val id: String, val name: String, val avatarB64: String?)
data class ChatMessage(
    val id: Long,
    val fromMe: Boolean,
    val body: String,
    val track: CloudSong?,
    val at: Long,
    val read: Boolean
)
data class Conversation(val peer: ChatPeer, val last: ChatMessage, val unread: Int)

/** Сообщения между пользователями (Supabase, таблица messages). */
object CloudChat {
    @Volatile
    private var ensured = false

    private fun req(path: String, token: String): Request.Builder =
        Request.Builder()
            .url(SupabaseConfig.URL + path)
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer $token")

    private fun parseTime(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time ?: 0L
    }.getOrDefault(0L)

    private fun parseMessage(o: JSONObject, me: String): ChatMessage {
        val t = if (o.isNull("track")) null else o.optJSONObject("track")?.let { x ->
            CloudSong(
                x.optString("id"),
                x.optString("title"),
                x.optString("artist"),
                if (x.isNull("artwork")) null else x.optString("artwork").ifBlank { null },
                x.optLong("dur", 0L)
            )
        }
        return ChatMessage(
            o.getLong("id"),
            o.getString("sender_id") == me,
            o.optString("body"),
            t,
            parseTime(o.optString("created_at")),
            !o.isNull("read_at")
        )
    }

    /** Чтобы писать, у вас должна быть строка профиля на сервере (скрытая, пока вы её не опубликуете). */
    suspend fun ensureProfile(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ensured) return@runCatching
            val (token, uid) = CloudProfiles.session()
            val body = JSONObject().put("id", uid).put("username", name.trim())
            val r = req("/rest/v1/profiles", token)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(body.toString().toRequestBody(CloudProfiles.JSON_TYPE))
                .build()
            val (code, resp) = CloudProfiles.exec(r)
            if (code !in 200..299) {
                throw IOException(
                    if (resp.contains("23505")) "Это имя уже занято, смените его в профиле"
                    else "Не удалось создать профиль ($code)"
                )
            }
            ensured = true
        }
    }

    suspend fun send(toId: String, body: String, track: CloudSong?, myName: String): Result<Unit> {
        val ok = ensureProfile(myName)
        if (ok.isFailure) return ok
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = CloudProfiles.session()
                val o = JSONObject()
                    .put("sender_id", uid)
                    .put("recipient_id", toId)
                    .put("body", body.trim())
                if (track != null) {
                    o.put(
                        "track",
                        JSONObject()
                            .put("id", track.id)
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("artwork", track.artwork ?: JSONObject.NULL)
                            .put("dur", track.dur)
                    )
                }
                val r = req("/rest/v1/messages", token)
                    .header("Prefer", "return=minimal")
                    .post(o.toString().toRequestBody(CloudProfiles.JSON_TYPE))
                    .build()
                val (code, resp) = CloudProfiles.exec(r)
                if (code !in 200..299) {
                    throw IOException(
                        when {
                            code == 404 -> "Таблица messages не найдена: выполните SQL чатов в Supabase"
                            resp.contains("23503") -> "Получателя нет на сервере"
                            else -> "Не удалось отправить ($code)"
                        }
                    )
                }
                Unit
            }
        }
    }

    suspend fun thread(peerId: String): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, me) = CloudProfiles.session()
            val f = "or=(and(sender_id.eq.$me,recipient_id.eq.$peerId),and(sender_id.eq.$peerId,recipient_id.eq.$me))"
            val r = req("/rest/v1/messages?select=*&$f&order=created_at.desc&limit=200", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code !in 200..299) throw IOException("Не удалось загрузить чат ($code)")
            val a = JSONArray(body)
            (0 until a.length()).map { parseMessage(a.getJSONObject(it), me) }.reversed()
        }
    }

    suspend fun markRead(peerId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val (token, me) = CloudProfiles.session()
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
            val r = req("/rest/v1/messages?recipient_id=eq.$me&sender_id=eq.$peerId&read_at=is.null", token)
                .header("Prefer", "return=minimal")
                .patch(JSONObject().put("read_at", now).toString().toRequestBody(CloudProfiles.JSON_TYPE))
                .build()
            CloudProfiles.exec(r)
        }
        Unit
    }

    suspend fun unreadTotal(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (CloudProfiles.myId() == null) return@runCatching 0
            val (token, me) = CloudProfiles.session()
            val r = req("/rest/v1/messages?select=id&recipient_id=eq.$me&read_at=is.null&limit=100", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code !in 200..299) throw IOException("code $code")
            JSONArray(body).length()
        }
    }

    suspend fun conversations(): Result<List<Conversation>> = withContext(Dispatchers.IO) {
        runCatching {
            if (CloudProfiles.myId() == null) return@runCatching emptyList<Conversation>()
            val (token, me) = CloudProfiles.session()
            val r = req("/rest/v1/messages?select=*&order=created_at.desc&limit=300", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code == 404) throw IOException("Таблица messages не найдена: выполните SQL чатов в Supabase")
            if (code !in 200..299) throw IOException("Не удалось загрузить чаты ($code)")
            val a = JSONArray(body)
            val grouped = LinkedHashMap<String, MutableList<ChatMessage>>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val peer = if (o.getString("sender_id") == me) o.getString("recipient_id") else o.getString("sender_id")
                grouped.getOrPut(peer) { mutableListOf() }.add(parseMessage(o, me))
            }
            if (grouped.isEmpty()) return@runCatching emptyList<Conversation>()
            val ids = grouped.keys.joinToString(",")
            val pr = req("/rest/v1/profiles?select=id,username,avatar_b64&id=in.($ids)", token).get().build()
            val (pc, pb) = CloudProfiles.exec(pr)
            val peers = HashMap<String, ChatPeer>()
            if (pc in 200..299) {
                val pa = JSONArray(pb)
                for (i in 0 until pa.length()) {
                    val o = pa.getJSONObject(i)
                    peers[o.getString("id")] = ChatPeer(
                        o.getString("id"),
                        o.optString("username"),
                        if (o.isNull("avatar_b64")) null else o.optString("avatar_b64")
                    )
                }
            }
            grouped.map { (id, list) ->
                Conversation(
                    peers[id] ?: ChatPeer(id, "Пользователь", null),
                    list.first(),
                    list.count { !it.fromMe && !it.read }
                )
            }
        }
    }
}
