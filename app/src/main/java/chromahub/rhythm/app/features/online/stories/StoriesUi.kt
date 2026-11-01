package chromahub.rhythm.app.features.online.stories

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.features.online.ArtworkUtil
import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.CloudSong
import chromahub.rhythm.app.features.online.PlaylistStore
import chromahub.rhythm.app.features.online.ProfileSongs
import chromahub.rhythm.app.features.online.ProfileStore
import chromahub.rhythm.app.shared.presentation.components.common.SplashBackgroundOrbs
import chromahub.rhythm.app.shared.presentation.components.common.buildSplashBackdropShapes
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val SONG_STORY_MS = 7000f

private fun timeAgo(ms: Long): String {
    val m = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1 -> "только что"
        m < 60 -> "$m мин назад"
        else -> "${m / 60} ч назад"
    }
}

// ───────────────────────── ряд историй на «Главной» ─────────────────────────

@Composable
fun StoriesRow(musicViewModel: MusicViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember {
        CloudStories.init(context)
        ProfileStore.init(context)
        ProfileSongs.init(context)
        PlaylistStore.init(context)
        true
    }
    val feed by CloudStories.feed.collectAsState()
    val seen by CloudStories.seen.collectAsState()
    val avatarPath by ProfileStore.avatarPath.collectAsState()
    val myName by ProfileStore.name.collectAsState()

    var snapshot by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var composer by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { CloudStories.refresh() }

    val mine = feed.firstOrNull { it.isMine }
    val others = feed.filter { !it.isMine }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "story_mine") {
            StoryBubble(
                name = "Моя история",
                b64 = null,
                filePath = avatarPath,
                label = myName,
                unseen = mine != null && mine.stories.any { it.id !in seen },
                hasStories = mine != null,
                showPlus = true,
                onClick = {
                    if (mine != null) {
                        snapshot = feed
                        viewerIndex = feed.indexOf(mine)
                    } else composer = true
                },
                onPlus = { composer = true }
            )
        }
        items(others, key = { "story_" + it.ownerId }) { g ->
            StoryBubble(
                name = g.name,
                b64 = g.avatarB64,
                filePath = null,
                label = g.name,
                unseen = g.stories.any { it.id !in seen },
                hasStories = true,
                showPlus = false,
                onClick = {
                    snapshot = feed
                    viewerIndex = feed.indexOf(g)
                },
                onPlus = {}
            )
        }
    }

    val vi = viewerIndex
    if (vi != null && snapshot.isNotEmpty() && vi in snapshot.indices) {
        StoryViewer(
            groups = snapshot,
            startGroup = vi,
            musicViewModel = musicViewModel,
            onClose = { viewerIndex = null }
        )
    }
    if (composer) {
        StoryComposer(musicViewModel = musicViewModel, onDismiss = { composer = false })
    }
}

@Composable
private fun StoryBubble(
    name: String,
    b64: String?,
    filePath: String?,
    label: String,
    unseen: Boolean,
    hasStories: Boolean,
    showPlus: Boolean,
    onClick: () -> Unit,
    onPlus: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // пружинное нажатие, как у обложки в плеере
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "storyBubbleScale"
    )
    val ring: Brush = when {
        hasStories && unseen -> Brush.sweepGradient(listOf(cs.primary, cs.tertiary, cs.secondary, cs.primary))
        else -> SolidColor(cs.outlineVariant)
    }
    Column(
        modifier = Modifier.width(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(72.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .border(if (hasStories && unseen) 3.dp else 2.dp, ring, CircleShape)
                    .padding(5.dp)
                    .clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                StoryAvatar(b64, filePath, label, 62.dp)
            }
            if (showPlus) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(cs.primary)
                        .border(2.dp, cs.surface, CircleShape)
                        .clickable(onClick = onPlus),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = cs.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun StoryAvatar(b64: String?, filePath: String?, label: String, size: Dp) {
    val bitmap = remember(b64) {
        b64?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            filePath != null -> AsyncImage(
                model = File(filePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            else -> Text(
                text = label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ───────────────────────── просмотр, как в Instagram ─────────────────────────

@OptIn(UnstableApi::class)
@Composable
private fun StoryViewer(
    groups: List<StoryGroup>,
    startGroup: Int,
    musicViewModel: MusicViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarPath by ProfileStore.avatarPath.collectAsState()

    var gi by remember { mutableIntStateOf(startGroup.coerceIn(0, groups.lastIndex)) }
    var si by remember {
        mutableIntStateOf(
            groups[startGroup.coerceIn(0, groups.lastIndex)].stories
                .indexOfFirst { it.id !in CloudStories.seen.value }
                .let { if (it < 0) 0 else it }
        )
    }
    val group = groups[gi]
    val story = group.stories[si.coerceIn(0, group.stories.lastIndex)]

    var progress by remember { mutableFloatStateOf(0f) }
    var pressPaused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }

    fun goNext() {
        if (si < group.stories.lastIndex) si++
        else if (gi < groups.lastIndex) { gi++; si = 0 }
        else onClose()
    }
    fun goPrev() {
        if (si > 0) si--
        else if (gi > 0) { gi--; si = groups[gi].stories.lastIndex }
        else progress = 0f
    }
    val onNext by rememberUpdatedState({ goNext() })
    val onPrev by rememberUpdatedState({ goPrev() })
    val pausedNow by rememberUpdatedState(pressPaused || menuOpen)

    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(story.id) {
        CloudStories.markSeen(story.id)
        progress = 0f
        val url = story.videoUrl
        if (url != null) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = !pausedNow
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }
    LaunchedEffect(pressPaused, menuOpen) {
        if (story.isVideo) player.playWhenReady = !(pressPaused || menuOpen)
    }
    // один «счётчик» для видео и песен: видео идёт по позиции плеера, песня по таймеру
    LaunchedEffect(story.id) {
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(16)
            val now = SystemClock.uptimeMillis()
            val dt = now - last
            last = now
            if (pausedNow) continue
            if (story.isVideo) {
                if (player.playerError != null) { onNext(); return@LaunchedEffect }
                val d = player.duration
                if (player.playbackState == Player.STATE_ENDED) progress = 1f
                else if (d > 0 && d != C.TIME_UNSET) progress = (player.currentPosition.toFloat() / d).coerceIn(0f, 1f)
            } else {
                progress = (progress + dt / SONG_STORY_MS).coerceAtMost(1f)
            }
            if (progress >= 1f) { onNext(); return@LaunchedEffect }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragY
                    val s = 1f - (dragY / 3000f).coerceIn(0f, 0.15f)
                    scaleX = s
                    scaleY = s
                    alpha = 1f - (dragY / 1200f).coerceIn(0f, 0.6f)
                }
                .background(Color.Black)
        ) {
            // 1. картинка: видео или фон истории-песни
            if (story.isVideo) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (story.caption.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 28.dp)
                    ) {
                        Text(story.caption, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                SongBackdrop(story)
            }

            // 2. тапы: назад, вперёд, удержание = пауза, свайп вниз = закрыть
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                pressPaused = true
                                tryAwaitRelease()
                                pressPaused = false
                            },
                            onTap = { offset -> if (offset.x < size.width * 0.3f) onPrev() else onNext() }
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dragY > 220f) onClose() else dragY = 0f },
                            onDragCancel = { dragY = 0f },
                            onVerticalDrag = { _, dy -> dragY = (dragY + dy).coerceAtLeast(0f) }
                        )
                    }
            )

            // 3. песня поверх тапов, чтобы кнопка «Слушать» нажималась
            if (!story.isVideo) SongStoryForeground(story, musicViewModel)

            // 4. верхняя панель: полоски, автор, меню
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    group.stories.forEachIndexed { i, _ ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        scaleX = when {
                                            i < si -> 1f
                                            i == si -> progress.coerceIn(0f, 1f)
                                            else -> 0f
                                        }
                                    }
                                    .background(Color.White)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StoryAvatar(group.avatarB64, if (group.isMine) avatarPath else null, group.name, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (group.isMine) "Вы" else group.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(timeAgo(story.createdAtMs), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                    }
                    Box {
                        Text(
                            "⋮",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (group.isMine) {
                                DropdownMenuItem(
                                    text = { Text("Удалить историю") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            CloudStories.delete(story)
                                                .onSuccess {
                                                    Toast.makeText(context, "История удалена", Toast.LENGTH_SHORT).show()
                                                    onClose()
                                                }
                                                .onFailure {
                                                    Toast.makeText(context, it.message ?: "Не удалось удалить", Toast.LENGTH_SHORT).show()
                                                }
                                        }
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Пожаловаться") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            CloudStories.report(story)
                                                .onSuccess {
                                                    Toast.makeText(context, "Спасибо, жалоба отправлена", Toast.LENGTH_SHORT).show()
                                                }
                                                .onFailure {
                                                    Toast.makeText(context, it.message ?: "Не удалось отправить", Toast.LENGTH_SHORT).show()
                                                }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "✕",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ───────────────────────── история-песня: дизайн и анимации из плеера ─────────────────────────

@Composable
private fun SongBackdrop(story: Story) {
    val cs = MaterialTheme.colorScheme
    val cfg = LocalConfiguration.current
    val cover = story.song?.let { ArtworkUtil.forSong(it.id, it.artwork) }
    val shapes = remember(story.id, cfg.screenWidthDp, cfg.screenHeightDp) {
        buildSplashBackdropShapes(
            seed = story.id.hashCode(),
            shapeIds = listOf("COOKIE_9", "FLOWER", "CLOVER_4_LEAF", "SOFT_BURST", "PUFFY"),
            preset = "PLAYFUL",
            screenWidthDp = cfg.screenWidthDp,
            screenHeightDp = cfg.screenHeightDp,
            primaryColor = cs.primary,
            secondaryColor = cs.secondary,
            tertiaryColor = cs.tertiary,
            neutralColor = cs.surfaceVariant,
            sizeScale = 1.6f
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.85f), cs.tertiary.copy(alpha = 0.75f))))
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.55f)
            )
        }
        SplashBackgroundOrbs(shapes)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
        )
    }
}

@Composable
private fun SongStoryForeground(story: Story, musicViewModel: MusicViewModel) {
    val song = story.song ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var loading by remember(story.id) { mutableStateOf(false) }

    // обложка выезжает пружиной с отскоком, как в плеере, и потом «дышит»
    val enter = remember(story.id) { Animatable(0f) }
    LaunchedEffect(story.id) {
        enter.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        )
    }
    val breathe by rememberInfiniteTransition(label = "storyBreathe").animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOut), RepeatMode.Reverse),
        label = "storyBreatheValue"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp)
            .padding(top = 56.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val cover = ArtworkUtil.forSong(song.id, song.artwork)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f)
                .graphicsLayer {
                    val s = (0.55f + 0.45f * enter.value) * breathe
                    scaleX = s
                    scaleY = s
                    alpha = enter.value.coerceIn(0f, 1f)
                    rotationZ = (1f - enter.value) * -6f
                }
                .shadow(24.dp, RoundedCornerShape(36.dp))
                .clip(RoundedCornerShape(36.dp))
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("♪", style = MaterialTheme.typography.displayLarge, color = cs.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            song.title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            song.artist,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (story.caption.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                story.caption,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (loading) return@Button
                scope.launch {
                    loading = true
                    val resolved = runCatching { CloudProfiles.resolveSong(song) }.getOrNull()
                    loading = false
                    if (resolved != null) {
                        musicViewModel.playSong(resolved)
                        Toast.makeText(context, "Играет: " + song.title, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Не удалось найти песню", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            shape = RoundedCornerShape(28.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
            } else {
                Text("▶  Слушать")
            }
        }
    }
}

// ───────────────────────── создание истории ─────────────────────────

private data class SongChoice(val song: CloudSong, val source: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryComposer(musicViewModel: MusicViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPublic by ProfileStore.isPublic.collectAsState()
    val favs by ProfileSongs.songs.collectAsState()
    val plTracks by PlaylistStore.tracks.collectAsState()
    val playlists by musicViewModel.playlists.collectAsState()

    var step by remember { mutableStateOf("menu") } // menu | song | video
    var preparing by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var preparedFile by remember { mutableStateOf<File?>(null) }
    var pickedSong by remember { mutableStateOf<CloudSong?>(null) }
    var caption by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            preparing = true
            scope.launch {
                StoryCompressor.compress(context, uri)
                    .onSuccess { preparedFile = it; step = "video" }
                    .onFailure { Toast.makeText(context, it.message ?: "Не удалось подготовить видео", Toast.LENGTH_LONG).show() }
                preparing = false
            }
        }
    }

    val choices = remember(favs, plTracks, playlists) {
        val out = ArrayList<SongChoice>()
        favs.forEach { out.add(SongChoice(it, "Любимые песни")) }
        plTracks.forEach { out.add(SongChoice(CloudSong(it.id, it.title, it.artist, it.art, it.durMs), "Мой плейлист")) }
        playlists.forEach { pl ->
            pl.songs.forEach { s ->
                out.add(
                    SongChoice(
                        CloudSong(
                            ProfileSongs.normId(s.id),
                            s.title,
                            s.artist,
                            ArtworkUtil.forSong(s.id, s.artworkUri?.toString()),
                            s.duration
                        ),
                        "Плейлист «" + pl.name + "»"
                    )
                )
            }
        }
        out.distinctBy { it.song.id }
    }
    val shown = remember(choices, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) choices else choices.filter {
            it.song.title.lowercase().contains(q) || it.song.artist.lowercase().contains(q)
        }).take(200)
    }

    fun close() {
        if (posting) return
        preparedFile?.delete()
        onDismiss()
    }

    fun publish() {
        if (posting) return
        scope.launch {
            posting = true
            val file = preparedFile
            val song = pickedSong
            val result = when {
                step == "video" && file != null -> CloudStories.postVideo(file, caption)
                song != null -> CloudStories.postSong(song, caption)
                else -> Result.failure(IllegalStateException("Нечего публиковать"))
            }
            posting = false
            result
                .onSuccess {
                    file?.delete()
                    preparedFile = null
                    Toast.makeText(context, "История опубликована на 24 часа", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                .onFailure { Toast.makeText(context, it.message ?: "Не удалось опубликовать", Toast.LENGTH_LONG).show() }
        }
    }

    ModalBottomSheet(onDismissRequest = { close() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Text("Новая история", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Живёт 24 часа. Её увидят те, у кого профиль публичный.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            when {
                !isPublic -> {
                    Text(
                        "Чтобы выкладывать истории, откройте Профиль и включите «Публичный профиль».",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { close() }) { Text("Понятно") }
                }
                preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Готовим видео…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                step == "menu" -> {
                    FilledTonalButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("🎬  Видео из галереи (до 15 секунд)") }
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = { step = "song" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("🎵  Песня из плейлиста") }
                }
                step == "song" && pickedSong == null -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Найти песню") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    if (shown.isEmpty()) {
                        Text(
                            "Песен пока нет. Добавьте их в плейлист или в любимые.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.song.id + "|" + it.source }) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { pickedSong = c.song }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = ArtworkUtil.forSong(c.song.id, c.song.artwork),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        c.song.artist + " · " + c.source,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    // готово к публикации: видео или выбранная песня
                    val file = preparedFile
                    val song = pickedSong
                    if (step == "video" && file != null) {
                        Text(
                            "Видео готово: " + String.format("%.1f", file.length() / 1_048_576f) + " МБ",
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else if (song != null) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Подпись (необязательно)") },
                        maxLines = 2,
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { publish() }, enabled = !posting, shape = RoundedCornerShape(24.dp)) {
                            if (posting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Text("Опубликовать")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { close() }, enabled = !posting) { Text("Отмена") }
                    }
                }
            }
        }
    }
}
