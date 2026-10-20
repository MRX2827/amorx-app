#!/usr/bin/env python3
# AmorX: новый вид импортированного плейлиста (экран как у «Скачанного»,
# строки как в «Рекомендациях», «Играть всё» и «Вперемешку»).
# Запускать из корня проекта Rhythm (рядом с gradlew). Нужен уже применённый amorx_yandex_import.py.
# Если что-то не сходится, ничего не записывает и пишет «СТОП».

import os
import shutil
import sys

BASE = "app/src/main/java/chromahub/rhythm/app/features/online"
SCREEN = BASE + "/OnlineScreen.kt"
YFILE = BASE + "/YandexImport.kt"
BACKUP = os.path.expanduser("~/Desktop/yandex_backup2")

problems = []
if not os.path.isfile("gradlew") or not os.path.isdir(BASE):
    print("СТОП: запустите скрипт из корня проекта Rhythm (рядом с gradlew)")
    sys.exit(1)

if not os.path.isfile(YFILE):
    problems.append("нет YandexImport.kt: сначала примените amorx_yandex_import.py")
else:
    y = open(YFILE, encoding="utf-8").read()
    if "object YandexImport" not in y:
        problems.append("YandexImport.kt выглядит неожиданно")
    if "YandexPlaylistScreen" in y:
        problems.append("новый вид уже применён")

screen = open(SCREEN, encoding="utf-8").read()
OLD = "private fun OnlineTrackRow("
NEW = "internal fun OnlineTrackRow("
if screen.count(OLD) != 1:
    problems.append("OnlineScreen.kt: OnlineTrackRow найден %d раз, нужно ровно 1" % screen.count(OLD))

if problems:
    print("СТОП. Ничего не записано. Проблемы:")
    for p in problems:
        print(" -", p)
    sys.exit(1)

KOTLIN = r'''package chromahub.rhythm.app.features.online

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

data class ImportState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

private data class YaItem(val title: String, val artist: String, val durationMs: Long)

/** Импорт плейлиста Яндекс Музыки: читаем список по ссылке и ищем каждую песню через поиск приложения. */
object YandexImport {
    private const val PREFS = "online_yandex"
    private const val SEP_ITEM = "\u001E"
    private const val SEP_FIELD = "\u001F"

    val state = MutableStateFlow(ImportState())
    val tracks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val missing = MutableStateFlow<List<String>>(emptyList())
    val title = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val p = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        title.value = p.getString("title", "") ?: ""
        missing.value = (p.getString("missing", "") ?: "").split(SEP_ITEM).filter { it.isNotBlank() }
        tracks.value = (p.getString("tracks", "") ?: "").split(SEP_ITEM)
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val f = entry.split(SEP_FIELD)
                if (f.size < 4) null
                else OnlineTrack(
                    videoUrl = f[0],
                    title = f[1],
                    artist = f[2],
                    durationMs = f[3].toLongOrNull() ?: 0L,
                    artworkUrl = f.getOrNull(4)?.takeIf { it.isNotBlank() }
                )
            }
    }

    private fun save() {
        val ctx = appContext ?: return
        val t = tracks.value.joinToString(SEP_ITEM) {
            listOf(it.videoUrl, it.title, it.artist, it.durationMs.toString(), it.artworkUrl ?: "")
                .joinToString(SEP_FIELD)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tracks", t)
            .putString("missing", missing.value.joinToString(SEP_ITEM))
            .putString("title", title.value)
            .apply()
    }

    fun clear() {
        job?.cancel()
        state.value = ImportState()
        tracks.value = emptyList()
        missing.value = emptyList()
        title.value = ""
        save()
    }

    private fun apiUrl(link: String): String? {
        val uuid = Regex("""lk\.[a-f0-9\-]+""").find(link)?.value
        if (uuid != null) return "https://api.music.yandex.net/playlist/" + uuid
        val m = Regex("""users/([^/?#]+)/playlists/(\d+)""").find(link)
        if (m != null) {
            return "https://api.music.yandex.net/users/" + m.groupValues[1] + "/playlists/" + m.groupValues[2]
        }
        return null
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Yandex-Music-API")
        c.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023231")
        try {
            if (c.responseCode != 200) throw RuntimeException("Яндекс ответил HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun findTrack(item: YaItem): OnlineTrack? {
        val q = (item.artist + " " + item.title).trim()
        var list: List<OnlineTrack> = emptyList()
        for (attempt in 0..1) {
            try {
                list = OnlineRepository.search(q)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) delay(700)
            }
        }
        val top = list.take(5)
        if (top.isEmpty()) return null
        if (item.durationMs > 0) {
            val close = top.filter { t ->
                t.durationMs > 0 && Math.abs(t.durationMs - item.durationMs) <= 15000L
            }
            if (close.isNotEmpty()) return close.first()
        }
        return top.first()
    }

    fun start(link: String) {
        if (state.value.running) return
        val api = apiUrl(link.trim())
        if (api == null) {
            message.value = "Ссылка не похожа на плейлист Яндекс Музыки"
            return
        }
        job = scope.launch {
            state.value = ImportState(running = true)
            try {
                val root = JSONObject(fetch(api)).getJSONObject("result")
                val plTitle = root.optString("title", "Плейлист")
                val arr = root.getJSONArray("tracks")
                val items = ArrayList<YaItem>()
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i).optJSONObject("track") ?: continue
                    val names = t.optJSONArray("artists")
                    val artist = if (names != null && names.length() > 0) {
                        names.getJSONObject(0).optString("name")
                    } else ""
                    val version = t.optString("version", "")
                    val name = (t.optString("title") + " " + version).trim()
                    if (name.isBlank()) continue
                    items.add(YaItem(name, artist, t.optLong("durationMs", 0L)))
                }
                if (items.isEmpty()) {
                    message.value = "В плейлисте не нашлось песен"
                    state.value = ImportState()
                    return@launch
                }
                state.value = ImportState(true, 0, items.size)
                val results = arrayOfNulls<OnlineTrack>(items.size)
                val sem = Semaphore(3)
                val doneCount = AtomicInteger(0)
                coroutineScope {
                    items.mapIndexed { i, item ->
                        async {
                            sem.withPermit {
                                results[i] = findTrack(item)
                                state.value = ImportState(true, doneCount.incrementAndGet(), items.size)
                            }
                        }
                    }.awaitAll()
                }
                val found = results.filterNotNull().distinctBy { it.videoUrl }
                tracks.value = found
                missing.value = items.indices
                    .filter { results[it] == null }
                    .map { items[it].artist + " — " + items[it].title }
                title.value = plTitle
                save()
                state.value = ImportState()
                message.value = "Импортировано " + found.size + " из " + items.size
            } catch (e: CancellationException) {
                state.value = ImportState()
                throw e
            } catch (e: Exception) {
                state.value = ImportState()
                message.value = "Импорт не удался: " + (e.message ?: "неизвестная ошибка")
            }
        }
    }
}

@Composable
fun YandexImportCard(musicViewModel: MusicViewModel) {
    val context = LocalContext.current
    remember { YandexImport.init(context); true }
    val st by YandexImport.state.collectAsState()
    val tracks by YandexImport.tracks.collectAsState()
    val plTitle by YandexImport.title.collectAsState()
    val msg by YandexImport.message.collectAsState()
    var showLink by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }

    LaunchedEffect(msg) {
        msg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            YandexImport.message.value = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Из Яндекс Музыки", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                text = when {
                    st.running -> "Ищу песни: ${st.done} из ${st.total}"
                    tracks.isNotEmpty() -> (if (plTitle.isBlank()) "Плейлист" else plTitle) + " · " + tracks.size
                    else -> "Вставьте ссылку на плейлист, и песни найдутся здесь"
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(16.dp))
            if (st.running) {
                val frac = if (st.total > 0) st.done.toFloat() / st.total.toFloat() else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tracks.isNotEmpty()) {
                        Button(onClick = { showList = true }) { Text("Открыть") }
                        FilledTonalButton(onClick = { showLink = true }) { Text("Новая ссылка") }
                    } else {
                        Button(onClick = { showLink = true }) { Text("Вставить ссылку") }
                    }
                }
            }
        }
    }

    if (showLink) {
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text("Ссылка на плейлист") },
            text = {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("https://music.yandex.ru/...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLink = false
                    YandexImport.start(link)
                }) { Text("Импортировать") }
            },
            dismissButton = { TextButton(onClick = { showLink = false }) { Text("Отмена") } }
        )
    }

    if (showList) {
        YandexPlaylistScreen(musicViewModel = musicViewModel, onClose = { showList = false })
    }
}

@Composable
private fun YandexPlaylistScreen(musicViewModel: MusicViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val tracks by YandexImport.tracks.collectAsState()
    val missing by YandexImport.missing.collectAsState()
    val plTitle by YandexImport.title.collectAsState()
    val downloaded by OnlineDownloads.items.collectAsState()
    val pending by OnlineDownloads.pending.collectAsState()
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Первая песня получает настоящую ссылку, остальные получают заготовку:
    // плеер сам обновит её перед воспроизведением (механизм OnlineLinks).
    fun playList(list: List<OnlineTrack>, start: Int) {
        if (resolving != null || list.isEmpty()) return
        val first = list[start]
        scope.launch {
            resolving = first.videoUrl
            val r = runCatching { OnlineRepository.resolveAudioUrl(first.videoUrl) }
            resolving = null
            r.onSuccess { url ->
                val songs = list.mapIndexed { i, t ->
                    if (i == start) t.toSong(url)
                    else t.toSong("https://www.youtube.com/watch?v=" + t.id + "&expire=0")
                }
                musicViewModel.playQueue(songs, enableShuffle = false, startIndex = start)
            }.onFailure {
                Toast.makeText(context, "Не удалось получить трек: " + it.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить плейлист?") },
            text = { Text("Плейлист исчезнет из приложения. Скачанные песни останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    YandexImport.clear()
                    onClose()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (plTitle.isBlank()) "Плейлист" else plTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(top = 16.dp, bottom = 4.dp)
                        )
                        TextButton(onClick = onClose) { Text("Закрыть") }
                    }
                }
                item {
                    Text(
                        text = tracks.size.toString() + " песен",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { playList(tracks, 0) }) { Text("Играть всё") }
                        FilledTonalButton(onClick = { playList(tracks.shuffled(), 0) }) {
                            Text("Вперемешку")
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                }
                items(tracks, key = { it.videoUrl }) { t ->
                    val id = t.id
                    OnlineTrackRow(
                        track = t,
                        resolving = resolving == t.videoUrl,
                        isDownloaded = downloaded.any { it.id == id },
                        downloadProgress = pending[id]?.progress,
                        onDownload = {
                            OnlineDownloads.download(t)
                            Toast.makeText(context, "Скачивание началось", Toast.LENGTH_SHORT).show()
                        },
                        onClick = { playList(tracks, tracks.indexOf(t)) }
                    )
                }
                if (missing.isNotEmpty()) {
                    item {
                        Text(
                            text = "Не нашлось (" + missing.size + ")",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                        )
                    }
                    items(missing) { m ->
                        Text(
                            text = m,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                        )
                    }
                }
                item {
                    Spacer(Modifier.size(16.dp))
                    TextButton(onClick = { confirmDelete = true }) { Text("Удалить плейлист") }
                }
            }
        }
    }
}
'''

os.makedirs(BACKUP, exist_ok=True)
shutil.copy2(SCREEN, os.path.join(BACKUP, "OnlineScreen.kt"))
shutil.copy2(YFILE, os.path.join(BACKUP, "YandexImport.kt"))

open(SCREEN, "w", encoding="utf-8").write(screen.replace(OLD, NEW, 1))
open(YFILE, "w", encoding="utf-8").write(KOTLIN)
print("Готово: новый вид плейлиста добавлен. Копии: " + BACKUP)
