package amorx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface as SkiaSurface

@Composable
fun PlayerLikeSheet(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize().zIndex(1f),
        enter = slideInVertically(tween(365, easing = FastOutSlowInEasing)) { it / 5 } +
            fadeIn(tween(285)),
        exit = slideOutVertically(tween(275, easing = FastOutSlowInEasing)) { it / 6 } +
            fadeOut(tween(225))
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0C0C0C)).pointerInput(Unit) { detectTapGestures {} }) {
            content()
        }
    }
}

private data class PlayerArtwork(val cover: ImageBitmap, val backdrop: ImageBitmap)

private suspend fun loadPlayerArtwork(url: String): PlayerArtwork? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 4000
            readTimeout = 5000
        }
        val encoded = connection.getInputStream().use { it.readBytes() }
        SkiaImage.makeFromEncoded(encoded).use { image ->
            // Blur a small raster once and animate its layer rather than blurring every frame.
            val ratio = 180f / maxOf(image.width, image.height)
            val width = (image.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (image.height * ratio).roundToInt().coerceAtLeast(1)
            SkiaSurface.makeRasterN32Premul(width, height).use { surface ->
                ImageFilter.makeBlur(8f, 8f, FilterTileMode.CLAMP).use { blur ->
                    Paint().use { paint ->
                        paint.imageFilter = blur
                        surface.canvas.drawImageRect(
                            image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                            Rect.makeWH(width.toFloat(), height.toFloat()),
                            SamplingMode.LINEAR, paint, true
                        )
                    }
                }
                surface.makeImageSnapshot().use { backdrop ->
                    PlayerArtwork(image.toComposeImageBitmap(), backdrop.toComposeImageBitmap())
                }
            }
        }
    }.getOrNull()
}

private val SoftWhite = Color.White.copy(alpha = 0.72f)

@Composable
fun FullPlayer(
    p: PlayerState,
    onWave: (() -> Unit)? = null,
    fullscreen: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    val track = p.current
    val artworkUrl = track?.artwork
    val artwork by key(artworkUrl) {
        produceState<PlayerArtwork?>(null) {
            value = artworkUrl?.let { loadPlayerArtwork(it) }
        }
    }
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(720, easing = LinearEasing)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 650.dp
        val headerInset = if (compact) 12.dp else 20.dp
        val top = if (compact) 50.dp else 64.dp
        val bottom = if (compact) 8.dp else 24.dp
        val controlsHeight = if (compact) 185.dp else 230.dp
        val coverSize = minOf(
            maxHeight * 0.4f,
            maxWidth - 48.dp,
            (maxHeight - top - bottom - controlsHeight).coerceAtLeast(95.dp),
            560.dp
        )
        PlayerBackdrop(artwork?.backdrop, track?.color, p.playing)
        Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.width(maxOf(coverSize, 260.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlayerCover(track?.color, artwork?.cover, coverSize, p.playing, entrance)
                Spacer(Modifier.height(if (compact) 12.dp else 26.dp))
                PlayerMetadata(track, entrance)
                PlayerTimeline(p, entrance)
                Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                PlayerTransport(p, compact, entrance)
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                PlayerVolume(compact, entrance)
            }
        }
        PlayerHeader(
            p, onWave, fullscreen, onClose,
            Modifier.fillMaxWidth().padding(
                horizontal = headerInset,
                vertical = if (compact) 9.dp else 42.dp
            )
        )
    }
}

@Composable
private fun PlayerBackdrop(image: ImageBitmap?, color: Color?, playing: Boolean) {
    val fallback by animateColorAsState(
        targetValue = color?.copy(alpha = 0.36f) ?: Color(0xFF232323),
        animationSpec = tween(650),
        label = "player ambient color"
    )
    val drift = remember { Animatable(0f) }
    LaunchedEffect(playing, image != null) {
        if (playing && image != null) {
            while (true) {
                drift.animateTo(1f, tween(18000, easing = EaseInOutCubic))
                drift.animateTo(-1f, tween(21000, easing = EaseInOutCubic))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Color(0xFF101010))) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF101010), fallback, Color(0xFF111111)))
            )
        )
        val maxDrift = with(LocalDensity.current) { (maxWidth * 0.035f).toPx() }
        Crossfade(image, Modifier.fillMaxSize(), animationSpec = tween(650), label = "player backdrop") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val shift = drift.value
                        translationX = shift * maxDrift
                        translationY = shift * maxDrift * 0.28f
                        scaleX = 1.13f + shift * 0.018f
                        scaleY = scaleX
                    },
                    contentScale = ContentScale.Crop
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.58f),
                    0.55f to Color.Black.copy(alpha = 0.27f),
                    1f to Color.Black.copy(alpha = 0.39f)
                )
            )
        )
    }
}

private fun Modifier.playerEntrance(entrance: Animatable<Float, *>, start: Float, travel: Dp) = graphicsLayer {
    val progress = FastOutSlowInEasing.transform(((entrance.value - start) / 0.62f).coerceIn(0f, 1f))
    translationY = travel.toPx() * (1f - progress)
    alpha = progress
}

@Composable
private fun PlayerCover(
    color: Color?, cover: ImageBitmap?, size: Dp, playing: Boolean, entrance: Animatable<Float, *>
) {
    val scale = animateFloatAsState(
        if (playing) 1f else 0.943f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "player cover scale"
    )
    val colorValue by animateColorAsState(color ?: Color(0xFF343434), tween(420), label = "player cover color")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier.size(size)
            .playerEntrance(entrance, 0f, 26.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .shadow(25.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colorValue, Color(0xFF171717)))),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(cover, Modifier.fillMaxSize(), animationSpec = tween(350), label = "player cover") { bitmap ->
            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(size * 0.25f), tint = SoftWhite)
                }
            } else {
                Image(bitmap, "Обложка трека", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun PlayerHeader(
    p: PlayerState,
    onWave: (() -> Unit)?,
    fullscreen: (() -> Unit)?,
    close: () -> Unit,
    modifier: Modifier
) {
    val track = p.current
    var queueOpen by remember { mutableStateOf(false) }
    Box(modifier.height(40.dp), contentAlignment = Alignment.Center) {
        Text("СЕЙЧАС ИГРАЕТ", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
            letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerIcon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер", filled = true, onClick = close)
            if (onWave != null) PlayerIcon(Icons.Rounded.GraphicEq, "Моя волна", filled = true, onClick = onWave)
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fullscreen != null) PlayerIcon(Icons.Rounded.Fullscreen, "Полноэкранный режим", filled = true, onClick = fullscreen)
            PlayerIcon(Icons.Rounded.QueueMusic, "Очередь", filled = true, enabled = track != null) { queueOpen = true }
        }
    }
    if (queueOpen) {
        AlertDialog(
            onDismissRequest = { queueOpen = false },
            title = { Text("Очередь") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp)) {
                    items(p.queue) { queued ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(queued.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(queued.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            leadingIcon = {
                                if (queued.id == track?.id) Icon(Icons.Rounded.GraphicEq, "Текущий трек")
                                else Cover(queued.color, 32.dp, corner = 7.dp, url = queued.artwork)
                            },
                            onClick = {
                                p.play(queued)
                                queueOpen = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { queueOpen = false }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun PlayerMetadata(track: Track?, entrance: Animatable<Float, *>) {
    Row(Modifier.fillMaxWidth().playerEntrance(entrance, 0.10f, 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Crossfade(
            targetState = track,
            modifier = Modifier.weight(1f),
            animationSpec = tween(260),
            label = "player track metadata"
        ) { current ->
            Column {
                Text(
                    current?.title ?: "Выберите трек через поиск",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (current?.artist?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    Text(current.artist, color = SoftWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (track?.isReal == true) {
            val liked = Likes.isLiked(track.id)
            PlayerIcon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (liked) "Убрать из любимых" else "Добавить в любимые",
                active = liked
            ) { Likes.toggle(track) }
        }
        if (track?.videoUrl != null) {
            val saved = Downloads.find(track.id) != null
            val pending = Downloads.pending.value[track.id] != null
            PlayerIcon(
                if (saved) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                when {
                    saved -> "Скачано"
                    pending -> "Скачивается"
                    else -> "Скачать трек"
                },
                enabled = !saved && !pending
            ) { Downloads.download(track) }
        }
    }
}

@Composable
private fun PlayerTimeline(p: PlayerState, entrance: Animatable<Float, *>) {
    val track = p.current
    val duration = p.duration.coerceAtLeast(1).toFloat()
    var seek by remember(track?.id) { mutableStateOf<Float?>(null) }
    val progress by animateFloatAsState(
        targetValue = if (track == null) 0f else (p.position / duration).coerceIn(0f, 1f),
        animationSpec = tween(155, easing = LinearEasing),
        label = "player timeline"
    )
    Column(Modifier.fillMaxWidth().playerEntrance(entrance, 0.15f, 16.dp)) {
        PlayerSlider(
            value = seek ?: progress,
            label = "Позиция трека",
            onValueChange = { seek = it },
            onValueChangeFinished = {
                seek?.let { p.seek(it * duration) }
                seek = null
            },
            enabled = track != null && p.duration > 0 && !p.loading && p.error == null
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((seek?.times(duration) ?: p.position).toInt().coerceAtLeast(0)), color = SoftWhite, fontSize = 12.sp)
            if (p.loading) Text("Загрузка…", color = SoftWhite, fontSize = 12.sp)
            else p.error?.let {
                Text(it, Modifier.weight(1f, fill = false).padding(horizontal = 12.dp),
                    color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(fmt(p.duration.coerceAtLeast(0)), color = SoftWhite, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayerTransport(p: PlayerState, compact: Boolean, entrance: Animatable<Float, *>) {
    val hasTrack = p.current != null
    Row(
        Modifier.playerEntrance(entrance, 0.23f, 18.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerIcon(
            Icons.Rounded.Shuffle, "Перемешать", size = 34.dp, active = p.shuffle,
            enabled = hasTrack, onClick = p::toggleShuffle
        )
        PlayerIcon(Icons.Rounded.SkipPrevious, "Предыдущий трек", size = 34.dp, enabled = hasTrack) { p.prev() }
        PlayerIcon(
            if (p.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (p.playing) "Пауза" else "Воспроизвести",
            size = if (compact) 38.dp else 46.dp,
            primary = true,
            enabled = hasTrack
        ) { p.toggle() }
        PlayerIcon(Icons.Rounded.SkipNext, "Следующий трек", size = 34.dp, enabled = hasTrack) { p.next() }
        PlayerIcon(Icons.Rounded.Repeat, "Повтор трека", size = 34.dp, active = p.repeat, enabled = hasTrack) {
            p.repeat = !p.repeat
        }
    }
}

@Composable
private fun PlayerVolume(compact: Boolean, entrance: Animatable<Float, *>) {
    val saved = SettingsStore.current.value.volume.coerceIn(0, 100)
    var volume by remember(saved) { mutableFloatStateOf(saved / 100f) }
    var lastAudible by remember { mutableIntStateOf(if (saved == 0) 75 else saved) }
    Row(
        Modifier.playerEntrance(entrance, 0.30f, 16.dp)
            .width(170.dp)
            .height(if (compact) 34.dp else 42.dp)
            .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
            .padding(start = 4.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val muted = volume <= 0f
        PlayerIcon(
            if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            if (muted) "Включить звук" else "Выключить звук",
            size = 30.dp
        ) {
            if (saved > 0) lastAudible = saved
            SettingsStore.setVolume(if (muted) lastAudible else 0)
        }
        PlayerSlider(
            value = volume,
            label = "Громкость",
            modifier = Modifier.weight(1f),
            thumbVisible = true,
            onValueChange = {
                volume = it
                Engine.setVolume((it * 100f).roundToInt())
            },
            onValueChangeFinished = {
                val level = (volume * 100f).roundToInt()
                if (level > 0) lastAudible = level
                SettingsStore.setVolume(level)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSlider(
    value: Float,
    label: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    thumbVisible: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val reveal = animateFloatAsState(
        if (enabled && (thumbVisible || hovered || focused || pressed)) 1f else 0f, tween(130),
        label = "player slider thumb"
    )
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.fillMaxWidth().height(25.dp).hoverable(source).semantics { contentDescription = label },
        interactionSource = source,
        enabled = enabled,
        thumb = {
            Box(Modifier.size(11.dp).graphicsLayer {
                alpha = reveal.value
                scaleX = reveal.value
                scaleY = reveal.value
            }.background(Color.White, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val stroke = 3.dp.toPx()
                val end = size.width - stroke / 2
                val start = Offset(stroke / 2, center.y)
                drawLine(Color.White.copy(alpha = 0.21f), start, Offset(end, center.y), stroke, StrokeCap.Round)
                if (enabled && state.value > 0f) {
                    val progress = start.x + (end - start.x) * state.value.coerceIn(0f, 1f)
                    drawLine(Color.White, start, Offset(progress, center.y), stroke, StrokeCap.Round)
                }
            }
        }
    )
}

@Composable
private fun PlayerIcon(
    icon: ImageVector,
    label: String,
    size: Dp = 40.dp,
    primary: Boolean = false,
    filled: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val highlight = enabled && (hovered || focused)
    val scale = animateFloatAsState(
        when {
            pressed -> 0.90f
            highlight -> 1.06f
            else -> 1f
        },
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "player button scale"
    )
    val background by animateColorAsState(
        when {
            primary && !enabled -> Color.White.copy(alpha = 0.48f)
            primary -> Color.White
            highlight || active -> Color.White.copy(alpha = 0.19f)
            filled -> Color.White.copy(alpha = 0.09f)
            else -> Color.Transparent
        },
        tween(145), label = "player button background"
    )
    Box(
        Modifier.size(size).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }.clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = source, indication = null, enabled = enabled,
                role = Role.Button, onClickLabel = label, onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, label,
            modifier = Modifier.size(if (primary) size * 0.6f else size * 0.52f),
            tint = when {
                primary -> Color(0xFF0B0B0B)
                !enabled -> Color.White.copy(alpha = 0.30f)
                active || highlight -> Color.White
                else -> SoftWhite
            }
        )
    }
}
