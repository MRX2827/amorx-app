package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

private const val favoritesId = "favorites"
private const val downloadsId = "downloads"
private const val localId = "local"

private data class LibrarySection(
    val id: String,
    val name: String,
    val subtitle: String,
    val icon: ImageVector,
    val count: Int
)

private enum class LibrarySort(val text: String) {
    Default("Порядок добавления"),
    Title("По названию"),
    Artist("По исполнителю"),
    Duration("По длительности")
}

@Composable
fun DownloadsScreen(p: PlayerState, openAudio: () -> File? = { chooseAudio() }) {
    val stored by Downloads.items
    val pending by Downloads.pending
    val liked by Likes.items
    val library by DesktopLibrary.data
    val sections = listOf(
        LibrarySection(favoritesId, "Любимые треки", "Все сохранённые треки", Icons.Rounded.Favorite, liked.size),
        LibrarySection(downloadsId, "Скачанное", "Доступно без интернета", Icons.Rounded.Download, stored.size),
        LibrarySection(localId, "Локальные файлы", "Аудио с компьютера", Icons.Rounded.FolderOpen, library.local.size)
    )
    val playlists = library.playlists.map {
        LibrarySection(it.id, it.name, "Плейлист", Icons.Rounded.QueueMusic, it.tracks.size)
    }
    var sectionId by remember { mutableStateOf(downloadsId) }
    var createPlaylist by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Downloaded?>(null) }
    var deletePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    val openFile = {
        openAudio()?.let(DesktopLibrary::openLocal)?.let {
            p.play(it, listOf(it))
            sectionId = localId
        }
        Unit
    }

    if (createPlaylist) {
        CreateLibraryPlaylist(
            onCreated = { sectionId = it.id },
            close = { createPlaylist = false }
        )
    }
    toDelete?.let { download ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить из загрузок?") },
            text = { Text("«${download.title}» будет удалён с компьютера.") },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.delete(download.id)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }
    deletePlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deletePlaylist = null },
            title = { Text("Удалить плейлист?") },
            text = { Text("Плейлист «${playlist.name}» будет удалён из библиотеки.") },
            confirmButton = {
                TextButton(onClick = {
                    if (DesktopLibrary.deletePlaylist(playlist.id)) {
                        sectionId = downloadsId
                        deletePlaylist = null
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deletePlaylist = null }) { Text("Отмена") } }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val split = maxWidth >= 690.dp
        val select: (String) -> Unit = { sectionId = it }
        val details: @Composable (Modifier) -> Unit = { modifier ->
            AnimatedContent(
                targetState = sectionId,
                modifier = modifier,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(230)) { it / 25 }) togetherWith fadeOut(tween(120))
                },
                label = "library section"
            ) { id ->
                val section = (sections + playlists).firstOrNull { it.id == id } ?: sections[1]
                val playlist = library.playlists.firstOrNull { it.id == id }
                val tracks = remember(id, stored, liked, library) {
                    when (id) {
                        favoritesId -> liked.mapNotNull { it.toTrack() }
                        downloadsId -> stored.sortedByDescending { it.addedAt }.map { it.toTrack() }
                        localId -> library.local.mapNotNull { it.toTrack() }
                        else -> playlist?.tracks.orEmpty().mapNotNull { it.toTrack() }
                    }
                }
                val removeTrack: (Track) -> Unit = { track ->
                    when (id) {
                        downloadsId -> toDelete = stored.find { it.id == track.id }
                        favoritesId -> Likes.remove(track.id)
                        localId -> DesktopLibrary.removeLocal(track.id)
                        else -> DesktopLibrary.removeFromPlaylist(id, track.id)
                    }
                }
                val addFile = openFile.takeIf { id == localId }
                LibraryTrackPane(
                    section, tracks, p,
                    pending = if (id == downloadsId) pending.values.toList() else emptyList(),
                    downloadSize = if (id == downloadsId && stored.isNotEmpty()) stored.sumOf { it.sizeBytes } else null,
                    openFile = addFile,
                    playlist = playlist,
                    onDeletePlaylist = { deletePlaylist = playlist },
                    removeTrack = removeTrack
                )
            }
        }
        if (split) {
            val railWidth = (maxWidth * 0.24f).coerceIn(230.dp, 300.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LibrarySectionLabel("ТРЕКИ")
                        sections.forEach { LibrarySectionRow(it, sectionId == it.id) { select(it.id) } }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            LibrarySectionLabel("ПЛЕЙЛИСТЫ", Modifier.weight(1f))
                            IconButton(onClick = { createPlaylist = true }) { Icon(Icons.Rounded.Add, "Создать плейлист") }
                        }
                        playlists.forEach { LibrarySectionRow(it, sectionId == it.id) { select(it.id) } }
                    }
                }
                details(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CompactLibraryMenu(sections + playlists, sectionId, select, Modifier.weight(1f))
                    IconButton(onClick = { createPlaylist = true }) {
                        Icon(Icons.Rounded.Add, "Создать плейлист")
                    }
                }
                details(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CompactLibraryMenu(
    sections: List<LibrarySection>,
    active: String,
    select: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = 250.dp)) {
            Text(sections.firstOrNull { it.id == active }?.name ?: "Библиотека", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, "Разделы")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sections.forEach { section ->
                DropdownMenuItem(
                    text = { Text(section.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(section.icon, null) },
                    onClick = {
                        select(section.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LibrarySectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun LibrarySectionRow(section: LibrarySection, active: Boolean, select: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().libraryInteraction(active, select).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) { Icon(section.icon, null, modifier = Modifier.size(21.dp), tint = colors.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                section.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
            )
            Text(
                section.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
        }
        Text(
            section.count.toString(),
            modifier = Modifier.clip(CircleShape).background(colors.surfaceContainerHigh).padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun LibraryTrackPane(
    section: LibrarySection,
    tracks: List<Track>,
    p: PlayerState,
    pending: List<Pending>,
    downloadSize: Long?,
    openFile: (() -> Unit)?,
    playlist: DesktopPlaylist?,
    onDeletePlaylist: () -> Unit,
    removeTrack: (Track) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.Default) }
    var sortMenu by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val results = remember(query, sort, tracks) {
        val text = query.trim()
        val matches = tracks.filter { it.title.contains(text, ignoreCase = true) || it.artist.contains(text, ignoreCase = true) }
        when (sort) {
            LibrarySort.Default -> matches
            LibrarySort.Title -> matches.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            LibrarySort.Artist -> matches.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            LibrarySort.Duration -> matches.sortedBy { it.seconds }
        }
    }
    LaunchedEffect(query, sort) { listState.scrollToItem(0) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 540.dp
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                section.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val size = downloadSize?.let { " · ${"%.1f".format(it / 1048576.0)} МБ" }.orEmpty()
                            Text(
                                trackCount(tracks.size) + size,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        if (openFile != null) {
                            IconButton(onClick = openFile) { Icon(Icons.Rounded.FolderOpen, "Открыть аудиофайл") }
                        }
                        if (compact) {
                            FilledTonalIconButton(
                                onClick = { results.firstOrNull()?.let { p.play(it, results) } },
                                enabled = results.isNotEmpty()
                            ) { Icon(Icons.Rounded.PlayArrow, "Слушать") }
                        } else {
                            FilledTonalButton(
                                onClick = { results.firstOrNull()?.let { p.play(it, results) } },
                                enabled = results.isNotEmpty()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Слушать")
                            }
                        }
                        FilledTonalIconButton(
                            onClick = {
                                val shuffled = results.shuffled()
                                shuffled.firstOrNull()?.let { p.play(it, shuffled) }
                            },
                            enabled = results.isNotEmpty()
                        ) { Icon(Icons.Rounded.Shuffle, "Вперемешку") }
                        if (playlist != null) {
                            Box {
                                FilledTonalIconButton(onClick = { playlistMenu = true }) {
                                    Icon(Icons.Rounded.MoreHoriz, "Действия с плейлистом")
                                }
                                DropdownMenu(expanded = playlistMenu, onDismissRequest = { playlistMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Добавить текущий трек") },
                                        enabled = p.current?.isReal == true && playlist.tracks.none { it.id == p.current?.id },
                                        onClick = {
                                            p.current?.let { DesktopLibrary.addToPlaylist(playlist.id, it) }
                                            playlistMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Удалить плейлист") },
                                        onClick = {
                                            playlistMenu = false
                                            onDeletePlaylist()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LibrarySearchField(query, { query = it }, Modifier.widthIn(max = 340.dp).weight(1f, fill = compact))
                        if (!compact) Spacer(Modifier.weight(1f))
                        Box {
                            if (compact) {
                                FilledTonalIconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Tune, "Фильтры") }
                            } else {
                                FilledTonalButton(onClick = { sortMenu = true }) {
                                    Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Фильтры")
                                }
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                LibrarySort.entries.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(entry.text) },
                                        trailingIcon = if (entry == sort) {{ Icon(Icons.Rounded.Check, null) }} else null,
                                        onClick = {
                                            sort = entry
                                            sortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
                    ) {
                        items(pending, key = { "pending:${it.track.id}" }) { download ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                                Text(
                                    "Загружается: ${download.track.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                if (download.progress > 0f) {
                                    LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        if (results.isEmpty() && pending.isEmpty()) {
                            item {
                                val empty = when {
                                    query.isNotBlank() -> "Треки не найдены"
                                    section.id == favoritesId -> "Отметьте понравившийся трек сердечком."
                                    section.id == downloadsId -> "Скачивайте музыку из поиска, чтобы слушать её без интернета."
                                    section.id == localId -> "Откройте аудиофайл с компьютера."
                                    else -> "Добавьте треки через меню рядом с ними или через меню плейлиста."
                                }
                                Text(
                                    empty,
                                    color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        items(results, key = { it.id }) { track ->
                            Column(Modifier.animateItem()) {
                                LibraryTrackRow(
                                    track, current = p.current?.id == track.id,
                                    playing = p.current?.id == track.id && p.playing,
                                    onPlay = {
                                        if (p.current?.id == track.id && p.playing) p.toggle()
                                        else p.play(track, results)
                                    },
                                    removeLabel = if (section.id == downloadsId) "Удалить из загрузок" else "Убрать из списка",
                                    onRemove = { removeTrack(track) }
                                )
                                HorizontalDivider(Modifier.padding(start = 50.dp, end = 12.dp), color = colors.outlineVariant)
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        style = defaultScrollbarStyle().copy(
                            thickness = 4.dp,
                            unhoverColor = colors.onSurface.copy(alpha = 0.16f),
                            hoverColor = colors.onSurface.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp)
                    )
                }
            }
        }
    }
}

private fun trackCount(count: Int): String {
    val noun = when {
        count % 100 in 11..14 -> "треков"
        count % 10 == 1 -> "трек"
        count % 10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $noun"
}

@Composable
private fun LibrarySearchField(query: String, onEdit: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.height(36.dp).clip(CircleShape).background(colors.surfaceContainer).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = query,
            onValueChange = onEdit,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Поиск в библиотеке" },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.onSurface)
        ) { input ->
            Box {
                if (query.isEmpty()) {
                    Text(
                        "Название или исполнитель", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                    )
                }
                input()
            }
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onEdit("") }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.Close, "Очистить поиск", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun LibraryTrackRow(
    track: Track,
    current: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    removeLabel: String,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).libraryInteraction(current, onPlay).padding(start = 6.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Cover(track.color, size = 44.dp, corner = 8.dp, url = track.artwork)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (playing) {
            Icon(Icons.Rounded.GraphicEq, "Сейчас играет", modifier = Modifier.size(16.dp))
        }
        if (track.seconds > 0) {
            Text(fmt(track.seconds), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        LibraryTrackMenu(track, removeLabel, onRemove)
    }
}

@Composable
private fun Modifier.libraryInteraction(active: Boolean, onClick: () -> Unit): Modifier {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val color by animateColorAsState(
        when {
            active -> colors.surfaceContainerHigh
            hovered -> colors.surfaceContainer
            else -> Color.Transparent
        },
        tween(150), label = "library hover"
    )
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(130), label = "library press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(10.dp))
        .background(color)
        .semantics { selected = active }
        .hoverable(source)
        .clickable(
            interactionSource = source, indication = LocalIndication.current,
            role = Role.Button, onClick = onClick
        )
}

@Composable
internal fun LibraryTrackMenu(track: Track, removeLabel: String? = null, onRemove: () -> Unit = {}) {
    if (!track.isReal && removeLabel == null) return
    val library by DesktopLibrary.data
    var menu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreHoriz, "Действия с треком", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (track.isReal) {
                library.playlists.forEach { playlist ->
                    val added = playlist.tracks.any { it.id == track.id }
                    DropdownMenuItem(
                        text = { Text("В плейлист «${playlist.name}»", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        enabled = !added,
                        onClick = {
                            DesktopLibrary.addToPlaylist(playlist.id, track)
                            menu = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("В новый плейлист") },
                    onClick = {
                        menu = false
                        create = true
                    }
                )
            }
            if (removeLabel != null) {
                DropdownMenuItem(
                    text = { Text(removeLabel) },
                    onClick = {
                        menu = false
                        onRemove()
                    }
                )
            }
        }
    }
    if (create) CreateLibraryPlaylist(first = track, close = { create = false })
}

@Composable
private fun CreateLibraryPlaylist(
    first: Track? = null,
    onCreated: (DesktopPlaylist) -> Unit = {},
    close: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Новый плейлист") },
        text = {
            OutlinedTextField(
                name, { name = it.take(80) }, singleLine = true,
                label = { Text("Название") }, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    DesktopLibrary.createPlaylist(name, first)?.let {
                        onCreated(it)
                        close()
                    }
                },
                enabled = name.isNotBlank()
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Отмена") } }
    )
}
