package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

internal object DesktopWelcomeStore {
    private val seen get() = File(AppDir.root, "desktop-welcome.json")

    fun shouldShow(): Boolean =
        !seen.isFile && listOf("settings.json", "cloud.json", "profile.json", "artists.json", "likes.json",
            "downloads/index.json").none { File(AppDir.root, it).isFile }

    fun markSeen() {
        runCatching { writeLocalJson(seen, "true") }
    }
}

private data class WelcomeStep(val title: String, val description: String)

private val welcomeSteps = listOf(
    WelcomeStep("Добро пожаловать\nв AmorX", "Слушайте музыку, открывайте новое и делитесь любимыми треками с друзьями."),
    WelcomeStep("Кто звучит в вашем\nмире?", "Отметьте любимых исполнителей для первых рекомендаций."),
    WelcomeStep("Всё готово", "Откройте «Главное» или начните слушать свою волну.")
)

private val welcomeBackground = Color(0xFF0C0C0C)
private val welcomeText = Color(0xFFF9F9F9)
private val welcomeMuted = Color(0xFFAAAAAA)

@Composable
internal fun DesktopWelcome(openAccount: () -> Unit, finish: (DesktopPage) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var artistPicker by remember { mutableStateOf(false) }
    val artists by WaveStore.artists
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().background(welcomeBackground)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        val large = maxWidth >= 760.dp && maxHeight >= 560.dp
        WelcomeRings(Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            val contentHeight = maxHeight
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 620.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).heightIn(min = contentHeight)
                    .padding(top = if (large) 70.dp else 42.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedContent(
                    step,
                    transitionSpec = {
                        (fadeIn(tween(300, delayMillis = 60)) +
                            slideInVertically(tween(330)) { it / 12 }) togetherWith fadeOut(tween(125))
                    },
                    label = "first-run steps"
                ) { index ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val content = welcomeSteps[index]
                        Text("Настройка · ${index + 1} из ${welcomeSteps.size}",
                            color = welcomeMuted, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(if (large) 24.dp else 12.dp))
                        Text(
                            content.title, textAlign = TextAlign.Center, color = welcomeText,
                            fontSize = if (large) 50.sp else 32.sp,
                            lineHeight = if (large) 53.sp else 35.sp,
                            letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(if (large) 28.dp else 12.dp))
                        Text(
                            content.description, textAlign = TextAlign.Center, color = welcomeMuted,
                            fontSize = if (large) 15.sp else 13.sp,
                            modifier = Modifier.widthIn(max = 420.dp)
                        )
                        Spacer(Modifier.height(if (large) 30.dp else 18.dp))
                        val extra = when (index) {
                            0 -> "Войти в аккаунт"
                            1 -> if (artists.isEmpty()) "Выбрать исполнителей" else "Исполнители · ${artists.size}"
                            else -> "Открыть мою волну"
                        }
                        WelcomeButton(extra, primary = false, large = large) {
                            when (index) {
                                0 -> openAccount()
                                1 -> artistPicker = true
                                else -> finish(DesktopPage.Wave)
                            }
                        }
                        Spacer(Modifier.height(if (large) 15.dp else 7.dp))
                        WelcomeButton(
                            if (index == 2) "Открыть главное" else "Продолжить",
                            primary = true,
                            large = large
                        ) { if (index == 2) finish(DesktopPage.Home) else step = index + 1 }
                        if (index < 2) {
                            Spacer(Modifier.height(if (large) 10.dp else 4.dp))
                            TextButton(
                                onClick = { if (index == 0) finish(DesktopPage.Home) else step = 2 },
                                colors = ButtonDefaults.textButtonColors(contentColor = welcomeMuted)
                            ) { Text("Пропустить") }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(start = if (large) 38.dp else 18.dp,
                top = if (large) 25.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(if (large) 26.dp else 20.dp), tint = welcomeText)
            Text("AmorX", color = welcomeText, fontSize = if (large) 23.sp else 17.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (artistPicker) ArtistPicker { artistPicker = false }
}

@Composable
private fun WelcomeButton(label: String, primary: Boolean, large: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (large) 14.dp else 10.dp)
    val modifier = Modifier.width(if (primary) 250.dp else 292.dp).height(if (large) 48.dp else 38.dp)
    if (primary) {
        Button(onClick, modifier, shape = shape, colors = ButtonDefaults.buttonColors(
            containerColor = welcomeText, contentColor = welcomeBackground
        )) { Text(label) }
    } else {
        OutlinedButton(onClick, modifier, shape = shape, border = BorderStroke(1.dp, Color(0xFF343434)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFF181818), contentColor = welcomeText
            )
        ) { Text(label) }
    }
}

@Composable
private fun WelcomeRings(modifier: Modifier) {
    val phase = rememberInfiniteTransition(label = "welcome ring drift").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Restart),
        label = "welcome rings phase"
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val motion = sin(phase.value)
        listOf(-1f, 1f).forEach { side ->
            val left = side < 0
            val point = Offset(w * (if (left) .11f else .885f) + motion * 10.dp.toPx(),
                h * (if (left) .59f else .34f) + motion * 15.dp.toPx() * side)
            val visibility = if (left) listOf(Color(0xFF454545), Color.Transparent)
                else listOf(Color.Transparent, Color(0xFF454545))
            val brush = Brush.horizontalGradient(visibility, point.x - w * .17f, point.x + w * .17f)
            val baseWidth = w * .19f
            val baseHeight = h * .53f
            val gap = (h * .017f).coerceAtLeast(5.dp.toPx())
            rotate(side * (16f + motion * 3.5f), point) {
                repeat(35) { ring ->
                    val increment = ring * gap
                    drawOval(
                        brush, Offset(point.x - (baseWidth + increment) / 2, point.y - (baseHeight + increment) / 2),
                        Size(baseWidth + increment, baseHeight + increment),
                        alpha = (1f - ring / 48f).coerceIn(0f, 1f),
                        style = Stroke(1.dp.toPx())
                    )
                }
            }
        }
    }
}
