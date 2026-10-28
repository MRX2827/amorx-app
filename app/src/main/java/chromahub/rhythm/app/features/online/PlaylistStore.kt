package chromahub.rhythm.app.features.online

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class PlTrack(val id: String, val title: String, val artist: String, val durMs: Long, val art: String?)

fun PlTrack.toOnline(): OnlineTrack = OnlineTrack(
    videoUrl = "https://www.youtube.com/watch?v=" + id,
    title = title,
    artist = artist,
    durationMs = durMs,
    artworkUrl = art
)

/** Собственный плейлист AmorX: хранится на телефоне, до 1000 треков. */
object PlaylistStore {
    private const val MAX = 1000

    val tracks = MutableStateFlow<List<PlTrack>>(emptyList())
    val ownCode = MutableStateFlow<String?>(null)
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "amorx_playlist.json")
        file = f
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                val a = o.optJSONArray("tracks") ?: JSONArray()
                tracks.value = (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    PlTrack(
                        x.getString("id"),
                        x.optString("title"),
                        x.optString("artist"),
                        x.optLong("durMs", 0L),
                        if (x.isNull("art")) null else x.optString("art")
                    )
                }
                ownCode.value = if (o.isNull("ownCode")) null else o.optString("ownCode")
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray(tracks.value.map {
                JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                    .put("durMs", it.durMs).put("art", it.art ?: JSONObject.NULL)
            })
            val o = JSONObject().put("tracks", arr).put("ownCode", ownCode.value ?: JSONObject.NULL)
            f.writeText(o.toString())
        }
    }

    fun add(list: List<PlTrack>): Int {
        val before = tracks.value.size
        tracks.value = (tracks.value + list).distinctBy { it.id }.take(MAX)
        save()
        return tracks.value.size - before
    }

    fun replace(list: List<PlTrack>) {
        tracks.value = list.distinctBy { it.id }.take(MAX)
        save()
    }

    fun setOwnCode(code: String?) {
        ownCode.value = code
        save()
    }

    fun remove(id: String) {
        tracks.value = tracks.value.filter { it.id != id }
        save()
    }

    fun clear() {
        tracks.value = emptyList()
        ownCode.value = null
        save()
    }
}

/** Скачивает плейлист по 2 песни за раз, чтобы не душить сеть и YouTube. */
object PlaylistDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    val running = MutableStateFlow(false)

    fun missing(): List<PlTrack> {
        val have = OnlineDownloads.items.value.map { it.id }.toHashSet()
        return PlaylistStore.tracks.value.filter { it.id !in have }
    }

    /** Возвращает текст ошибки или null, если загрузка запущена. */
    fun start(context: Context): String? {
        OnlineDownloads.init(context)
        if (job?.isActive == true) return null
        val todoNow = missing()
        if (todoNow.isEmpty()) return null
        val needBytes = todoNow.size * 6L * 1024 * 1024
        val free = context.filesDir.usableSpace
        if (free < needBytes + 300L * 1024 * 1024) {
            val gb = needBytes / 1073741824.0
            return "Мало места: на ${todoNow.size} песен нужно около %.1f ГБ".format(gb)
        }
        job = scope.launch {
            running.value = true
            try {
                repeat(3) {
                    for (t in missing()) {
                        while (OnlineDownloads.pending.value.size >= 2) delay(300)
                        OnlineDownloads.download(t.toOnline())
                        delay(150)
                    }
                    while (OnlineDownloads.pending.value.isNotEmpty()) delay(500)
                    if (missing().isEmpty()) return@launch
                }
            } finally {
                running.value = false
            }
        }
        return null
    }
}

/** Ссылка на плейлист: список лежит в Supabase, в ссылке только код. */
object CloudPlaylists {
    private fun arr(list: List<PlTrack>): JSONArray = JSONArray(list.map {
        JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
            .put("dur", it.durMs).put("art", it.art ?: JSONObject.NULL)
    })

    fun linkFor(code: String): String = "https://mrx2827.github.io/amorxsyte/p.html#playlist/" + code

    fun parseCode(text: String): String? {
        val t = text.trim()
        val m = Regex("playlist/([0-9a-fA-F]{8})").find(t)
        if (m != null) return m.groupValues[1].lowercase()
        val bare = Regex("^[0-9a-fA-F]{8}$").find(t)
        return bare?.value?.lowercase()
    }

    private fun send(token: String, uid: String, list: List<PlTrack>, code: String?): Pair<Int, String> {
        val body = JSONObject().put("title", "Плейлист AmorX").put("tracks", arr(list))
        val url = SupabaseConfig.URL + "/rest/v1/shared_playlists" + (if (code != null) "?code=eq." + code else "")
        val rb = Request.Builder().url(url)
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer " + token)
            .header("Prefer", "return=representation")
        if (code == null) {
            body.put("owner_id", uid)
            rb.post(body.toString().toRequestBody(CloudProfiles.JSON_TYPE))
        } else {
            rb.patch(body.toString().toRequestBody(CloudProfiles.JSON_TYPE))
        }
        return CloudProfiles.exec(rb.build())
    }

    /** Выгружает плейлист и возвращает код. Повторный вызов обновляет тот же код. */
    suspend fun share(list: List<PlTrack>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (list.isEmpty()) throw IOException("Плейлист пуст")
            val (token, uid) = CloudProfiles.session()
            var code = PlaylistStore.ownCode.value
            var (c, resp) = send(token, uid, list, code)
            if (c in 200..299 && code != null && JSONArray(resp).length() == 0) {
                val r = send(token, uid, list, null)
                c = r.first
                resp = r.second
            }
            if (c == 404) throw IOException("Таблица shared_playlists не найдена: выполните SQL (Run) в Supabase")
            if (c !in 200..299) throw IOException("Не удалось поделиться (" + c + ")")
            val a = JSONArray(resp)
            if (a.length() == 0) throw IOException("Сервер не вернул код")
            code = a.getJSONObject(0).getString("code")
            PlaylistStore.setOwnCode(code)
            code
        }
    }

    suspend fun fetch(code: String): Result<List<PlTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, _) = CloudProfiles.session()
            val rq = Request.Builder()
                .url(SupabaseConfig.URL + "/rest/v1/rpc/get_shared_playlist")
                .header("apikey", SupabaseConfig.KEY)
                .header("Authorization", "Bearer " + token)
                .post(JSONObject().put("p_code", code).toString().toRequestBody(CloudProfiles.JSON_TYPE))
                .build()
            val (c, resp) = CloudProfiles.exec(rq)
            if (c == 404) throw IOException("Функция get_shared_playlist не найдена: выполните SQL (Run) в Supabase")
            if (c !in 200..299) throw IOException("Не удалось получить плейлист (" + c + ")")
            val rows = JSONArray(resp)
            if (rows.length() == 0) throw IOException("Плейлист по этой ссылке не найден")
            val ts = rows.getJSONObject(0).getJSONArray("tracks")
            (0 until ts.length()).map { i ->
                val o = ts.getJSONObject(i)
                PlTrack(
                    o.getString("id"),
                    o.optString("title"),
                    o.optString("artist"),
                    o.optLong("dur", 0L),
                    if (o.isNull("art")) null else o.optString("art")
                )
            }
        }
    }
}

/** Нажатие на ссылку amorx://playlist/КОД: плейлист сам загружается, импортируется и ставится на скачивание. */
object PlaylistLinkImport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun handle(context: Context, uri: android.net.Uri): Boolean {
        val code = CloudPlaylists.parseCode(uri.toString()) ?: return false
        val app = context.applicationContext
        PlaylistStore.init(app)
        scope.launch {
            android.widget.Toast.makeText(app, "Загружаем плейлист…", android.widget.Toast.LENGTH_SHORT).show()
            CloudPlaylists.fetch(code)
                .onSuccess { list ->
                    PlaylistStore.replace(list)
                    android.widget.Toast.makeText(app, "Импортировано песен: " + list.size, android.widget.Toast.LENGTH_LONG).show()
                    val err = PlaylistDownloader.start(app)
                    if (err != null) android.widget.Toast.makeText(app, err, android.widget.Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    android.widget.Toast.makeText(app, it.message ?: "Не удалось импортировать плейлист", android.widget.Toast.LENGTH_LONG).show()
                }
        }
        return true
    }
}
