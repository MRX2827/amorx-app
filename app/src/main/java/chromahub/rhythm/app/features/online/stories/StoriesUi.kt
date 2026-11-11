package chromahub.rhythm.app.features.online.stories

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.features.online.ArtworkUtil
import chromahub.rhythm.app.features.online.CloudSong
import chromahub.rhythm.app.features.online.PlaylistStore
import chromahub.rhythm.app.features.online.ProfileSongs
import chromahub.rhythm.app.features.online.ProfileStore
import chromahub.rhythm.app.shared.presentation.components.common.SplashBackgroundOrbs
import chromahub.rhythm.app.shared.presentation.components.common.buildSplashBackdropShapes
import chromahub.rhythm.app.shared.data.model.Song
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val SONG_STORY_MS = 7000f

internal fun timeAgo(ms: Long): String {
    val m = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1 -> "только что"
        m < 60 -> "$m мин назад"
        else -> "${m / 60} ч назад"
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Пружинное нажатие, как у обложки в плеере. */
@Composable
private fun rememberPressScale(source: MutableInteractionSource, pressed: Float = 0.92f): State<Float> {
    val isPressed by source.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "pressScale"
    )
}

// ───────────────────────── ряд историй на «Главной» ─────────────────────────

@OptIn(UnstableApi::class)
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
    val friends by CloudFriends.state.collectAsState()
    val avatarPath by ProfileStore.avatarPath.collectAsState()
    val myName by ProfileStore.name.collectAsState()

    var snapshot by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var composer by remember { mutableStateOf(false) }
    var friendsOpen by remember { mutableStateOf(false) }

    // пока приложение открыто, лента обновляется сама каждые 3 секунды, чтобы новые истории
    // появлялись у людей сразу; в фоне опрос останавливается
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            CloudFriends.refresh()
            while (true) {
                CloudStories.refresh()
                delay(3_000L)
            }
        }
    }
    val audioPreloader = remember(context) { StoryAudioPreloader(context.applicationContext) }
    DisposableEffect(audioPreloader) { onDispose { audioPreloader.release() } }
    val firstSongs = remember(feed, seen) {
        feed.mapNotNull { group ->
            val songs = group.stories.filter { !it.isVideo && it.song != null }
            songs.firstOrNull { it.id !in seen } ?: songs.firstOrNull()
        }.take(6)
    }
    LaunchedEffect(audioPreloader, lifecycleOwner, firstSongs, viewerIndex, composer) {
        if (viewerIndex == null && !composer) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                audioPreloader.preload(firstSongs)
            }
        }
    }

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
                index = 0,
                onClick = {
                    if (mine != null) {
                        snapshot = feed
                        viewerIndex = feed.indexOf(mine)
                    } else composer = true
                },
                onPlus = { composer = true }
            )
        }
        item(key = "story_friends") {
            FriendsBubble(count = friends.incoming.size, index = 1, onClick = { friendsOpen = true })
        }
        itemsIndexed(others, key = { _, g -> "story_" + g.ownerId }) { i, g ->
            Box(modifier = Modifier.animateItemCompat()) {
                StoryBubble(
                    name = g.name,
                    b64 = g.avatarB64,
                    filePath = null,
                    label = g.name,
                    unseen = g.stories.any { it.id !in seen },
                    hasStories = true,
                    showPlus = false,
                    index = i + 2,
                    onClick = {
                        snapshot = feed
                        viewerIndex = feed.indexOf(g)
                    },
                    onPlus = {}
                )
            }
        }
    }

    val vi = viewerIndex
    if (vi != null && snapshot.isNotEmpty() && vi in snapshot.indices) {
        StoryViewer(
            groups = snapshot,
            startGroup = vi,
            audioPreloader = audioPreloader,
            musicViewModel = musicViewModel,
            onClose = { viewerIndex = null }
        )
    }
    if (composer) StoryComposer(musicViewModel = musicViewModel, onDismiss = { composer = false })
    if (friendsOpen) FriendsSheet(onDismiss = { friendsOpen = false })
}

/** Заглушка на случай, если анимация перестановки недоступна: просто возвращает тот же Modifier. */
private fun Modifier.animateItemCompat(): Modifier = this

@Composable
private fun StoryBubble(
    name: String,
    b64: String?,
    filePath: String?,
    label: String,
    unseen: Boolean,
    hasStories: Boolean,
    showPlus: Boolean,
    index: Int,
    onClick: () -> Unit,
    onPlus: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressScale by rememberPressScale(interaction)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val spin = rememberInfiniteTransition(label = "storyRing")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "storyRingAngle"
    )
    val ringBrush = remember(cs.primary, cs.tertiary, cs.secondary) {
        Brush.sweepGradient(listOf(cs.primary, cs.tertiary, cs.secondary, cs.primary))
    }
    val active = hasStories && unseen
    val ringModifier = if (active) {
        Modifier.drawBehind {
            val stroke = 3.dp.toPx()
            rotate(angle) {
                drawCircle(brush = ringBrush, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
            }
        }
    } else {
        Modifier.border(2.dp, cs.outlineVariant, CircleShape)
    }

    Column(
        modifier = Modifier
            .width(76.dp)
            .graphicsLayer {
                val s = appear.value
                scaleX = s
                scaleY = s
                alpha = s.coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(72.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                    .then(ringModifier)
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
                    Icon(StoryIcons.Add, contentDescription = "Добавить историю", tint = cs.onPrimary, modifier = Modifier.size(14.dp))
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
private fun FriendsBubble(count: Int, index: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressScale by rememberPressScale(interaction)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Column(
        modifier = Modifier
            .width(76.dp)
            .graphicsLayer {
                val s = appear.value
                scaleX = s
                scaleY = s
                alpha = s.coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(72.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(cs.secondaryContainer)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(StoryIcons.Group, contentDescription = "Друзья", tint = cs.onSecondaryContainer, modifier = Modifier.size(30.dp))
            }
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(cs.error)
                        .border(2.dp, cs.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (count > 9) "9+" else count.toString(),
                        color = cs.onError,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Text(
            "Друзья",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
internal fun StoryAvatar(b64: String?, filePath: String?, label: String, size: Dp) {
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

/** Состояние звука истории-песни: ждём ссылку, играет фрагмент, не нашли или передали в основной плеер. */
internal enum class StoryAudioState { NONE, LOADING, READY, FAILED, HANDOFF }

@OptIn(UnstableApi::class)
@Composable
private fun StoryViewer(
    groups: List<StoryGroup>,
    startGroup: Int,
    audioPreloader: StoryAudioPreloader,
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
    var songBusy by remember { mutableStateOf(false) }
    var statsOpen by remember { mutableStateOf(false) }
    var stats by remember(story.id) { mutableStateOf(StoryStats()) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    var audio by remember(story.id) { mutableStateOf(if (story.isVideo) StoryAudioState.NONE else StoryAudioState.LOADING) }

    // появление и закрытие: мягкая пружина и плавное затухание
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    fun requestClose() {
        if (closing) return
        closing = true
        scope.launch {
            enter.animateTo(0f, tween(180))
            onClose()
        }
    }

    fun goNext() {
        if (si < group.stories.lastIndex) si++
        else if (gi < groups.lastIndex) { gi++; si = 0 }
        else requestClose()
    }
    fun goPrev() {
        if (si > 0) si--
        else if (gi > 0) { gi--; si = groups[gi].stories.lastIndex }
        else progress = 0f
    }
    val onNext by rememberUpdatedState({ goNext() })
    val onPrev by rememberUpdatedState({ goPrev() })
    val pausedNow by rememberUpdatedState(pressPaused || menuOpen || songBusy || statsOpen)

    val videoSources = remember(context) { DefaultMediaSourceFactory(context.applicationContext) }
    val player = remember(audioPreloader) {
        audioPreloader.buildPlayer().apply {
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
    DisposableEffect(player) { onDispose { player.release() } }

    val orderedStories = remember(groups) { groups.flatMap { it.stories } }
    LaunchedEffect(audioPreloader, story.id) {
        val position = orderedStories.indexOf(story)
        val upcoming = orderedStories.drop(position + 1).asSequence()
            .filter { !it.isVideo && it.song != null }.take(4).toList()
        audioPreloader.preload(listOf(story) + upcoming + listOfNotNull(orderedStories.getOrNull(position - 1)))
    }

    // реакции: у автора списки, у зрителя свой лайк; просмотр засчитывается после секунды
    LaunchedEffect(story.id) {
        val mineNow = group.isMine
        CloudStories.loadStats(story, mineNow).onSuccess { stats = it }
        if (!mineNow) {
            delay(1000)
            CloudStories.recordView(story)
        }
    }

    LaunchedEffect(story.id) {
        CloudStories.markSeen(story.id)
        progress = 0f
        val url = story.videoUrl
        val song = story.song
        when {
            url != null -> {
                player.setMediaSource(videoSources.createMediaSource(MediaItem.fromUri(url)))
                player.prepare()
                player.playWhenReady = !pausedNow
            }
            song != null -> {
                player.pause()
                player.clearMediaItems()
                val found = withTimeoutOrNull(StoryAudio.LOAD_TIMEOUT_MS) {
                    StoryAudio.resolve(context, song)
                }
                if (audio == StoryAudioState.HANDOFF) return@LaunchedEffect
                if (found == null) {
                    audio = StoryAudioState.FAILED
                } else {
                    audioPreloader.setClip(player, story, found)
                    player.prepare()
                    player.playWhenReady = !pausedNow
                }
            }
            else -> {
                player.stop()
                player.clearMediaItems()
            }
        }
    }
    LaunchedEffect(pausedNow) {
        if (story.isVideo || player.currentMediaItem?.mediaId == story.id && audio != StoryAudioState.HANDOFF) {
            player.playWhenReady = !pausedNow
        }
    }
    // видео и фрагмент песни идут по позиции плеера, поэтому видео доходит до конца.
    // Если звук песни не нашёлся или передан в основной плеер, историю ведёт обычный таймер
    LaunchedEffect(story.id) {
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(16)
            val now = SystemClock.uptimeMillis()
            val dt = now - last
            last = now
            if (pausedNow) continue
            val clipInstalled = player.currentMediaItem?.mediaId == story.id
            val followPlayer = story.isVideo ||
                clipInstalled && (audio == StoryAudioState.LOADING || audio == StoryAudioState.READY)
            when {
                followPlayer -> {
                    if (player.playerError != null) {
                        if (story.isVideo) { onNext(); return@LaunchedEffect }
                        player.stop()
                        audio = StoryAudioState.FAILED
                        continue
                    }
                    val state = player.playbackState
                    if (state == Player.STATE_READY && audio == StoryAudioState.LOADING) {
                        audio = StoryAudioState.READY
                    }
                    val d = player.duration
                    if (state == Player.STATE_ENDED) progress = 1f
                    else if (d > 0 && d != C.TIME_UNSET) progress = (player.currentPosition.toFloat() / d).coerceIn(0f, 1f)
                }
                audio == StoryAudioState.LOADING -> Unit // ждём звук, полоска стоит
                else -> progress = (progress + dt / SONG_STORY_MS).coerceAtMost(1f)
            }
            if (progress >= 1f) { onNext(); return@LaunchedEffect }
        }
    }

    // переход между историями: между людьми слайд, внутри одного человека лёгкое смещение с затуханием
    val storySpec: AnimatedContentTransitionScope<Pair<Int, Int>>.() -> ContentTransform = {
        val gd = targetState.first - initialState.first
        val sd = targetState.second - initialState.second
        if (gd != 0) {
            val d = if (gd > 0) 1 else -1
            (slideInHorizontally(tween(340)) { it * d } + fadeIn(tween(340))) togetherWith
                (slideOutHorizontally(tween(340)) { -it * d / 3 } + fadeOut(tween(260)))
        } else {
            val d = if (sd >= 0) 1 else -1
            (slideInHorizontally(tween(260)) { it * d / 8 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(260)) { -it * d / 8 } + fadeOut(tween(200)))
        }
    }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val e = enter.value
                    val s = (0.9f + 0.1f * e) * (1f - (dragY / 3000f).coerceIn(0f, 0.15f))
                    scaleX = s
                    scaleY = s
                    translationY = dragY
                    alpha = e.coerceIn(0f, 1f) * (1f - (dragY / 1200f).coerceIn(0f, 0.6f))
                }
                .background(Color.Black)
        ) {
            // 1. картинка: видео или фон истории-песни
            AnimatedContent(
                targetState = gi to si,
                transitionSpec = storySpec,
                modifier = Modifier.fillMaxSize(),
                label = "storyMedia"
            ) { (g, s) ->
                val st = groups.getOrNull(g)?.stories?.getOrNull(s)
                if (st != null) {
                    if (st.isVideo) {
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
                    } else {
                        SongBackdrop(st)
                    }
                }
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
                            onDragEnd = {
                                if (dragY > 220f) requestClose()
                                else scope.launch { animate(dragY, 0f, animationSpec = spring()) { v, _ -> dragY = v } }
                            },
                            onDragCancel = {
                                scope.launch { animate(dragY, 0f, animationSpec = spring()) { v, _ -> dragY = v } }
                            },
                            onVerticalDrag = { _, dy -> dragY = (dragY + dy).coerceAtLeast(0f) }
                        )
                    }
            )

            // 3. подпись видео и карточка песни поверх тапов, чтобы кнопка «Слушать» нажималась
            AnimatedContent(
                targetState = gi to si,
                transitionSpec = storySpec,
                modifier = Modifier.fillMaxSize(),
                label = "storyForeground"
            ) { (g, s) ->
                val st = groups.getOrNull(g)?.stories?.getOrNull(s)
                if (st != null) {
                    if (st.isVideo) VideoCaption(st)
                    else SongStoryForeground(
                        story = st,
                        musicViewModel = musicViewModel,
                        audioState = if (st.id == story.id) audio else StoryAudioState.NONE,
                        onBusy = { songBusy = it },
                        onHandoff = {
                            player.playWhenReady = false
                            audio = StoryAudioState.HANDOFF
                        }
                    )
                }
            }

            // 4. верхняя панель: полоски, автор, меню
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = enter.value.coerceIn(0f, 1f)
                        translationY = (1f - enter.value.coerceIn(0f, 1f)) * -40f
                    }
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
                    if (group.isMine) {
                        ReactionPill(StoryIcons.Eye, stats.views.size, Color.White) { statsOpen = true }
                        Spacer(Modifier.width(6.dp))
                        ReactionPill(StoryIcons.Heart, stats.likes.size, Color(0xFFFF4D6D)) { statsOpen = true }
                        Spacer(Modifier.width(2.dp))
                    } else {
                        LikeButton(stats.likedByMe) {
                            val st = story
                            val now = !stats.likedByMe
                            stats = stats.copy(likedByMe = now)
                            scope.launch {
                                CloudStories.setLike(st, now).onFailure {
                                    if (st.id == story.id) stats = stats.copy(likedByMe = !now)
                                    Toast.makeText(context, it.message ?: "Не удалось сохранить лайк", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                    }
                    Box {
                        Icon(
                            StoryIcons.More,
                            contentDescription = "Меню",
                            tint = Color.White,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { menuOpen = true }
                                .padding(10.dp)
                                .size(24.dp)
                        )
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (group.isMine) {
                                DropdownMenuItem(
                                    text = { Text("Удалить историю") },
                                    leadingIcon = { Icon(StoryIcons.Delete, null, Modifier.size(20.dp)) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            CloudStories.delete(story)
                                                .onSuccess {
                                                    Toast.makeText(context, "История удалена", Toast.LENGTH_SHORT).show()
                                                    requestClose()
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
                                    leadingIcon = { Icon(StoryIcons.Flag, null, Modifier.size(20.dp)) },
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
                    Icon(
                        StoryIcons.Close,
                        contentDescription = "Закрыть",
                        tint = Color.White,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { requestClose() }
                            .padding(10.dp)
                            .size(24.dp)
                    )
                }
            }

            if (statsOpen && group.isMine) {
                StoryStatsSheet(
                    story = story,
                    initial = stats,
                    onLoaded = { stats = it },
                    onDismiss = { statsOpen = false }
                )
            }
        }
    }
}

@Composable
private fun ReactionPill(icon: ImageVector, count: Int, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.9f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(count.toString(), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        if (liked) {
            scale.snapTo(0.65f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
    ) {
        Icon(
            StoryIcons.Heart,
            contentDescription = if (liked) "Убрать лайк" else "Лайк",
            tint = if (liked) Color(0xFFFF4D6D) else Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryStatsSheet(
    story: Story,
    initial: StoryStats,
    onLoaded: (StoryStats) -> Unit,
    onDismiss: () -> Unit
) {
    var stats by remember { mutableStateOf(initial) }
    var tab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(story.id) {
        CloudStories.loadStats(story, true).onSuccess { stats = it; onLoaded(it) }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.material3.TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Просмотры " + stats.views.size) })
            androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Лайки " + stats.likes.size) })
        }
        val list = if (tab == 0) stats.views else stats.likes
        if (list.isEmpty()) {
            Text(
                if (loading) "Загрузка…" else if (tab == 0) "Пока никто не смотрел" else "Пока нет лайков",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                items(list, key = { it.id }) { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        StoryAvatar(p.avatarB64, null, p.name, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(timeAgo(p.atMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VideoCaption(story: Story) {
    if (story.caption.isBlank()) return
    val a = remember(story.id) { Animatable(0f) }
    LaunchedEffect(story.id) {
        delay(200)
        a.animateTo(1f, tween(380))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = a.value
                    translationY = (1f - a.value) * 60f
                }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 28.dp)
        ) {
            Text(story.caption, color = Color.White, style = MaterialTheme.typography.bodyLarge)
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
private fun EqualizerBars(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "storyEq")
    val heights = (0 until 4).map { i ->
        t.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420 + i * 130, easing = EaseInOut), RepeatMode.Reverse),
            label = "storyEq$i"
        )
    }
    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleY = if (active) h.value else 0.3f
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun SongStoryForeground(
    story: Story,
    musicViewModel: MusicViewModel,
    audioState: StoryAudioState,
    onBusy: (Boolean) -> Unit,
    onHandoff: () -> Unit
) {
    val song = story.song ?: return
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    var wantPlay by remember(story.id) { mutableStateOf(false) }
    var started by remember(story.id) { mutableStateOf(false) }

    LaunchedEffect(story.id, wantPlay) {
        if (!wantPlay || started) return@LaunchedEffect
        onBusy(true)
        try {
            val found = withTimeoutOrNull(StoryAudio.LOAD_TIMEOUT_MS) {
                StoryAudio.resolve(context, song)
            }
            if (found != null) {
                started = true
                onHandoff()
                musicViewModel.playSong(found)
                Toast.makeText(context, "Играет: " + song.title, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Не удалось найти песню", Toast.LENGTH_SHORT).show()
            }
        } finally {
            wantPlay = false
            onBusy(false)
        }
    }

    // обложка выезжает пружиной с отскоком, как в плеере, потом «дышит»; текст и кнопка появляются по очереди
    val enter = remember(story.id) { Animatable(0f) }
    LaunchedEffect(story.id) {
        enter.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val breathe by rememberInfiniteTransition(label = "storyBreathe").animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(2400, easing = EaseInOut), RepeatMode.Reverse),
        label = "storyBreatheValue"
    )
    fun stage(from: Float): Float = ((enter.value - from) / 0.5f).coerceIn(0f, 1f)

    val btnSource = remember { MutableInteractionSource() }
    val btnScale by rememberPressScale(btnSource, 0.94f)

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
                .then(if (cover == null) Modifier.background(cs.primaryContainer) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                // фон под обложкой убран, а сама картинка чуть больше рамки: при дробном масштабе
                // по краям больше не просвечивают светлые полоски
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.03f; scaleY = 1.03f }
                )
            } else {
                Icon(StoryIcons.MusicNote, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(72.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val a = stage(0.25f)
                alpha = a
                translationY = (1f - a) * 48f
            }
        ) {
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
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqualizerBars(active = audioState == StoryAudioState.READY, color = Color.White)
                Text(
                    formatDuration(story.clipStartMs) + " – " + formatDuration(story.clipEndMs),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
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
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { if (!started && !wantPlay) wantPlay = true },
            interactionSource = btnSource,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.graphicsLayer {
                val a = stage(0.45f)
                alpha = a
                translationY = (1f - a) * 48f
                scaleX = btnScale
                scaleY = btnScale
            }
        ) {
            when {
                started -> {
                    Icon(StoryIcons.Check, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Играет")
                }
                wantPlay -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Загружаем…")
                }
                else -> {
                    Icon(StoryIcons.Play, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Слушать целиком")
                }
            }
        }
    }
}

// ───────────────────────── создание истории ─────────────────────────

private data class SongChoice(val song: CloudSong, val source: String)

private enum class ComposerStage { PREPARING, MENU, SONG_LIST, READY }

@Composable
private fun OptionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val scale by rememberPressScale(source, 0.97f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(cs.secondaryContainer.copy(alpha = 0.55f))
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(cs.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryComposer(musicViewModel: MusicViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favs by ProfileSongs.songs.collectAsState()
    val plTracks by PlaylistStore.tracks.collectAsState()
    val playlists by musicViewModel.playlists.collectAsState()

    var step by remember { mutableStateOf("menu") } // menu | song | video
    var preparing by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<PreparedVideo?>(null) }
    var pickedSong by remember { mutableStateOf<SongChoice?>(null) }
    var caption by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var clipStart by remember { mutableLongStateOf(0L) }
    var clipLen by remember { mutableLongStateOf(StoryAudio.DEFAULT_CLIP_MS) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            preparing = true
            scope.launch {
                StoryCompressor.compress(context, uri)
                    .onSuccess { prepared = it; step = "video" }
                    .onFailure { Toast.makeText(context, it.message ?: "Не удалось подготовить видео", Toast.LENGTH_LONG).show() }
                preparing = false
            }
        }
    }

    val choices = remember(favs, plTracks, playlists) {
        val out = ArrayList<SongChoice>()
        favs.forEach { out.add(SongChoice(it, "Любимые песни")) }
        plTracks.forEach {
            out.add(SongChoice(CloudSong("online_" + it.id, it.title, it.artist, it.art, it.durMs), "Мой плейлист"))
        }
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
        prepared?.file?.delete()
        onDismiss()
    }

    fun publish() {
        if (posting) return
        scope.launch {
            posting = true
            val video = prepared
            val song = pickedSong?.song
            val result = when {
                step == "video" && video != null -> CloudStories.postVideo(video.file, caption)
                song != null -> CloudStories.postSong(song, caption, clipStart, clipStart + clipLen)
                else -> Result.failure(IllegalStateException("Нечего публиковать"))
            }
            posting = false
            result
                .onSuccess {
                    video?.file?.delete()
                    prepared = null
                    Toast.makeText(context, "История опубликована на 24 часа", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                .onFailure { Toast.makeText(context, it.message ?: "Не удалось опубликовать", Toast.LENGTH_LONG).show() }
        }
    }

    val stage = when {
        preparing -> ComposerStage.PREPARING
        step == "menu" -> ComposerStage.MENU
        step == "song" && pickedSong == null -> ComposerStage.SONG_LIST
        else -> ComposerStage.READY
    }

    ModalBottomSheet(onDismissRequest = { close() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .animateContentSize()
        ) {
            Text("Новая история", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Живёт 24 часа. Её увидят только ваши друзья.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 10 }) togetherWith fadeOut(tween(140))
                },
                label = "composerStage"
            ) { st ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (st) {
                        ComposerStage.PREPARING -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Готовим видео…", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        ComposerStage.MENU -> {
                            OptionCard(
                                icon = StoryIcons.Video,
                                title = "Видео из галереи",
                                subtitle = "До минуты, идёт до конца",
                                onClick = {
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                }
                            )
                            Spacer(Modifier.height(12.dp))
                            OptionCard(
                                icon = StoryIcons.MusicNote,
                                title = "Песня из плейлиста",
                                subtitle = "Любимые, плейлисты и онлайн",
                                onClick = { step = "song" }
                            )
                        }
                        ComposerStage.SONG_LIST -> {
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
                                            .clickable {
                                                pickedSong = c
                                                clipStart = 0L
                                                clipLen = minOf(StoryAudio.DEFAULT_CLIP_MS, if (c.song.dur > 0) c.song.dur else 240_000L)
                                            }
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                StoryIcons.MusicNote, null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            AsyncImage(
                                                model = ArtworkUtil.forSong(c.song.id, c.song.artwork),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
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
                        ComposerStage.READY -> {
                            val video = prepared
                            val choice = pickedSong
                            if (step == "video" && video != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(StoryIcons.Video, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Видео готово · " + formatDuration(video.durationMs) + " · " +
                                            String.format("%.1f", video.file.length() / 1_048_576f) + " МБ",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                if (video.trimmed) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Видео длиннее минуты, поэтому выложим первую минуту.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (choice != null) {
                                SongClipEditor(
                                    song = choice.song,
                                    startMs = clipStart,
                                    lenMs = clipLen,
                                    onStartChange = { clipStart = it },
                                    onLenChange = { clipLen = it }
                                )
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
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
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
    }
}


// ───────────────────────── редактор фрагмента песни ─────────────────────────

/** Полоса с «дорожкой» песни: рамку можно тянуть, тап ставит её в нужное место. Столбики только для вида. */
@Composable
private fun ClipTimeline(
    seed: String,
    durationMs: Long,
    startMs: Long,
    lenMs: Long,
    onStartChange: (Long) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bars = remember(seed) {
        var x = (seed.hashCode().toLong() and 0x7fffffffL) + 7L
        FloatArray(56) {
            x = (x * 1103515245L + 12345L) and 0x7fffffffL
            0.25f + (x % 1000L) / 1000f * 0.75f
        }
    }
    val curStart by rememberUpdatedState(startMs)
    val curLen by rememberUpdatedState(lenMs)
    val curDur by rememberUpdatedState(durationMs)
    val onStart by rememberUpdatedState(onStartChange)
    var widthPx by remember { mutableFloatStateOf(1f) }
    fun clampStart(v: Long): Long = v.coerceIn(0L, (curDur - curLen).coerceAtLeast(0L))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.45f))
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onStart(clampStart(curStart + (drag.x / widthPx * curDur).toLong()))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    onStart(clampStart((o.x / widthPx * curDur - curLen / 2f).toLong()))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp)) {
            val n = bars.size
            val step = size.width / n
            val bw = step * 0.55f
            val ws = startMs.toFloat() / durationMs * size.width
            val we = (startMs + lenMs).toFloat() / durationMs * size.width
            bars.forEachIndexed { i, h ->
                val cx = step * (i + 0.5f)
                val inside = cx in ws..we
                val bh = size.height * h
                drawRoundRect(
                    color = if (inside) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.3f),
                    topLeft = Offset(cx - bw / 2f, (size.height - bh) / 2f),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bw / 2f, bw / 2f)
                )
            }
            val pad = 5.dp.toPx()
            drawRoundRect(
                color = cs.primary,
                topLeft = Offset(ws - pad, -8.dp.toPx()),
                size = Size(we - ws + 2 * pad, size.height + 16.dp.toPx()),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
private fun SongClipEditor(
    song: CloudSong,
    startMs: Long,
    lenMs: Long,
    onStartChange: (Long) -> Unit,
    onLenChange: (Long) -> Unit
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val durationMs = if (song.dur > 0) song.dur else 240_000L
    var previewOn by remember(song) { mutableStateOf(false) }
    var resolved by remember(song) { mutableStateOf<Song?>(null) }
    var resolveFailed by remember(song) { mutableStateOf(false) }
    var attempt by remember(song) { mutableIntStateOf(0) }
    var playerState by remember(song) { mutableIntStateOf(Player.STATE_IDLE) }
    var playerItem by remember(song) { mutableStateOf<MediaItem?>(null) }
    val clip = remember(resolved, startMs, lenMs) {
        resolved?.let { StoryAudio.clip(it, song.id, startMs, startMs + lenMs) }
    }
    val previewLoading = previewOn && (clip == null || clip != playerItem || playerState != Player.STATE_READY)

    val player = remember(context, song) {
        val appContext = context.applicationContext
        val mediaSources = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(StoryAudio.dataSourceFactory(appContext))
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(StoryAudio.loadControl())
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playerState = player.playbackState
                playerItem = player.currentMediaItem
                if (playerState == Player.STATE_ENDED) previewOn = false
            }

            override fun onPlayerError(error: PlaybackException) {
                if (previewOn) Toast.makeText(context, "Не удалось проиграть фрагмент", Toast.LENGTH_SHORT).show()
                previewOn = false
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(song, attempt) {
        val found = withTimeoutOrNull(StoryAudio.LOAD_TIMEOUT_MS) { StoryAudio.resolve(context, song) }
        resolved = found
        resolveFailed = found == null
        if (resolveFailed && previewOn) {
            previewOn = false
            Toast.makeText(context, "Не удалось найти песню", Toast.LENGTH_SHORT).show()
        }
    }

    fun preparePreview(item: MediaItem) {
        if (player.currentMediaItem != item) player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = previewOn
    }

    LaunchedEffect(player, clip) {
        if (clip == null) return@LaunchedEffect
        if (player.currentMediaItem == clip) {
            player.playWhenReady = previewOn
            return@LaunchedEffect
        }
        if (player.currentMediaItem != null) {
            player.pause()
            delay(150L)
        }
        // Нажатие на кнопку могло уже подготовить этот фрагмент во время задержки.
        if (player.currentMediaItem != clip) preparePreview(clip)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(StoryIcons.MusicNote, null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Выберите фрагмент", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ClipTimeline(
            seed = song.id,
            durationMs = durationMs,
            startMs = startMs,
            lenMs = lenMs,
            onStartChange = onStartChange
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDuration(startMs) + " – " + formatDuration(startMs + lenMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            listOf(10_000L, 15_000L, 30_000L).forEach { l ->
                FilterChip(
                    selected = lenMs == l,
                    onClick = {
                        onLenChange(l)
                        onStartChange(startMs.coerceAtMost((durationMs - l).coerceAtLeast(0L)))
                    },
                    label = { Text((l / 1000).toString() + " с") },
                    enabled = l <= durationMs,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                previewOn = !previewOn
                if (previewOn) {
                    if (resolveFailed) {
                        resolveFailed = false
                        attempt++
                    }
                    if (clip != null) {
                        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                        preparePreview(clip)
                    }
                } else {
                    player.pause()
                    player.seekToDefaultPosition()
                }
            },
            shape = RoundedCornerShape(20.dp)
        ) {
            if (previewLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(if (previewOn) StoryIcons.Pause else StoryIcons.Play, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (previewOn) "Остановить" else "Послушать фрагмент")
        }
    }
}
