import sys, os, shutil
O = "app/src/main/java/chromahub/rhythm/app/features/online/"
SCREEN = O + "OnlineScreen.kt"
NEW = {O + "PlaylistStore.kt": None, O + "PlaylistUi.kt": None}

def stop(m):
    print("СТОП " + m + ". Ничего не записано."); sys.exit(1)

for p in NEW:
    if os.path.exists(p): stop("файл уже существует: " + p)
s = open(SCREEN, encoding="utf-8").read()
anchor = "item { YandexImportCard(musicViewModel) }"
if s.count(anchor) != 1:
    stop("OnlineScreen: якорь найден %d раз (нужно 1)" % s.count(anchor))
s = s.replace(anchor, anchor + "\n        item { PlaylistCard(musicViewModel) }")

STORE = r'''package chromahub.rhythm.app.features.online

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

    private class Saved {
        var tracks: List<PlTrack>? = null
        var ownCode: String? = null
    }

    val tracks = MutableStateFlow<List<PlTrack>>(emptyList())
    val ownCode = MutableStateFlow<String?>(null)
    private var file: File? = null
    private val gson = Gson()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "amorx_playlist.json")
        file = f
        if (f.exists()) {
            val s = runCatching { gson.fromJson(f.readText(), Saved::class.java) }.getOrNull()
            if (s != null) {
                tracks.value = s.tracks.orEmpty()
                ownCode.value = s.ownCode
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        val s = Saved()
        s.tracks = tracks.value
        s.ownCode = ownCode.value
        runCatching { f.writeText(gson.toJson(s)) }
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

    fun linkFor(code: String): String = "amorx://playlist/" + code

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
'''

UI = r'''package chromahub.rhythm.app.features.online

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import kotlinx.coroutines.launch

@Composable
fun PlaylistCard(musicViewModel: MusicViewModel) {
    val context = LocalContext.current
    remember { PlaylistStore.init(context); OnlineDownloads.init(context); CloudProfiles.init(context); true }
    val tracks by PlaylistStore.tracks.collectAsState()
    val downloaded by OnlineDownloads.items.collectAsState()
    val running by PlaylistDownloader.running.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val haveIds = remember(downloaded) { downloaded.map { it.id }.toHashSet() }
    val have = tracks.count { it.id in haveIds }

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()

    fun startDownloads() {
        val err = PlaylistDownloader.start(context)
        if (err != null) toast(err) else toast("Скачивание плейлиста началось")
    }

    fun play() {
        val songs = tracks.mapNotNull { t -> downloaded.firstOrNull { it.id == t.id }?.toSong() }
        if (songs.isEmpty()) {
            toast("Пока нет скачанных песен из плейлиста")
        } else {
            musicViewModel.playQueue(songs, enableShuffle = false, startIndex = 0)
        }
    }

    fun copyLink() {
        if (busy) return
        scope.launch {
            busy = true
            CloudPlaylists.share(tracks)
                .onSuccess { code ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("AmorX playlist", CloudPlaylists.linkFor(code)))
                    toast("Ссылка скопирована. Отправьте её другу")
                }
                .onFailure { toast(it.message ?: "Не удалось поделиться") }
            busy = false
        }
    }

    fun pasteLink() {
        if (busy) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val code = CloudPlaylists.parseCode(text)
        if (code == null) {
            toast("В буфере нет ссылки на плейлист AmorX")
            return
        }
        scope.launch {
            busy = true
            CloudPlaylists.fetch(code)
                .onSuccess { list ->
                    PlaylistStore.replace(list)
                    toast("Импортировано песен: " + list.size)
                    startDownloads()
                }
                .onFailure { toast(it.message ?: "Не удалось импортировать") }
            busy = false
        }
    }

    fun addPlaying() {
        val cur = musicViewModel.currentSong.value
        if (cur == null) {
            toast("Сейчас ничего не играет")
            return
        }
        val vid = ArtworkUtil.videoId(cur.id)
        if (vid == null) {
            toast("Локальные файлы с телефона добавить нельзя")
            return
        }
        val n = PlaylistStore.add(
            listOf(PlTrack(vid, cur.title, cur.artist, cur.duration, ArtworkUtil.forSong(cur.id, cur.artworkUri?.toString())))
        )
        toast(if (n > 0) "Добавлено в плейлист" else "Эта песня уже в плейлисте")
    }

    fun addDownloaded() {
        val n = PlaylistStore.add(downloaded.map {
            PlTrack(it.id, it.title, it.artist, it.durationMs, ArtworkUtil.forSong("online_" + it.id, null))
        })
        toast("Добавлено песен: " + n)
    }

    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text("Плейлист AmorX (" + tracks.size + ")") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(tracks, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    t.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (t.id in haveIds) {
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showList = false }) { Text("Закрыть") } }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Плейлист AmorX", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                if (tracks.isEmpty()) "Пусто. Добавьте песни через «⋮» или вставьте ссылку друга"
                else "Песен: " + tracks.size + " · скачано: " + have,
                style = MaterialTheme.typography.bodyMedium
            )
            if (running || busy) {
                Spacer(Modifier.size(12.dp))
                if (tracks.isNotEmpty()) {
                    LinearProgressIndicator(progress = { have.toFloat() / tracks.size }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { play() }, enabled = have > 0) { Text("Играть") }
                if (have < tracks.size) {
                    FilledTonalButton(onClick = { startDownloads() }, enabled = !running) {
                        Text(if (running) "Качаю…" else "Скачать всё")
                    }
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Скопировать ссылку") }, onClick = { menu = false; copyLink() })
                        DropdownMenuItem(text = { Text("Вставить ссылку") }, onClick = { menu = false; pasteLink() })
                        DropdownMenuItem(text = { Text("Добавить играющую песню") }, onClick = { menu = false; addPlaying() })
                        DropdownMenuItem(text = { Text("Добавить все скачанные") }, onClick = { menu = false; addDownloaded() })
                        DropdownMenuItem(text = { Text("Показать список") }, onClick = { menu = false; showList = true })
                        DropdownMenuItem(text = { Text("Очистить плейлист") }, onClick = { menu = false; PlaylistStore.clear() })
                    }
                }
            }
        }
    }
}
'''

bk = os.path.expanduser("~/Desktop/playlist_backup"); os.makedirs(bk, exist_ok=True)
shutil.copy2(SCREEN, os.path.join(bk, "OnlineScreen.kt"))
open(SCREEN, "w", encoding="utf-8").write(s)
open(O + "PlaylistStore.kt", "w", encoding="utf-8").write(STORE)
open(O + "PlaylistUi.kt", "w", encoding="utf-8").write(UI)
print("Готово: плейлист AmorX добавлен. Копия OnlineScreen.kt: ~/Desktop/playlist_backup")
