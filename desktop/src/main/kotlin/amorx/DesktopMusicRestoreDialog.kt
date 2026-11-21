package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val RestoreWhite = Color(0xFFF7F7F7)
private val RestoreMuted = Color(0xFFA7A7AE)
private val RestoreSurface = Color(0xFF111114)
private val RestoreOutline = Color(0xFF333338)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopMusicRestoreDialog(
    state: MusicRestoreState,
    onAccept: (downloads: Boolean, playlists: Boolean) -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onLibrary: () -> Unit
) {
    var includeDownloads by remember { mutableStateOf(true) }
    var includePlaylists by remember { mutableStateOf(true) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val appearance by animateFloatAsState(
        if (entered) 1f else 0f,
        tween(380, easing = FastOutSlowInEasing), label = "music restore entrance"
    )
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val width = with(density) { window.width.toDp() }.minus(36.dp).coerceIn(280.dp, 520.dp)
    val height = with(density) { window.height.toDp() }.minus(48.dp).coerceIn(260.dp, 610.dp)
    val selectedDownloads = includeDownloads && state.downloadCount > 0
    val selectedPlaylists = includePlaylists && state.playlistCount > 0

    BasicAlertDialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(width).heightIn(max = height)
            .graphicsLayer {
                alpha = appearance
                scaleX = .96f + appearance * .04f
                scaleY = .96f + appearance * .04f
                translationY = (1f - appearance) * 18.dp.toPx()
            }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(25.dp),
            color = RestoreSurface,
            border = BorderStroke(1.dp, RestoreOutline),
            shadowElevation = 18.dp,
            contentColor = RestoreWhite
        ) {
            Box(Modifier.clipToBounds()) {
                RestoreAtmosphere(Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .padding(horizontal = 25.dp).padding(top = 20.dp, bottom = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        RestoreDialogHeader(state.phase, onDecline)
                        AnimatedContent(
                            targetState = state.phase,
                            transitionSpec = {
                                (fadeIn(tween(260, delayMillis = 55)) + slideInVertically(tween(300)) { it / 18 }) togetherWith
                                    fadeOut(tween(120))
                            },
                            label = "music restore content"
                        ) { phase ->
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                RestoreHeading(phase)
                                when (phase) {
                                    MusicRestorePhase.Discovering -> RestoreDiscovering()
                                    MusicRestorePhase.Offer -> {
                                        if (state.downloadCount == 0 && state.playlistCount == 0) {
                                            Text("В облаке пока нет сохранённых песен или плейлистов.",
                                                color = RestoreMuted, style = MaterialTheme.typography.bodyMedium)
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                                RestoreChoice(
                                                    Icons.Rounded.Download, "Скачанные песни",
                                                    restoreSongs(state.downloadCount), state.downloadCount > 0,
                                                    includeDownloads, { includeDownloads = it }
                                                )
                                                RestoreChoice(
                                                    Icons.AutoMirrored.Rounded.QueueMusic, "Плейлист AmorX",
                                                    restorePlaylists(state.playlistCount), state.playlistCount > 0,
                                                    includePlaylists, { includePlaylists = it }
                                                )
                                            }
                                        }
                                        if (state.unavailable > 0) Text(
                                            "Пропущено недоступных треков — ${state.unavailable}.",
                                            color = RestoreMuted, style = MaterialTheme.typography.bodySmall
                                        )
                                        state.message?.takeIf { it.isNotBlank() }?.let { message ->
                                            Text(message, color = RestoreMuted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    MusicRestorePhase.Restoring -> RestoreDownloadProgress(state)
                                    MusicRestorePhase.Finished -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Text(state.message ?: if (state.completed > 0) "Сохранено ${restoreSongs(state.completed)}." else "Библиотека обновлена.",
                                                color = RestoreWhite, style = MaterialTheme.typography.bodyLarge)
                                            if (state.failed > 0) Text(
                                                "Не удалось скачать ${restoreSongs(state.failed)}. Можно повторить загрузку.",
                                                color = RestoreMuted, style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                    MusicRestorePhase.Failed -> Text(
                                        state.message ?: "Не удалось получить вашу музыку из облака. Попробуйте ещё раз.",
                                        color = RestoreMuted, style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    RestoreDialogActions(
                        state, selectedDownloads || selectedPlaylists,
                        onAccept = { onAccept(selectedDownloads, selectedPlaylists) },
                        onDecline, onRetry, onLibrary
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreDialogHeader(phase: MusicRestorePhase, close: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(50.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF42404F), Color(0xFF22222A)))),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                if (phase == MusicRestorePhase.Finished) Icons.Rounded.Check else Icons.Rounded.CloudDownload,
                transitionSpec = { fadeIn(tween(230)) togetherWith fadeOut(tween(130)) },
                label = "restore header icon"
            ) { icon -> Icon(icon, null, Modifier.size(25.dp), tint = RestoreWhite) }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = close, modifier = Modifier.size(31.dp)) {
            Icon(Icons.Rounded.Close, if (phase == MusicRestorePhase.Restoring) "Свернуть загрузку" else "Закрыть",
                tint = RestoreMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RestoreHeading(phase: MusicRestorePhase) {
    val title = when (phase) {
        MusicRestorePhase.Discovering -> "Ваша музыка"
        MusicRestorePhase.Offer -> "Скачать вашу музыку?"
        MusicRestorePhase.Restoring -> "Музыка возвращается"
        MusicRestorePhase.Finished -> "Всё готово"
        MusicRestorePhase.Failed -> "Загрузка недоступна"
    }
    val description = when (phase) {
        MusicRestorePhase.Discovering -> "Ищем все скачанные песни и ваш плейлист AmorX."
        MusicRestorePhase.Offer -> "Верните свои треки на этот компьютер и слушайте их без интернета."
        MusicRestorePhase.Restoring -> "Готовые треки сразу появятся в «Скачанном»."
        MusicRestorePhase.Finished -> "Откройте библиотеку, чтобы начать слушать."
        MusicRestorePhase.Failed -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, color = RestoreWhite, fontSize = 28.sp, lineHeight = 32.sp,
            letterSpacing = (-.5).sp, fontWeight = FontWeight.SemiBold)
        if (description != null) Text(description, color = RestoreMuted,
            style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
    }
}

@Composable
private fun RestoreDiscovering() {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        CircularProgressIndicator(Modifier.size(19.dp), color = RestoreWhite, strokeWidth = 2.dp)
        Text("Проверяем облачную копию…", color = RestoreMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RestoreChoice(
    icon: ImageVector,
    title: String,
    summary: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = checked && enabled
    val background by animateColorAsState(
        when {
            hovered && enabled -> Color(0xFF2B2933)
            selected -> Color(0xFF222127)
            else -> Color(0xFF17171B)
        }, tween(175), label = "restore choice hover"
    )
    val border by animateColorAsState(
        if (selected) Color(0xFF5A5767) else RestoreOutline, tween(175), label = "restore choice selection"
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .hoverable(interaction, enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Checkbox, onClick = { onCheckedChange(!checked) }),
        color = background, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, border)
    ) {
        Row(Modifier.padding(start = 15.dp, end = 9.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = if (enabled) RestoreWhite else RestoreMuted.copy(alpha = .45f),
                modifier = Modifier.size(23.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = if (enabled) RestoreWhite else RestoreMuted,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(summary, color = RestoreMuted, style = MaterialTheme.typography.bodySmall)
            }
            Checkbox(
                checked = selected, onCheckedChange = onCheckedChange, enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = RestoreWhite, checkmarkColor = RestoreSurface,
                    uncheckedColor = RestoreMuted, disabledUncheckedColor = RestoreMuted.copy(alpha = .3f))
            )
        }
    }
}

@Composable
private fun RestoreDownloadProgress(state: MusicRestoreState) {
    val progress by animateFloatAsState(state.progress.coerceIn(0f, 1f), tween(300), label = "music restore progress")
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp), tint = RestoreMuted)
            Spacer(Modifier.width(9.dp))
            Text(state.activeTitle ?: "Подготавливаем загрузку…", Modifier.weight(1f),
                color = RestoreWhite, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                .semantics { contentDescription = "Прогресс восстановления музыки" },
            color = RestoreWhite, trackColor = Color(0xFF34323D), gapSize = 0.dp, drawStopIndicator = {}
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Готово ${state.completed + state.failed} из ${state.total}", color = RestoreMuted,
                style = MaterialTheme.typography.bodySmall)
            Text("${(progress * 100).toInt()}%", color = RestoreMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (state.failed > 0) Text("Не удалось скачать ${restoreSongs(state.failed)}.",
            color = RestoreMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RestoreDialogActions(
    state: MusicRestoreState,
    hasSelection: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onLibrary: () -> Unit
) {
    val primary = when (state.phase) {
        MusicRestorePhase.Offer -> "Да, скачать"
        MusicRestorePhase.Restoring, MusicRestorePhase.Finished -> "Открыть библиотеку"
        MusicRestorePhase.Failed -> "Повторить"
        MusicRestorePhase.Discovering -> null
    }
    val action = when (state.phase) {
        MusicRestorePhase.Offer -> onAccept
        MusicRestorePhase.Restoring, MusicRestorePhase.Finished -> onLibrary
        else -> onRetry
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp).padding(top = 9.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (primary != null) Button(
            onClick = action,
            enabled = state.phase != MusicRestorePhase.Offer || hasSelection,
            modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = RestoreWhite, contentColor = RestoreSurface,
                disabledContainerColor = Color(0xFF333238), disabledContentColor = RestoreMuted
            )
        ) { Text(primary, fontWeight = FontWeight.SemiBold) }
        if (state.phase == MusicRestorePhase.Finished && state.failed > 0) TextButton(
            onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = RestoreWhite)
        ) { Text("Повторить неудачные загрузки") }
        TextButton(onClick = onDecline, colors = ButtonDefaults.textButtonColors(contentColor = RestoreMuted)) {
            Text(when (state.phase) {
                MusicRestorePhase.Restoring -> "Свернуть"
                MusicRestorePhase.Finished -> "Готово"
                MusicRestorePhase.Failed -> "Закрыть"
                else -> "Не сейчас"
            })
        }
    }
}

@Composable
private fun RestoreAtmosphere(modifier: Modifier) {
    val phase = rememberInfiniteTransition(label = "restore atmospheric motion").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart),
        label = "restore drift"
    )
    Canvas(modifier) {
        val time = phase.value
        val first = Offset(size.width * (.14f + .11f * sin(time)), size.height * (.04f + .08f * cos(time)))
        val second = Offset(size.width * (.88f + .09f * cos(time)), size.height * (.5f + .12f * sin(time)))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF514662).copy(alpha = .28f), Color.Transparent),
                center = first, radius = size.width * .9f),
            radius = size.width * .9f, center = first
        )
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF33494E).copy(alpha = .13f), Color.Transparent),
                center = second, radius = size.width * .64f),
            radius = size.width * .64f, center = second
        )
        repeat(32) { i ->
            val x = size.width * (i / 31f)
            val wave = .11f * sin(i * .28f + time) + .035f * cos(i * .71f - time)
            val center = Offset(x, size.height * (.11f + wave))
            drawCircle(Color.White.copy(alpha = .065f), radius = (.55f + (i % 3) * .13f).dp.toPx(), center = center)
        }
    }
}

private fun restoreSongs(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "треков"
    count % 10 == 1 -> "трек"
    count % 10 in 2..4 -> "трека"
    else -> "треков"
}

private fun restorePlaylists(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "плейлистов"
    count % 10 == 1 -> "плейлист"
    count % 10 in 2..4 -> "плейлиста"
    else -> "плейлистов"
}
