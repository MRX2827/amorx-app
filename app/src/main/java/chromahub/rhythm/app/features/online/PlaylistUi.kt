package chromahub.rhythm.app.features.online

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object PlaylistNav {
    val open = mutableStateOf(false)
}

private object PlaylistActions {
    fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    fun startDownloads(ctx: Context) {
        val err = PlaylistDownloader.start(ctx)
        toast(ctx, err ?: "Скачивание плейлиста началось")
    }

    fun copyLink(ctx: Context, scope: CoroutineScope, busy: (Boolean) -> Unit) {
        scope.launch {
            busy(true)
            CloudPlaylists.share(PlaylistStore.tracks.value)
                .onSuccess { code ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("AmorX playlist", CloudPlaylists.linkFor(code)))
                    toast(ctx, "Ссылка скопирована. Отправьте её другу")
                }
                .onFailure { toast(ctx, it.message ?: "Не удалось поделиться") }
            busy(false)
        }
    }

    fun pasteLink(ctx: Context, scope: CoroutineScope, busy: (Boolean) -> Unit) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        val code = CloudPlaylists.parseCode(text)
        if (code == null) {
            toast(ctx, "В буфере нет ссылки на плейлист AmorX")
            return
        }
        scope.launch {
            busy(true)
            CloudPlaylists.fetch(code)
                .onSuccess { list ->
                    PlaylistStore.replace(list)
                    toast(ctx, "Импортировано песен: " + list.size)
                    startDownloads(ctx)
                }
                .onFailure { toast(ctx, it.message ?: "Не удалось импортировать") }
            busy(false)
        }
    }

    fun addPlaying(ctx: Context, vm: MusicViewModel) {
        val cur = vm.currentSong.value
        if (cur == null) { toast(ctx, "Сейчас ничего не играет"); return }
        val vid = ArtworkUtil.videoId(cur.id)
        if (vid == null) { toast(ctx, "Локальные файлы с телефона добавить нельзя"); return }
        val n = PlaylistStore.add(
            listOf(PlTrack(vid, cur.title, cur.artist, cur.duration, ArtworkUtil.forSong(cur.id, cur.artworkUri?.toString())))
        )
        toast(ctx, if (n > 0) "Добавлено в плейлист" else "Эта песня уже в плейлисте")
    }

    fun addDownloaded(ctx: Context) {
        val n = PlaylistStore.add(OnlineDownloads.items.value.map {
            PlTrack(it.id, it.title, it.artist, it.durationMs, ArtworkUtil.forSong("online_" + it.id, null))
        })
        toast(ctx, "Добавлено песен: " + n)
    }

    fun playAll(ctx: Context, vm: MusicViewModel, shuffle: Boolean) {
        val have = OnlineDownloads.items.value
        val songs = PlaylistStore.tracks.value.mapNotNull { t -> have.firstOrNull { it.id == t.id }?.toSong() }
        if (songs.isEmpty()) toast(ctx, "Пока нет скачанных песен из плейлиста")
        else vm.playQueue(songs, enableShuffle = shuffle, startIndex = 0)
    }

    fun playOne(ctx: Context, vm: MusicViewModel, scope: CoroutineScope, t: PlTrack, loading: (String?) -> Unit) {
        val have = OnlineDownloads.items.value
        if (have.any { it.id == t.id }) {
            val songs = PlaylistStore.tracks.value.mapNotNull { x -> have.firstOrNull { it.id == x.id }?.toSong() }
            val idx = songs.indexOfFirst { it.id == "offline_" + t.id }
            vm.playQueue(songs, enableShuffle = false, startIndex = idx.coerceAtLeast(0))
            return
        }
        scope.launch {
            loading(t.id)
            val r = runCatching { OnlineRepository.resolveAudioUrl(t.toOnline().videoUrl) }
            loading(null)
            r.onSuccess { url -> vm.playSong(t.toOnline().toSong(url)) }
                .onFailure { toast(ctx, "Не удалось получить трек: " + it.message) }
        }
    }
}

@Composable
private fun PlaylistMenu(vm: MusicViewModel, showOpen: Boolean, busy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }) { Text("⋮") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (showOpen) {
                DropdownMenuItem(text = { Text("Открыть список") }, onClick = { menu = false; PlaylistNav.open.value = true })
            }
            DropdownMenuItem(text = { Text("Скопировать ссылку") }, onClick = { menu = false; PlaylistActions.copyLink(ctx, scope, busy) })
            DropdownMenuItem(text = { Text("Вставить ссылку") }, onClick = { menu = false; PlaylistActions.pasteLink(ctx, scope, busy) })
            DropdownMenuItem(text = { Text("Добавить играющую песню") }, onClick = { menu = false; PlaylistActions.addPlaying(ctx, vm) })
            DropdownMenuItem(text = { Text("Добавить все скачанные") }, onClick = { menu = false; PlaylistActions.addDownloaded(ctx) })
            DropdownMenuItem(text = { Text("Очистить плейлист") }, onClick = { menu = false; PlaylistStore.clear() })
        }
    }
}

/** Карточка во вкладке «Онлайн». Сам список открывается экраном, как у плеера. */
@Composable
fun PlaylistCard(musicViewModel: MusicViewModel) {
    val context = LocalContext.current
    remember { PlaylistStore.init(context); OnlineDownloads.init(context); CloudProfiles.init(context); true }
    val tracks by PlaylistStore.tracks.collectAsState()
    val downloaded by OnlineDownloads.items.collectAsState()
    val running by PlaylistDownloader.running.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val haveIds = remember(downloaded) { downloaded.map { it.id }.toHashSet() }
    val have = tracks.count { it.id in haveIds }

    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 0.98f else 1f, tween(100), label = "plCardPress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(RoundedCornerShape(24.dp))
            .clickable(interactionSource = src, indication = LocalIndication.current) { PlaylistNav.open.value = true },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverMosaic(tracks.take(4).map { it.art }, 64.dp)
                Column(Modifier.weight(1f)) {
                    Text("Плейлист AmorX", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (tracks.isEmpty()) "Пусто. Добавьте песни из плеера или вставьте ссылку друга"
                        else if (have >= tracks.size) "Песен: " + tracks.size + " · все скачаны"
                        else "Песен: " + tracks.size + " · скачано: " + have,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (running || busy) {
                Spacer(Modifier.size(8.dp))
                if (tracks.isNotEmpty()) LinearProgressIndicator(progress = { have.toFloat() / tracks.size }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { PlaylistNav.open.value = true }, contentPadding = TileBtnPad) { Text("Открыть") }
                if (have < tracks.size) {
                    FilledTonalButton(onClick = { PlaylistActions.startDownloads(context) }, enabled = !running, contentPadding = TileBtnPad) {
                        Text(if (running) "Качаю…" else "Скачать всё")
                    }
                }
                PlaylistMenu(musicViewModel, showOpen = false) { busy = it }
            }
        }
    }
}

/** Хост экрана плейлиста: живёт на уровне навигации, выезжает как плеер. */
@Composable
fun PlaylistSheetHost(vm: MusicViewModel, route: String?) {
    val open by PlaylistNav.open
    val onPlayer = route == "player"
    LaunchedEffect(route) {
        if (route != null && route != "online" && route != "player") PlaylistNav.open.value = false
    }
    BackHandler(enabled = open && !onPlayer) { PlaylistNav.open.value = false }
    PlayerLikeSheet(visible = open && !onPlayer) {
        PlaylistScreen(vm) { PlaylistNav.open.value = false }
    }
}

@Composable
private fun PlAnimateIn(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val a by animateFloatAsState(
        if (visible) 1f else 0f, tween(300, delayMillis = 50 + minOf(index, 10) * 30), label = "plAlpha"
    )
    val s by animateFloatAsState(
        if (visible) 1f else 0.95f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "plScale"
    )
    Box(Modifier.graphicsLayer { alpha = a; scaleX = s; scaleY = s }) { content() }
}

@Composable
private fun PlaylistScreen(vm: MusicViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by PlaylistStore.tracks.collectAsState()
    val downloaded by OnlineDownloads.items.collectAsState()
    val pending by OnlineDownloads.pending.collectAsState()
    val running by PlaylistDownloader.running.collectAsState()
    val cur by vm.currentSong.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    val haveIds = remember(downloaded) { downloaded.map { it.id }.toHashSet() }
    val have = tracks.count { it.id in haveIds }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("‹ Назад") }
        }
        item {
            Text("Плейлист AmorX", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                "Песен: " + tracks.size + " · скачано: " + have,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (running || busy) {
                Spacer(Modifier.size(12.dp))
                if (tracks.isNotEmpty()) LinearProgressIndicator(progress = { have.toFloat() / tracks.size }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { PlaylistActions.playAll(context, vm, false) }, enabled = have > 0) { Text("Играть") }
                FilledTonalButton(onClick = { PlaylistActions.playAll(context, vm, true) }, enabled = have > 0) { Text("Вперемешку") }
                if (have < tracks.size) {
                    FilledTonalButton(onClick = { PlaylistActions.startDownloads(context) }, enabled = !running) {
                        Text(if (running) "Качаю…" else "Скачать")
                    }
                }
                PlaylistMenu(vm, showOpen = false) { busy = it }
            }
            Spacer(Modifier.size(12.dp))
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    "Плейлист пуст. В плеере откройте «три точки» и нажмите «Добавить в AmorX плейлист» или вставьте ссылку друга через «⋮».",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
            PlAnimateIn(index) {
                val src = remember { MutableInteractionSource() }
                val pressed by src.collectIsPressedAsState()
                val press by animateFloatAsState(if (pressed) 0.98f else 1f, tween(100), label = "plRowPress")
                val isCur = cur?.id == "offline_" + t.id || cur?.id == "online_" + t.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { scaleX = press; scaleY = press }
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isCur) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(interactionSource = src, indication = LocalIndication.current) {
                            if (loadingId == null) PlaylistActions.playOne(context, vm, scope, t) { loadingId = it }
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ArtworkUtil.forSong("online_" + t.id, t.art),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            t.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        val prog = pending[t.id]?.progress
                        when {
                            loadingId == t.id -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            t.id in haveIds -> Icon(
                                OnlineIcons.Done, "Скачано",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)
                            )
                            prog != null -> {
                                if (prog > 0f) CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                            else -> IconButton(onClick = { OnlineDownloads.download(t.toOnline()) }) {
                                Icon(OnlineIcons.Download, "Скачать", modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    IconButton(onClick = { PlaylistStore.remove(t.id) }) {
                        Icon(OnlineIcons.Close, "Убрать", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}


internal val TileBtnPad = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

internal fun clipboardText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}

@Composable
internal fun CoverMosaic(arts: List<String?>, size: Dp) {
    val bg = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.surface
    )
    Column(
        modifier = Modifier.size(size).clip(RoundedCornerShape(14.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        for (r in 0..1) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (c in 0..1) {
                    val i = r * 2 + c
                    val a = arts.getOrNull(i)
                    Box(Modifier.weight(1f).fillMaxHeight().background(bg[i])) {
                        if (!a.isNullOrBlank()) {
                            AsyncImage(
                                model = a,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImportTile(
    modifier: Modifier,
    title: String,
    subtitle: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    progress: Float?,
    actions: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = 32.dp)
            )
            if (progress != null) {
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            actions()
        }
    }
}
