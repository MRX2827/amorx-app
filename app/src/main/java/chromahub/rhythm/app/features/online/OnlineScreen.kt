package chromahub.rhythm.app.features.online

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnlineScreen(
    musicViewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { WaveStore.init(context); OnlineDownloads.init(context); true }

    val query by OnlineSearchState.query.collectAsState()
    val results by OnlineSearchState.results.collectAsState()
    val loading by OnlineSearchState.loading.collectAsState()
    val error by OnlineSearchState.error.collectAsState()
    val artists by WaveStore.artists.collectAsState()
    val pending by OnlineDownloads.pending.collectAsState()
    val downloaded by OnlineDownloads.items.collectAsState()

    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var resolvingUrl by remember { mutableStateOf<String?>(null) }
    var waveLoading by remember { mutableStateOf(false) }
    var showArtists by remember { mutableStateOf(false) }
    val recs by WaveRecs.tracks.collectAsState()
    val recsLoading by WaveRecs.loading.collectAsState()
    val recsLoadingMore by WaveRecs.loadingMore.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        OnlineDownloads.messages.collect {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    if (showArtists) WaveArtistPicker(onDismiss = { showArtists = false })

    LaunchedEffect(artists) {
        delay(800)
        WaveRecs.refresh(artists)
    }

    LaunchedEffect(listState, recs.size) {
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

    fun playTrack(track: OnlineTrack) {
        if (resolvingUrl != null) return
        scope.launch {
            resolvingUrl = track.videoUrl
            val result = runCatching { OnlineRepository.resolveAudioUrl(track.videoUrl) }
            resolvingUrl = null
            result.onSuccess { url ->
                musicViewModel.playSong(track.toSong(url))
            }.onFailure {
                Toast.makeText(
                    context,
                    "Не удалось получить трек: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun doSearch() {
        focusManager.clearFocus()
        OnlineSearchState.search(query)
    }

    fun startWave() {
        if (artists.isEmpty()) {
            showArtists = true
            return
        }
        if (waveLoading) return
        scope.launch {
            waveLoading = true
            val songs = runCatching { OnlineWave.buildSongs(artists) }.getOrDefault(emptyList())
            waveLoading = false
            if (songs.isEmpty()) {
                Toast.makeText(
                    context,
                    "Не удалось собрать волну. Проверьте интернет и попробуйте ещё раз",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                OnlineWave.begin(musicViewModel, songs)
            }
        }
    }

    val ypOpen by YandexImport.playlistOpen.collectAsState()
    if (ypOpen) {
        YandexPlaylistScreen(musicViewModel = musicViewModel, modifier = modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        item {
            Text(
                text = "Онлайн",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { OnlineSearchState.query.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Найти песню или исполнителя") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        TextButton(onClick = { doSearch() }) { Text("Найти") }
                    }
                }
            )
        }

        item { Spacer(Modifier.size(8.dp)) }

        val err = error
        if (loading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        } else if (err != null && results.isEmpty()) {
            item {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            items(results, key = { it.videoUrl }) { track ->
                val id = track.id
                OnlineTrackRow(
                    track = track,
                    resolving = resolvingUrl == track.videoUrl,
                    isDownloaded = downloaded.any { it.id == id },
                    downloadProgress = pending[id]?.progress,
                    onDownload = {
                        OnlineDownloads.download(track)
                        Toast.makeText(context, "Скачивание началось", Toast.LENGTH_SHORT).show()
                    },
                    onClick = {
                        if (resolvingUrl != null) return@OnlineTrackRow
                        scope.launch {
                            resolvingUrl = track.videoUrl
                            val result = runCatching {
                                OnlineRepository.resolveAudioUrl(track.videoUrl)
                            }
                            resolvingUrl = null
                            result.onSuccess { url ->
                                musicViewModel.playSong(track.toSong(url))
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Не удалось получить трек: ${it.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.size(8.dp)) }

        item { YandexImportCard(musicViewModel) }

        item {
            WaveCard(
                artists = artists,
                loading = waveLoading,
                onStart = { startWave() },
                onPickArtists = { showArtists = true }
            )
        }

        if (artists.isNotEmpty() && (recs.isNotEmpty() || recsLoading)) {
            item {
                Text(
                    text = "Рекомендации",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (recsLoading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                }
            }
            itemsIndexed(recs, key = { i, t -> "rec_" + i + "_" + t.videoUrl }) { _, track ->
                val id = track.id
                OnlineTrackRow(
                    track = track,
                    resolving = resolvingUrl == track.videoUrl,
                    isDownloaded = downloaded.any { it.id == id },
                    downloadProgress = pending[id]?.progress,
                    onDownload = {
                        OnlineDownloads.download(track)
                        Toast.makeText(context, "Скачивание началось", Toast.LENGTH_SHORT).show()
                    },
                    onClick = { playTrack(track) }
                )
            }
            if (recsLoadingMore) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun WaveCard(
    artists: List<WaveArtist>,
    loading: Boolean,
    onStart: () -> Unit,
    onPickArtists: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Моя волна", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                text = if (artists.isEmpty()) "Выберите любимых артистов, и волна подберёт музыку"
                else artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(16.dp))
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart, enabled = !loading) {
                    Text(if (loading) "Готовлю…" else "Запустить")
                }
                FilledTonalButton(onClick = onPickArtists) {
                    Text(if (artists.isEmpty()) "Выбрать артистов" else "Артисты (${artists.size})")
                }
            }
        }
    }
}

@Composable
private fun WaveArtistsDialog(onDismiss: () -> Unit) {
    val selected by WaveStore.artists.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WaveArtist>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (query.isBlank()) return
        scope.launch {
            searching = true
            message = null
            results = runCatching { OnlineRepository.searchArtists(query.trim()) }
                .getOrElse {
                    message = "Ошибка: ${it.message}"
                    emptyList()
                }
            if (results.isEmpty() && message == null) message = "Артисты не найдены"
            searching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Артисты для волны") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Найти артиста") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                TextButton(onClick = { search() }) { Text("Найти") }
                            }
                        }
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
                    items(selected, key = { "s_" + it.name }) { artist ->
                        ArtistRow(artist = artist, added = true) { WaveStore.remove(artist.name) }
                    }
                }
                if (searching) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
                    }
                }
                val msg = message
                if (msg != null) {
                    item { Text(msg, modifier = Modifier.padding(vertical = 12.dp)) }
                }
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "Результаты",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(results, key = { "r_" + it.name }) { artist ->
                        val added = selected.any { it.name.equals(artist.name, ignoreCase = true) }
                        ArtistRow(artist = artist, added = added) {
                            if (added) WaveStore.remove(artist.name) else WaveStore.add(artist)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun ArtistRow(artist: WaveArtist, added: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = artist.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (added) OnlineIcons.Close else OnlineIcons.Add,
                contentDescription = if (added) "Убрать" else "Добавить",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
internal fun OnlineTrackRow(
    track: OnlineTrack,
    resolving: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onDownload: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(track.artist, formatDuration(track.durationMs))
                    .filter { it.isNotBlank() }
                    .joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (resolving) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                isDownloaded -> Icon(
                    imageVector = OnlineIcons.Done,
                    contentDescription = "Скачано",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                downloadProgress != null -> {
                    if (downloadProgress > 0f) {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(
                        imageVector = OnlineIcons.Download,
                        contentDescription = "Скачать",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
