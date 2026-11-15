package amorx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory

suspend fun searchArtists(query: String): List<Artist> = withContext(Dispatchers.IO) {
    NewPipeDownloader.init()
    val service = ServiceList.YouTube
    val filters: List<List<String>> = listOf(
        listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS),
        listOf(YoutubeSearchQueryHandlerFactory.CHANNELS)
    )
    var lastError: Exception? = null
    for (filter in filters) {
        try {
            val handler = service.searchQHFactory.fromQuery(query, filter, "")
            val info = SearchInfo.getInfo(service, handler)
            val list = info.relatedItems
                .filterIsInstance<ChannelInfoItem>()
                .map { ch ->
                    Artist(ch.name.removeSuffix(" - Topic"), ch.thumbnails.maxByOrNull { it.height }?.url)
                }
                .distinctBy { it.name.lowercase() }
            if (list.isNotEmpty()) return@withContext list.take(15)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
        }
    }
    lastError?.let { throw it }
    emptyList()
}

class ArtistFeed(private val name: String) {
    private var pool: ArrayDeque<Track>? = null

    fun reset() { pool = null }

    suspend fun preload() {
        if (pool != null) return
        val a = runCatching { Repo.search(name) }.getOrDefault(emptyList())
        val b = runCatching { Repo.search("$name лучшие песни") }.getOrDefault(emptyList())
        val found = (a + b).distinctBy { it.id }
        val mine = found.filter { it.artist.contains(name, ignoreCase = true) }.ifEmpty { found.take(3) }
        pool = ArrayDeque(mine.take(25).shuffled())
    }

    suspend fun take(seen: Set<String>): Track? {
        preload()
        val p = pool ?: return null
        while (p.isNotEmpty()) {
            val t = p.removeFirst()
            if (t.id !in seen) return t
        }
        return null
    }
}

/** Бесконечная подборка: артисты чередуются, повторы фильтруются, круг начинается заново. */
class WaveFeed(artists: List<Artist>) {
    private val feeds = artists.map { ArtistFeed(it.name) }.shuffled()
    private val seen = HashSet<String>()
    private var rr = 0
    private var started = false

    suspend fun next(count: Int): List<Track> {
        if (feeds.isEmpty()) return emptyList()
        if (!started) {
            started = true
            coroutineScope { feeds.take(6).map { f -> async { f.preload() } }.awaitAll() }
        }
        var out = pick(count)
        if (out.isEmpty()) {
            seen.clear()
            feeds.forEach { it.reset() }
            rr = 0
            out = pick(count)
        }
        return out
    }

    private suspend fun pick(count: Int): List<Track> {
        val picked = ArrayList<Track>()
        var empty = 0
        while (picked.size < count && empty < feeds.size) {
            val f = feeds[rr % feeds.size]
            rr++
            val t = f.take(seen)
            if (t == null) empty++ else {
                empty = 0
                seen.add(t.id)
                picked.add(t)
            }
        }
        return picked
    }
}

/** Рекомендации под карточкой волны, бесконечная подгрузка при прокрутке. */
object WaveRecs {
    private const val PAGE = 20
    val tracks = mutableStateOf<List<Track>>(emptyList())
    val loading = mutableStateOf(false)
    val loadingMore = mutableStateOf(false)
    private var feed: WaveFeed? = null
    private var builtFor: String? = null
    private var busy = false

    suspend fun refresh(artists: List<Artist>) {
        if (artists.isEmpty()) {
            tracks.value = emptyList(); feed = null; builtFor = null
            return
        }
        val key = artists.map { it.name.lowercase() }.sorted().joinToString("|")
        if (key == builtFor && tracks.value.isNotEmpty()) return
        loading.value = true
        try {
            val f = WaveFeed(artists)
            val list = f.next(PAGE)
            if (list.isNotEmpty()) {
                feed = f; tracks.value = list; builtFor = key
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
        } finally {
            loading.value = false
        }
    }

    suspend fun loadMore() {
        val f = feed ?: return
        if (busy) return
        busy = true
        loadingMore.value = true
        try {
            val more = f.next(PAGE)
            if (more.isNotEmpty()) tracks.value = tracks.value + more
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
        } finally {
            loadingMore.value = false
            busy = false
        }
    }
}

@Composable
fun ArtistAvatar(a: Artist, size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            a.name.trim().take(1).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        RemoteImage(a.avatar, Modifier.fillMaxSize())
    }
}

@Composable
fun WaveScreen(p: PlayerState) {
    val scope = rememberCoroutineScope()
    val artists by WaveStore.artists
    val recs by WaveRecs.tracks
    val recsLoading by WaveRecs.loading
    val recsMore by WaveRecs.loadingMore
    var picker by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    if (picker) ArtistPicker { picker = false }

    LaunchedEffect(artists) {
        delay(800)
        WaveRecs.refresh(artists)
    }
    LaunchedEffect(recs.size) {
        if (recs.isEmpty()) return@LaunchedEffect
        while (true) {
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
            }.first { it }
            WaveRecs.loadMore()
            delay(3000)
        }
    }

    fun start() {
        if (artists.isEmpty()) { picker = true; return }
        if (starting) return
        scope.launch {
            starting = true
            msg = null
            try {
                val feed = WaveFeed(artists)
                val first = feed.next(5)
                if (first.isEmpty()) msg = "Не удалось собрать волну. Проверьте интернет и попробуйте ещё раз"
                else p.play(first[0], first, feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                msg = "Ошибка: ${e.message}"
            } finally {
                starting = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Text(
                "Волна",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
            )
        }
        item {
            AnimateIn {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "Моя волна",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (artists.isEmpty()) "Выберите любимых артистов, и волна подберёт музыку"
                            else artists.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(16.dp))
                        if (starting) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                        }
                        msg?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { start() }, enabled = !starting) {
                                Text(if (starting) "Готовлю…" else "Запустить")
                            }
                            FilledTonalButton(onClick = { picker = true }) {
                                Text(if (artists.isEmpty()) "Выбрать артистов" else "Артисты (${artists.size})")
                            }
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item {
                AnimateIn {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        items(artists, key = { it.name }) { a ->
                            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                ArtistAvatar(a, 64.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    a.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty() && (recs.isNotEmpty() || recsLoading)) {
            item {
                Text(
                    "Рекомендации",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (recsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            itemsIndexed(recs, key = { i, t -> "rec_" + i + "_" + t.id }) { _, t ->
                AnimateIn(Modifier.animateItem()) {
                    TrackRow(t, p.current?.id == t.id && p.playing, onClick = { p.play(t, recs) }) {
                        DownloadButton(t)
                    }
                }
            }
            if (recsMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistPicker(onDismiss: () -> Unit) {
    val selected by WaveStore.artists
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (q.isBlank() || busy) return
        scope.launch {
            busy = true
            info = null
            try {
                results = searchArtists(q.trim())
                if (results.isEmpty()) info = "Артисты не найдены"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                results = emptyList()
                info = "Ошибка: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Артисты для волны") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    OutlinedTextField(
                        value = q,
                        onValueChange = { q = it },
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                            if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) {
                                search(); true
                            } else false
                        },
                        placeholder = { Text("Найти артиста") },
                        trailingIcon = { if (q.isNotBlank()) TextButton(onClick = { search() }) { Text("Найти") } },
                        singleLine = true
                    )
                }
                if (selected.isNotEmpty()) {
                    item {
                        Text(
                            "Выбрано",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(selected, key = { "s_" + it.name }) { a ->
                        ArtistRow(a, true) { WaveStore.remove(a.name) }
                    }
                }
                if (busy) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
                info?.let { m -> item { Text(m, modifier = Modifier.padding(vertical = 12.dp)) } }
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "Результаты",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(results, key = { "r_" + it.name }) { a ->
                        val added = selected.any { it.name.equals(a.name, ignoreCase = true) }
                        ArtistRow(a, added) { if (added) WaveStore.remove(a.name) else WaveStore.add(a) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun ArtistRow(a: Artist, added: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtistAvatar(a, 44.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            a.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggle) {
            Icon(if (added) Icons.Rounded.Close else Icons.Rounded.Add, if (added) "Убрать" else "Добавить")
        }
    }
}
