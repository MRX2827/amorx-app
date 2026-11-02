package chromahub.rhythm.app.features.online.stories

import android.content.Context
import android.content.SharedPreferences
import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.CloudSong
import chromahub.rhythm.app.features.online.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Истории (Supabase): видео из галереи или песня из плейлиста, живут 24 часа.
 * Видят только те, у кого профиль публичный. Таблица stories, корзина Storage "stories".
 */
object CloudStories {
    private const val PREFS = "amorx_stories"
    private const val STORAGE = SupabaseConfig.URL + "/storage/v1/object"
    private const val PUBLIC_MARK = "/public/stories/"
    const val MAX_ACTIVE = 5

    private var prefs: SharedPreferences? = null

    private val _feed = MutableStateFlow<List<StoryGroup>>(emptyList())
    val feed: StateFlow<List<StoryGroup>> = _feed.asStateFlow()

    private val _seen = MutableStateFlow<Set<String>>(emptySet())
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        CloudProfiles.init(context)
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _seen.value = p.getStringSet("seen", emptySet())?.toSet() ?: emptySet()
        }
    }

    fun markSeen(id: String) {
        if (id in _seen.value) return
        _seen.value = _seen.value + id
        prefs?.edit()?.putStringSet("seen", _seen.value.toSet())?.apply()
    }

    fun hasUnseen(group: StoryGroup): Boolean = group.stories.any { it.id !in _seen.value }

    // ----- свои загрузки: помним файлы, чтобы убрать их из хранилища после 24 часов -----

    private data class Mine(val id: String, val path: String?, val exp: Long)

    private fun loadMine(): List<Mine> = runCatching {
        val a = JSONArray(prefs?.getString("mine", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Mine(o.getString("id"), if (o.isNull("path")) null else o.getString("path"), o.getLong("exp"))
        }
    }.getOrDefault(emptyList())

    private fun saveMine(list: List<Mine>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("path", it.path ?: JSONObject.NULL).put("exp", it.exp))
        }
        prefs?.edit()?.putString("mine", a.toString())?.apply()
    }

    private fun addMine(m: Mine) = saveMine(loadMine().filter { it.id != m.id } + m)
    private fun removeMine(id: String) = saveMine(loadMine().filter { it.id != id })

    // ----- сеть -----

    private fun rest(token: String, path: String): Request.Builder =
        Request.Builder()
            .url(SupabaseConfig.URL + "/rest/v1/" + path)
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer $token")

    private fun parseTime(s: String): Long =
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrDefault(0L)

    private fun parseStory(o: JSONObject): Story? {
        val kind = o.optString("kind")
        val video = if (kind == "video" && !o.isNull("video_path")) {
            SupabaseConfig.URL + "/storage/v1/object" + PUBLIC_MARK + o.getString("video_path")
        } else null
        val song = if (kind == "song" && !o.isNull("track")) {
            val t = o.getJSONObject("track")
            CloudSong(
                t.optString("id"),
                t.optString("title"),
                t.optString("artist"),
                if (t.isNull("artwork")) null else t.optString("artwork").takeIf { it.isNotBlank() },
                t.optLong("dur", 0L)
            )
        } else null
        if (video == null && song == null) return null
        var clipStart = 0L
        var clipEnd = 0L
        if (song != null) {
            val t = o.getJSONObject("track")
            clipStart = t.optLong("start", 0L).coerceAtLeast(0L)
            clipEnd = t.optLong("end", 0L)
            if (clipEnd <= clipStart) clipEnd = clipStart + StoryAudio.DEFAULT_CLIP_MS
        }
        return Story(
            id = o.getString("id"),
            ownerId = o.getString("owner_id"),
            videoUrl = video,
            song = song,
            caption = if (o.isNull("caption")) "" else o.getString("caption"),
            createdAtMs = parseTime(o.optString("created_at")),
            expiresAtMs = parseTime(o.optString("expires_at")),
            clipStartMs = clipStart,
            clipEndMs = clipEnd
        )
    }

    private fun deleteFile(token: String, path: String): Boolean = runCatching {
        val req = Request.Builder()
            .url("$STORAGE/stories/$path")
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        CloudProfiles.exec(req).first in 200..299
    }.getOrDefault(false)

    /** Убирает из хранилища файлы своих историй, у которых вышел срок. */
    private fun cleanupExpiredFiles() {
        val now = System.currentTimeMillis()
        val old = loadMine().filter { it.exp < now }
        if (old.isEmpty()) return
        val (token, _) = CloudProfiles.session()
        old.forEach { m ->
            val done = m.path == null || deleteFile(token, m.path)
            if (done) removeMine(m.id)
        }
    }

    /** Обновляет ленту. Ошибки сети не трогают то, что уже показано. */
    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { cleanupExpiredFiles() }
            val (token, uid) = CloudProfiles.session()
            val select = "id,owner_id,kind,video_path,track,caption,created_at,expires_at"
            val (code, body) = CloudProfiles.exec(
                rest(token, "stories?select=$select&order=created_at.asc&limit=200").get().build()
            )
            if (code !in 200..299) throw IOException("Не удалось загрузить истории ($code)")
            val arr = JSONArray(body)
            val stories = (0 until arr.length()).mapNotNull { i -> parseStory(arr.getJSONObject(i)) }

            val owners = stories.map { it.ownerId }.distinct()
            val people = HashMap<String, Pair<String, String?>>()
            if (owners.isNotEmpty()) {
                val (pc, pb) = CloudProfiles.exec(
                    rest(token, "profiles?select=id,username,avatar_b64&id=in.(" + owners.joinToString(",") + ")")
                        .get().build()
                )
                if (pc in 200..299) {
                    val pa = JSONArray(pb)
                    for (i in 0 until pa.length()) {
                        val o = pa.getJSONObject(i)
                        people[o.getString("id")] = o.optString("username") to
                            (if (o.isNull("avatar_b64")) null else o.getString("avatar_b64").takeIf { it.isNotBlank() })
                    }
                }
            }

            val seenNow = _seen.value
            val groups = stories.groupBy { it.ownerId }.map { (owner, list) ->
                val p = people[owner]
                StoryGroup(
                    ownerId = owner,
                    name = p?.first?.takeIf { it.isNotBlank() } ?: "Без имени",
                    avatarB64 = p?.second,
                    isMine = owner == uid,
                    stories = list.sortedBy { it.createdAtMs }
                )
            }.sortedWith(
                compareByDescending<StoryGroup> { it.isMine }
                    .thenByDescending { g -> g.stories.any { it.id !in seenNow } }
                    .thenByDescending { g -> g.stories.maxOf { it.createdAtMs } }
            )
            _feed.value = groups

            val alive = stories.map { it.id }.toSet()
            val pruned = _seen.value.filter { it in alive }.toSet()
            if (pruned.size != _seen.value.size) {
                _seen.value = pruned
                prefs?.edit()?.putStringSet("seen", pruned)?.apply()
            }
        }
    }

    private fun insertError(code: Int, body: String): String = when {
        code == 401 || code == 403 || body.contains("row-level security", ignoreCase = true) ->
            "Не удалось опубликовать. Одновременно можно держать до $MAX_ACTIVE историй, подождите, пока старые закончатся или удалите одну"
        else -> "Ошибка публикации ($code)"
    }

    private fun insertRow(token: String, row: JSONObject): Pair<String, Long> {
        val req = rest(token, "stories")
            .header("Prefer", "return=representation")
            .post(row.toString().toRequestBody(CloudProfiles.JSON_TYPE))
            .build()
        val (code, body) = CloudProfiles.exec(req)
        if (code !in 200..299) throw IOException(insertError(code, body))
        val o = JSONArray(body).getJSONObject(0)
        return o.getString("id") to parseTime(o.optString("expires_at"))
    }

    private fun captionValue(caption: String): Any {
        val c = caption.trim().take(120)
        return if (c.isEmpty()) JSONObject.NULL else c
    }

    suspend fun postVideo(file: File, caption: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = CloudProfiles.session()
            val path = "$uid/${UUID.randomUUID()}.mp4"
            val up = Request.Builder()
                .url("$STORAGE/stories/$path")
                .header("apikey", SupabaseConfig.KEY)
                .header("Authorization", "Bearer $token")
                .post(file.asRequestBody("video/mp4".toMediaType()))
                .build()
            val (uc, _) = CloudProfiles.exec(up)
            if (uc !in 200..299) {
                throw IOException(if (uc == 413) "Видео слишком большое" else "Не удалось загрузить видео ($uc)")
            }
            try {
                val row = JSONObject()
                    .put("owner_id", uid)
                    .put("kind", "video")
                    .put("video_path", path)
                    .put("caption", captionValue(caption))
                val (id, exp) = insertRow(token, row)
                addMine(Mine(id, path, exp))
            } catch (e: Exception) {
                deleteFile(token, path)
                throw e
            }
        }.also { if (it.isSuccess) refresh() }
    }

    suspend fun postSong(song: CloudSong, caption: String, clipStartMs: Long, clipEndMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = CloudProfiles.session()
            val row = JSONObject()
                .put("owner_id", uid)
                .put("kind", "song")
                .put(
                    "track",
                    JSONObject()
                        .put("id", song.id)
                        .put("title", song.title)
                        .put("artist", song.artist)
                        .put("artwork", song.artwork ?: JSONObject.NULL)
                        .put("dur", song.dur)
                        .put("start", clipStartMs)
                        .put("end", clipEndMs)
                )
                .put("caption", captionValue(caption))
            val (id, exp) = insertRow(token, row)
            addMine(Mine(id, null, exp))
        }.also { if (it.isSuccess) refresh() }
    }

    suspend fun delete(story: Story): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, _) = CloudProfiles.session()
            val (code, _) = CloudProfiles.exec(rest(token, "stories?id=eq.${story.id}").delete().build())
            if (code !in 200..299) throw IOException("Не удалось удалить ($code)")
            story.videoUrl?.substringAfter(PUBLIC_MARK, "")?.takeIf { it.isNotEmpty() }?.let { deleteFile(token, it) }
            removeMine(story.id)
        }.also { if (it.isSuccess) refresh() }
    }

    suspend fun report(story: Story): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = CloudProfiles.session()
            val body = JSONObject().put("story_id", story.id).put("reporter_id", uid)
            val req = rest(token, "story_reports").post(body.toString().toRequestBody(CloudProfiles.JSON_TYPE)).build()
            val (code, _) = CloudProfiles.exec(req)
            // 409: уже жаловались на эту историю, для человека это тоже успех
            if (code !in 200..299 && code != 409) throw IOException("Не удалось отправить жалобу ($code)")
        }
    }
}
