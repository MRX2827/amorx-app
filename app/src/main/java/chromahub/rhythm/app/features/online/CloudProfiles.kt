package chromahub.rhythm.app.features.online

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object SupabaseConfig {
    const val URL = "https://nxnshkyvpsvckwxquqxm.supabase.co"
    const val KEY = "sb_publishable_tBZRKUt1QsjbfAjXGtkPSA_39rbXY61"
}

data class CloudSong(val id: String, val title: String, val artist: String, val artwork: String?, val dur: Long)
data class CloudArtist(val name: String, val avatar: String?)
data class RemoteProfile(
    val id: String,
    val username: String,
    val bio: String,
    val avatarB64: String?,
    val artists: List<CloudArtist>,
    val songs: List<CloudSong>
)

/** Профили в облаке (Supabase). Вход анонимный: у каждой установки свой скрытый id. */
object CloudProfiles {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    internal val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("amorx_cloud", Context.MODE_PRIVATE)
        }
    }

    fun myId(): String? = prefs?.getString("uid", null)

    internal fun exec(req: Request): Pair<Int, String> =
        client.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }

    private fun authPost(path: String, body: String): Pair<Int, String> =
        exec(
            Request.Builder()
                .url(SupabaseConfig.URL + path)
                .header("apikey", SupabaseConfig.KEY)
                .post(body.toRequestBody(JSON_TYPE))
                .build()
        )

    internal fun storeSession(json: JSONObject) {
        val p = prefs ?: return
        p.edit()
            .putString("access", json.getString("access_token"))
            .putString("refresh", json.optString("refresh_token", ""))
            .putLong("exp", System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600))
            .putString("uid", json.getJSONObject("user").getString("id"))
            .apply()
    }

    /** Возвращает (токен, id пользователя). При первом обращении создаёт анонимный вход. */
    internal fun session(): Pair<String, String> {
        val p = prefs ?: throw IOException("Профили не запущены")
        val now = System.currentTimeMillis() / 1000
        val access = p.getString("access", null)
        val uid = p.getString("uid", null)
        if (access != null && uid != null && p.getLong("exp", 0L) - 60 > now) return access to uid

        val refresh = p.getString("refresh", null)
        if (!refresh.isNullOrBlank()) {
            val (code, body) = authPost(
                "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", refresh).toString()
            )
            if (code in 200..299) {
                storeSession(JSONObject(body))
                return p.getString("access", "")!! to p.getString("uid", "")!!
            }
            if (code !in 400..499) throw IOException("Сервер недоступен ($code)")
        }

        val (code, body) = authPost("/auth/v1/signup", "{}")
        if (code !in 200..299) {
            throw IOException(
                if (code == 422 || code == 400) "Вход отключён: включите Anonymous sign-ins в Supabase (Authentication)"
                else "Не удалось войти ($code)"
            )
        }
        storeSession(JSONObject(body))
        return p.getString("access", "")!! to p.getString("uid", "")!!
    }

    private fun encodeAvatar(path: String?): String? {
        if (path == null) return null
        return try {
            val bmp = BitmapFactory.decodeFile(path) ?: return null
            val side = minOf(bmp.width, bmp.height)
            val target = minOf(160, side)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(sq, target, target, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    suspend fun publish(
        name: String,
        bio: String,
        avatarPath: String?,
        isPublic: Boolean,
        artists: List<CloudArtist>,
        songs: List<CloudSong>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = session()
            val body = JSONObject()
                .put("id", uid)
                .put("username", name.trim())
                .put("bio", bio.trim())
                .put("avatar_b64", encodeAvatar(avatarPath) ?: JSONObject.NULL)
                .put("is_public", isPublic)
                .put("updated_at", nowIso())
                .put(
                    "favorite_artists",
                    JSONArray(artists.take(50).map {
                        JSONObject().put("name", it.name).put("avatar", it.avatar ?: JSONObject.NULL)
                    })
                )
                .put(
                    "favorite_songs",
                    JSONArray(songs.take(50).map {
                        JSONObject()
                            .put("id", it.id)
                            .put("title", it.title)
                            .put("artist", it.artist)
                            .put("artwork", it.artwork ?: JSONObject.NULL)
                            .put("dur", it.dur)
                    })
                )
            val req = Request.Builder()
                .url(SupabaseConfig.URL + "/rest/v1/profiles")
                .header("apikey", SupabaseConfig.KEY)
                .header("Authorization", "Bearer $token")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            val (code, resp) = exec(req)
            if (code !in 200..299) {
                throw IOException(
                    when {
                        resp.contains("23505") -> "Это имя уже занято, выберите другое"
                        code == 404 -> "Таблица profiles не найдена: выполните SQL (кнопка Run) в Supabase"
                        else -> "Ошибка публикации ($code)"
                    }
                )
            }
            prefs?.edit()?.putBoolean("published", true)?.apply()
            Unit
        }
    }

    /** Прячет профиль на сервере, если он был опубликован. */
    suspend fun unpublishIfNeeded() = withContext(Dispatchers.IO) {
        runCatching {
            val p = prefs ?: return@runCatching
            if (!p.getBoolean("published", false)) return@runCatching
            val (token, uid) = session()
            val req = Request.Builder()
                .url(SupabaseConfig.URL + "/rest/v1/profiles?id=eq." + uid)
                .header("apikey", SupabaseConfig.KEY)
                .header("Authorization", "Bearer $token")
                .header("Prefer", "return=minimal")
                .patch(JSONObject().put("is_public", false).toString().toRequestBody(JSON_TYPE))
                .build()
            val (code, _) = exec(req)
            if (code in 200..299) p.edit().putBoolean("published", false).apply()
        }
        Unit
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }

    private fun parse(arr: JSONArray): List<RemoteProfile> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val artists = o.optJSONArray("favorite_artists")?.let { a ->
            (0 until a.length()).map { j ->
                val x = a.getJSONObject(j)
                CloudArtist(x.optString("name"), x.str("avatar"))
            }
        } ?: emptyList()
        val songs = o.optJSONArray("favorite_songs")?.let { a ->
            (0 until a.length()).map { j ->
                val x = a.getJSONObject(j)
                CloudSong(
                    x.optString("id"),
                    x.optString("title"),
                    x.optString("artist"),
                    x.str("artwork"),
                    x.optLong("dur", 0L)
                )
            }
        } ?: emptyList()
        RemoteProfile(
            id = o.getString("id"),
            username = o.optString("username"),
            bio = o.str("bio") ?: "",
            avatarB64 = o.str("avatar_b64"),
            artists = artists,
            songs = songs
        )
    }

    suspend fun search(query: String): Result<List<RemoteProfile>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = runCatching { session().first }.getOrNull()
            val q = query.trim().lowercase().filter { it !in "%_*,()\\" }
            val url = StringBuilder(
                SupabaseConfig.URL +
                    "/rest/v1/profiles?select=id,username,bio,avatar_b64,favorite_artists,favorite_songs" +
                    "&is_public=eq.true&limit=30"
            )
            if (q.isNotEmpty()) {
                url.append("&username_lower=like.").append(URLEncoder.encode(q, "UTF-8")).append("*")
                url.append("&order=username_lower.asc")
            } else {
                url.append("&order=updated_at.desc")
            }
            val rb = Request.Builder().url(url.toString()).header("apikey", SupabaseConfig.KEY)
            if (token != null) rb.header("Authorization", "Bearer $token")
            val (code, body) = exec(rb.build())
            if (code == 404) throw IOException("Таблица profiles не найдена: выполните SQL (кнопка Run) в Supabase")
            if (code !in 200..299) throw IOException("Ошибка поиска ($code)")
            parse(JSONArray(body))
        }
    }

    /** Превращает песню из чужого профиля в играбельную: берёт её из онлайн-каталога или ищет по названию. */
    suspend fun resolveSong(s: CloudSong): Song {
        val videoId = when {
            s.id.startsWith("online_") -> s.id.removePrefix("online_")
            s.id.startsWith("offline_") -> s.id.removePrefix("offline_")
            else -> null
        }
        val track = if (videoId != null) {
            OnlineTrack(
                videoUrl = "https://www.youtube.com/watch?v=" + videoId,
                title = s.title,
                artist = s.artist,
                durationMs = s.dur,
                artworkUrl = s.artwork
            )
        } else {
            OnlineRepository.search(s.artist + " " + s.title).firstOrNull()
                ?: throw IOException("Песня не найдена")
        }
        return track.toSong(OnlineRepository.resolveAudioUrl(track.videoUrl))
    }
}
