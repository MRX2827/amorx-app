package amorx

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

// Выезд снизу, как в Rhythm: пружина NoBouncy + StiffnessLow, верхние углы 32dp,
// которые выпрямляются у самого верха, содержимое проявляется по мере выезда.
@Composable
fun PlayerLikeSheet(visible: Boolean, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(1f)) {
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val offset = remember { Animatable(h) }
        LaunchedEffect(h) { if (!visible) offset.snapTo(h) }
        LaunchedEffect(visible) {
            offset.animateTo(
                if (visible) 0f else h,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
            )
        }
        val showing by remember { derivedStateOf { offset.value < h - 1f } }
        if (visible || showing) {
            val bg = MaterialTheme.colorScheme.surfaceContainer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = (1f - offset.value / h).coerceIn(0f, 1f)
                        val blend = ((f - 0.82f) / 0.18f).coerceIn(0f, 1f)
                        val top = (32f * (1f - blend * blend * blend)).dp
                        val bottom = (28f * (1f - f)).dp
                        translationY = offset.value
                        shape = RoundedCornerShape(
                            topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom
                        )
                        clip = true
                    }
                    .background(bg)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (1f - offset.value / h).coerceIn(0f, 1f) }
                ) {
                    content()
                }
            }
        }
    }
}

// Каскад входа как в ExpressivePlayerScreen телефона:
// 900 мс линейно, у каждого ряда свой отрезок, FastOutSlowIn для сдвига и прозрачности,
// CubicBezier(0.34, 1.56, 0.64, 1) для «выпрыгивания» масштаба.
private val PopEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
private fun popScale(e: Float) = 0.88f + 0.12f * e.coerceIn(0f, 1f) + 0.45f * (e - 1f).coerceAtLeast(0f)

private class Line(val alpha: Float, val ty: Float, val scale: Float)

private fun line(f: Float, start: Float, len: Float, dyPx: Float): Line {
    val raw = ((f - start) / len).coerceIn(0f, 1f)
    val fr = FastOutSlowInEasing.transform(raw)
    return Line(fr, dyPx * (1f - fr), popScale(PopEasing.transform(raw)))
}

private fun Modifier.entrance(l: Line): Modifier = graphicsLayer {
    alpha = l.alpha
    translationY = l.ty
    scaleX = l.scale
    scaleY = l.scale
}

@Composable
fun FullPlayer(p: PlayerState, onClose: () -> Unit) {
    val t = p.current ?: demoTracks[0]

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); started = true }
    val f by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "entrance"
    )
    val d = LocalDensity.current.density
    val art = line(f, 0.00f, 0.45f, 44f * d)
    val titleL = line(f, 0.08f, 0.45f, 32f * d)
    val sliderL = line(f, 0.22f, 0.48f, 40f * d)
    val buttonsL = line(f, 0.46f, 0.54f, 48f * d)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
                Text(
                    "Сейчас играет",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.size(48.dp))
            }
            if (wide) {
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { BigCover(t.color, p.playing, art, t.artwork) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Controls(p, t, titleL, sliderL, buttonsL) }
                }
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BigCover(t.color, p.playing, art, t.artwork)
                    Spacer(Modifier.height(24.dp))
                    Controls(p, t, titleL, sliderL, buttonsL)
                }
            }
        }
    }
}

@Composable
private fun BigCover(c: Color, playing: Boolean, l: Line, url: String?) {
    val s by animateFloatAsState(
        targetValue = if (playing) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ArtworkScale"
    )
    val r by animateDpAsState(
        targetValue = if (playing) 32.dp else 48.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ArtworkCorner"
    )
    Box(
        modifier = Modifier
            .entrance(l)
            .graphicsLayer { scaleX = s; scaleY = s }
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(r))
            .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.5f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.MusicNote, null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(96.dp)
        )
        RemoteImage(url, Modifier.fillMaxSize())
    }
}

@Composable
private fun Controls(p: PlayerState, t: Track, titleL: Line, sliderL: Line, buttonsL: Line) {
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Row(Modifier.entrance(titleL), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                t.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LikeButton(t)
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.entrance(sliderL)) {
            Slider(
                value = p.position.coerceIn(0f, p.duration.coerceAtLeast(1).toFloat()),
                onValueChange = { p.position = it },
                valueRange = 0f..p.duration.coerceAtLeast(1).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(p.position.toInt()), style = MaterialTheme.typography.bodySmall)
                Text(fmt(p.duration), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().entrance(buttonsL),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { }) { Icon(Icons.Rounded.Shuffle, null) }
            IconButton(onClick = { p.prev() }) { Icon(Icons.Rounded.SkipPrevious, null) }
            CookieButton(p.playing, 72.dp) { p.toggle() }
            IconButton(onClick = { p.next() }) { Icon(Icons.Rounded.SkipNext, null) }
            IconButton(onClick = { }) { Icon(Icons.Rounded.Repeat, null) }
        }
    }
}
