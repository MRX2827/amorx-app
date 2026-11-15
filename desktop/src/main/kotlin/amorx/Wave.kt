package amorx

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
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
import java.io.File

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

internal object PlaybackHistory {
    private const val LIMIT = 30
    private val file = File(AppDir.root, "recent-tracks.json")
    private val gson = Gson()
    private val recent = mutableStateOf(
        runCatching {
            gson.fromJson(file.readText(), Array<Entry>::class.java).orEmpty()
                .mapNotNull(Entry::toTrack)
                .take(LIMIT)
        }.getOrNull().orEmpty()
    )
    val items: State<List<Track>> = recent

    @Synchronized
    fun record(track: Track) {
        if (!track.isReal || track == recent.value.firstOrNull()) return
        val updated = (listOf(track) + recent.value.filter { it.id != track.id }).take(LIMIT)
        recent.value = updated
        runCatching { writeLocalJson(file, gson.toJson(updated.map(::Entry))) }
    }

    private data class Entry(
        val id: String,
        val title: String,
        val artist: String,
        val seconds: Int,
        val artwork: String?,
        val videoUrl: String?,
        val localPath: String?,
    ) {
        constructor(track: Track) : this(
            track.id, track.title, track.artist, track.seconds, track.artwork, track.videoUrl, track.localPath
        )

        fun toTrack(): Track? {
            val path = localPath?.takeIf { File(it).isFile }
            if (path == null && videoUrl == null) return null
            return Track(id, title, artist, seconds, colorFor(id), artwork, videoUrl, path)
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
    val recent by PlaybackHistory.items
    val downloads by Downloads.items
    val quickPicks = remember(recent, downloads, recs) {
        (recent + recs + downloads.asReversed().map { it.toTrack() }).distinctBy { it.id }.take(6)
    }
    var picker by remember { mutableStateOf(false) }
    var waveOptions by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    if (picker) ArtistPicker { picker = false }

    LaunchedEffect(artists) {
        delay(350)
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
        if (artists.isEmpty()) {
            picker = true
            return
        }
        if (starting) return
        scope.launch {
            starting = true
            error = null
            try {
                val feed = WaveFeed(artists)
                val first = feed.next(5)
                if (first.isEmpty()) error = "Не удалось запустить волну. Проверьте подключение и попробуйте ещё раз."
                else p.play(first.first(), first, feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось запустить волну."
            } finally {
                starting = false
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = if (maxWidth >= 760.dp) 32.dp else 20.dp
        val quickWidth = (maxWidth * 0.10f).coerceIn(108.dp, 148.dp)
        val heroHeight = (maxHeight * 0.51f).coerceIn(158.dp, 460.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 28.dp, bottom = 20.dp)
        ) {
            item("hero") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = edge),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Моя волна", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Персональная подборка треков и рекомендаций для вас",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    WaveControl(Icons.Rounded.Person, "Выбрать исполнителей", onClick = { picker = true })
                    Box {
                        WaveControl(Icons.Rounded.Tune, "Настройки волны", onClick = { waveOptions = true })
                        DropdownMenu(expanded = waveOptions, onDismissRequest = { waveOptions = false }) {
                            DropdownMenuItem(
                                text = { Text("Исполнители (${artists.size})") },
                                onClick = {
                                    waveOptions = false
                                    picker = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("К рекомендациям") },
                                enabled = recs.isNotEmpty(),
                                onClick = {
                                    waveOptions = false
                                    scope.launch { listState.animateScrollToItem(2) }
                                }
                            )
                        }
                    }
                    WaveControl(
                        Icons.Rounded.PlayArrow,
                        "Запустить волну",
                        primary = true,
                        busy = starting,
                        onClick = ::start
                    )
                }
                error?.let {
                    Text(it, Modifier.padding(horizontal = edge, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                WaveParticles(
                    playing = p.playing && !p.loading,
                    modifier = Modifier.fillMaxWidth().height(heroHeight)
                )
            }
            item("quick-picks") {
                Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    Text(
                        "Быстрый выбор",
                        Modifier.padding(horizontal = edge),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            recent.isNotEmpty() -> "Недавно прослушанное и подборка для вас"
                            quickPicks.isNotEmpty() -> "Выберите трек, чтобы начать слушать"
                            else -> "Здесь появятся прослушанные треки"
                        },
                        Modifier.padding(start = edge, end = edge, top = 6.dp, bottom = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (quickPicks.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = edge),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(quickPicks, key = { it.id }) { track ->
                                WaveTrackCard(
                                    track,
                                    quickWidth,
                                    p.current?.id == track.id && p.playing,
                                    modifier = Modifier.animateItem(),
                                    onClick = { p.play(track, quickPicks) }
                                )
                            }
                        }
                    }
                }
            }
            if (artists.isNotEmpty()) {
                item("recommendations") {
                    Column(Modifier.padding(horizontal = edge)) {
                        Text("Рекомендации", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (recsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                        if (recs.isEmpty() && !recsLoading) {
                            Text(
                                "Пока нет треков по выбранным исполнителям",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                itemsIndexed(recs, key = { index, track -> "rec_${index}_${track.id}" }) { _, track ->
                    Box(Modifier.animateItem().padding(horizontal = edge)) {
                        TrackRow(
                            track,
                            p.current?.id == track.id && p.playing,
                            onClick = { p.play(track, recs) }
                        ) { DownloadButton(track) }
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
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun WaveControl(
    icon: ImageVector,
    label: String,
    primary: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else if (hovered) 1.07f else 1f,
        tween(160),
        label = "wave control scale"
    )
    val background = when {
        primary -> scheme.inverseSurface
        hovered -> scheme.surfaceContainerHighest
        else -> scheme.surfaceContainerHigh
    }
    val tint = if (primary) scheme.inverseOnSurface else scheme.onSurface

    Box(
        Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
        else Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun WaveTrackCard(
    track: Track,
    width: Dp,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val zoom by animateFloatAsState(if (hovered) 1.08f else 1f, tween(320), label = "wave artwork zoom")
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(140), label = "wave track scale")
    val controlAlpha by animateFloatAsState(if (hovered || active) 1f else 0f, tween(160), label = "wave track control")
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(width, width * 1.34f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, if (hovered || active) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f), shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    ) {
        Box(
            Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.MusicNote, null,
                tint = Color.White.copy(alpha = 0.30f),
                modifier = Modifier.size(32.dp)
            )
            RemoteImage(track.artwork, Modifier.fillMaxSize())
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.91f))
            )
        )
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp)
                .graphicsLayer { alpha = controlAlpha }
                .clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (active) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                null, Modifier.size(17.dp), tint = Color.Black
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(
                track.title, color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist, color = Color.White.copy(alpha = 0.76f),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ArtistPicker(onDismiss: () -> Unit) {
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
