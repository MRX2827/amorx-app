package amorx

import androidx.compose.runtime.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val CookieShape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(cx, cy) * 0.93f
    val steps = 120
    for (i in 0..steps) {
        val t = 2.0 * PI * i / steps
        val rr = r * (1.0 + 0.06 * cos(10.0 * t))
        val x = cx + (rr * cos(t)).toFloat()
        val y = cy + (rr * sin(t)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    if (url == null) return
    val img by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { loadImageBitmap(it) } }.getOrNull()
        }
    }
    img?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}

@Composable
fun Cover(color: Color, size: Dp, corner: Dp = 12.dp, modifier: Modifier = Modifier, url: String? = null) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.MusicNote, null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(size * 0.45f)
        )
        RemoteImage(url, Modifier.fillMaxSize())
    }
}

@Composable
fun CookieButton(playing: Boolean, size: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .bouncyClick(onClick)
            .size(size)
            .clip(CookieShape)
            .background(MaterialTheme.colorScheme.inverseSurface),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

// Анимации как в ExpressiveMiniPlayer телефона: пружина прогресса, нажатие 0.98,
// «подпрыгивание» при появлении (1.05) и смене песни (1.02), свайпы вверх/вниз/в стороны.
@Composable
fun MiniPlayer(p: PlayerState, onOpen: () -> Unit) {
    val t = p.current ?: return

    val animatedProgress by animateFloatAsState(
        targetValue = (p.position / p.duration.coerceAtLeast(1)).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "progress"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "scale"
    )

    var songBounce by remember { mutableStateOf(false) }
    val songBounceScale by animateFloatAsState(
        targetValue = if (songBounce) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "songBounceScale"
    )
    var appearBounce by remember { mutableStateOf(false) }
    val appearScale by animateFloatAsState(
        targetValue = if (appearBounce) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "initialAppearanceBounceScale"
    )
    LaunchedEffect(t.id) {
        appearBounce = true
        delay(150)
        appearBounce = false
        delay(50)
        songBounce = true
        delay(100)
        songBounce = false
    }

    var offsetY by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    var dismissing by remember { mutableStateOf(false) }

    val translationY by animateFloatAsState(
        targetValue = when {
            dismissing -> 500f
            offsetY > 0 -> offsetY.coerceAtMost(250f)
            else -> 0f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "translationOffsetY"
    )
    val translationX by animateFloatAsState(
        targetValue = offsetX.coerceIn(-300f, 300f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "translationOffsetX"
    )
    val alphaValue by animateFloatAsState(
        targetValue = when {
            dismissing -> 0f
            offsetY > 0 -> (1f - (offsetY / 250f)).coerceIn(0f, 1f)
            else -> 1f
        },
        animationSpec = tween(150),
        label = "alphaValue"
    )
    val dragScale by animateFloatAsState(
        targetValue = if (offsetY > 0) (1f - (offsetY / 1000f)).coerceAtLeast(0.9f) else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "dragScale"
    )

    LaunchedEffect(dismissing) {
        if (dismissing) {
            delay(250)
            if (t.videoUrl != null) Engine.setPause(true)
            p.playing = false
            p.current = null
        }
    }

    val total = pressScale * songBounceScale * appearScale * dragScale

    Surface(
        modifier = Modifier
            .graphicsLayer {
                this.translationX = translationX
                this.translationY = translationY
                this.alpha = alphaValue
                scaleX = total
                scaleY = total
            }
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .pointerInput(t.id) {
                detectDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        when {
                            offsetY < -100f -> onOpen()
                            offsetY > 100f -> dismissing = true
                            offsetX > 120f -> p.prev()
                            offsetX < -120f -> p.next()
                        }
                        offsetX = 0f
                        offsetY = 0f
                    },
                    onDragCancel = { offsetX = 0f; offsetY = 0f },
                    onDrag = { change, drag ->
                        change.consume()
                        offsetX += drag.x
                        offsetY += drag.y
                    }
                )
            }
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onOpen),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(t.color, 56.dp, url = t.artwork)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        t.artist + "  ·  " + fmt(p.position.toInt()) + "/" + fmt(p.duration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CookieButton(p.playing, 56.dp) { p.toggle() }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .bouncyClick { p.next() }
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.SkipNext, null) }
            }
        }
    }
}
