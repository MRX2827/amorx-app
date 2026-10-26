package chromahub.rhythm.app.features.online

import android.content.ClipboardManager
import android.content.Context
import android.text.Html
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

private data class MimItem(
    val title: String,
    val artist: String,
    val source: String,
    val durationMs: Long,
    val art: String?
)

/** Импорт плейлиста Mimose: читаем публичную страницу, ищем каждую песню и кладём в плейлист AmorX. */
object MimoseImport {
    val state = MutableStateFlow(ImportState())
    val message = MutableStateFlow<String?>(null)
    val missing = MutableStateFlow<List<String>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun playlistId(link: String): String? =
        Regex("(?:mimose\\.fun/p/|mimose://playlist/)([A-Za-z0-9_-]{8,})").find(link)?.groupValues?.get(1)

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        try {
            if (c.responseCode != 200) throw RuntimeException("Mimose ответил HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun clean(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun field(row: String, cls: String): String =
        Regex("class=\"" + cls + "[^\"]*\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
            .find(row)?.groupValues?.get(1)?.let { clean(it) } ?: ""

    private fun dur(s: String): Long {
        val p = s.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (p.isEmpty()) return 0L
        return p.fold(0L) { acc, v -> acc * 60 + v } * 1000L
    }

    private fun parse(html: String): List<MimItem> {
        val out = ArrayList<MimItem>()
        val rows = Regex("<li class=\"page_row__[^\"]*\">(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (m in rows) {
            val r = m.groupValues[1]
            val title = field(r, "page_rowTitle__")
            if (title.isBlank()) continue
            val art = Regex("<img src=\"([^\"]*)\"").find(r)?.groupValues?.get(1)?.let { clean(it) }
            out.add(
                MimItem(
                    title,
                    field(r, "page_rowArtist__"),
                    field(r, "page_rowBadge__"),
                    dur(field(r, "page_rowDuration__")),
                    art?.takeIf { it.isNotBlank() }
                )
            )
        }
        return out
    }

    /** Возвращает трек и признак «длительность совпала»; null, если ничего не найдено. */
    private suspend fun findTrack(item: MimItem): Pair<OnlineTrack, Boolean>? {
        val sc = item.source.contains("SoundCloud", ignoreCase = true)
        val q = (if (sc) item.title else item.artist + " " + item.title).trim()
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
        if (item.durationMs > 45000L) {
            val close = top.firstOrNull { t ->
                t.durationMs > 0 && Math.abs(t.durationMs - item.durationMs) <= 15000L
            }
            if (close != null) return Pair(close, true)
            return Pair(top.first(), false)
        }
        return Pair(top.first(), true)
    }

    private suspend fun run(ctx: Context, id: String) {
        val rows = parse(fetch("https://mimose.fun/p/" + id))
        if (rows.isEmpty()) {
            message.value = "В плейлисте не нашлось песен (разметка страницы могла измениться)"
            return
        }
        state.value = ImportState(true, 0, rows.size)
        val results = arrayOfNulls<Pair<OnlineTrack, Boolean>>(rows.size)
        val sem = Semaphore(3)
        val doneCount = AtomicInteger(0)
        coroutineScope {
            rows.mapIndexed { i, item ->
                async {
                    sem.withPermit {
                        results[i] = findTrack(item)
                        state.value = ImportState(true, doneCount.incrementAndGet(), rows.size)
                    }
                }
            }.awaitAll()
        }
        val found = ArrayList<PlTrack>()
        val miss = ArrayList<String>()
        var inexact = 0
        rows.forEachIndexed { i, item ->
            val r = results[i]
            if (r == null) {
                miss.add(item.artist + " — " + item.title)
            } else {
                val t = r.first
                if (!r.second) inexact++
                found.add(PlTrack(t.id, t.title, t.artist, t.durationMs, t.artworkUrl ?: item.art))
            }
        }
        missing.value = miss
        val added = PlaylistStore.add(found)
        var msg = "Найдено " + found.size + " из " + rows.size + ", новых в плейлисте: " + added
        if (inexact > 0) msg += ". Другая длительность у " + inexact
        val err = PlaylistDownloader.start(ctx)
        message.value = if (err != null) msg + ". " + err else msg + ". Скачивание началось"
    }

    fun start(context: Context, link: String) {
        if (state.value.running) return
        val ctx = context.applicationContext
        val id = playlistId(link.trim())
        if (id == null) {
            message.value = "Ссылка не похожа на плейлист Mimose"
            return
        }
        PlaylistStore.init(ctx)
        OnlineDownloads.init(ctx)
        message.value = null
        missing.value = emptyList()
        job = scope.launch {
            state.value = ImportState(running = true)
            try {
                run(ctx, id)
            } catch (e: CancellationException) {
                message.value = "Импорт остановлен"
                throw e
            } catch (e: Exception) {
                message.value = "Не удалось импортировать: " + (e.message ?: "ошибка")
            } finally {
                state.value = ImportState()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

@Composable
fun MimoseImportCard() {
    val context = LocalContext.current
    val st by MimoseImport.state.collectAsState()
    val msg by MimoseImport.message.collectAsState()
    val miss by MimoseImport.missing.collectAsState()
    var link by remember { mutableStateOf("") }
    var showMissing by remember { mutableStateOf(false) }

    if (showMissing) {
        AlertDialog(
            onDismissRequest = { showMissing = false },
            title = { Text("Не найдено (" + miss.size + ")") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(miss) { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMissing = false }) { Text("Закрыть") } }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Импорт из Mimose", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                "Вставьте ссылку на плейлист: песни найдутся, попадут в плейлист AmorX и скачаются",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(12.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !st.running,
                label = { Text("mimose.fun/p/…") }
            )
            if (st.running) {
                Spacer(Modifier.size(12.dp))
                if (st.total > 0) {
                    LinearProgressIndicator(progress = { st.done.toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.size(4.dp))
                Text("Поиск: " + st.done + " из " + st.total, style = MaterialTheme.typography.bodySmall)
            }
            val m = msg
            if (m != null) {
                Spacer(Modifier.size(8.dp))
                Text(m, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (st.running) {
                    Button(onClick = { MimoseImport.cancel() }) { Text("Стоп") }
                } else {
                    Button(onClick = { MimoseImport.start(context, link) }, enabled = link.isNotBlank()) { Text("Импорт") }
                    FilledTonalButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        link = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    }) { Text("Вставить") }
                }
                if (miss.isNotEmpty()) {
                    TextButton(onClick = { showMissing = true }) { Text("Не найдено: " + miss.size) }
                }
            }
        }
    }
}
