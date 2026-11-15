package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 36.dp,
    active: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val palette = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (pressed) .9f else if (hovered && enabled) 1.055f else 1f,
        tween(if (pressed) 90 else 190),
        label = "button scale"
    )
    val background = animateColorAsState(
        when {
            primary && enabled -> if (hovered) palette.secondary else palette.primary
            active || hovered || focused -> palette.surfaceContainerHigh
            else -> Color.Transparent
        },
        tween(170), label = "button background"
    )
    val tint = animateColorAsState(
        when {
            !enabled -> palette.onSurfaceVariant.copy(alpha = .34f)
            primary -> palette.onPrimary
            active || hovered || focused -> palette.onSurface
            else -> palette.onSurfaceVariant
        },
        tween(170), label = "button icon"
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        Box(
            Modifier.size(size)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(CircleShape)
                .background(background.value)
                .hoverable(source, enabled = enabled)
                .clickable(interactionSource = source, indication = null, enabled = enabled,
                    role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tint.value, modifier = Modifier.size(if (primary) 24.dp else 19.dp))
        }
    }
}

@Composable
internal fun DesktopHeader(
    search: DesktopSearchState,
    searchFocus: FocusRequester,
    onSearch: () -> Unit,
    onHome: () -> Unit,
    openLocal: () -> Unit,
    fullscreen: () -> Unit,
    openChats: () -> Unit,
    openAccount: () -> Unit,
    openSettings: () -> Unit,
    openWelcome: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp).padding(start = 12.dp)) {
        val roomy = maxWidth >= 710.dp
        val palette = MaterialTheme.colorScheme
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.CenterStart)) {
            Surface(
                onClick = { menu = true },
                shape = CircleShape,
                color = palette.surfaceContainerLow
            ) {
                Row(
                    Modifier.height(31.dp).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp))
                    Text("AmorX", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (roomy) Text(
                        "v${DesktopUpdate.currentVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.onSurfaceVariant
                    )
                    Icon(Icons.Rounded.KeyboardArrowDown, "Меню AmorX", Modifier.size(14.dp), tint = palette.onSurfaceVariant)
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Главное") }, onClick = { menu = false; onHome() })
                DropdownMenuItem(text = { Text("Открыть файл…") }, onClick = { menu = false; openLocal() })
                DropdownMenuItem(text = { Text("Аккаунт AmorX") }, onClick = { menu = false; openAccount() })
                DropdownMenuItem(text = { Text("Настройки") }, onClick = { menu = false; openSettings() })
                DropdownMenuItem(text = { Text("Первый запуск") }, onClick = { menu = false; openWelcome() })
                DropdownMenuItem(text = { Text("Полноэкранный режим") }, onClick = { menu = false; fullscreen() })
            }
        }
        DesktopSearchBar(
            search.query, { search.query = it }, onSearch,
            Modifier.align(Alignment.Center).width((maxWidth * if (roomy) .39f else .47f).coerceAtMost(420.dp)),
            searchFocus
        )
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (roomy) DesktopIconAction(Icons.Rounded.FolderOpen, "Открыть аудиофайл", openLocal)
            DesktopIconAction(Icons.AutoMirrored.Rounded.Chat, "Сообщения", openChats)
            DesktopIconAction(Icons.Rounded.Fullscreen, "Полноэкранный режим (F11)", fullscreen)
        }
    }
}

@Composable
private fun DesktopSearchBar(
    value: String,
    setValue: (String) -> Unit,
    search: () -> Unit,
    modifier: Modifier,
    focus: FocusRequester
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val palette = MaterialTheme.colorScheme
    val border by animateColorAsState(
        if (focused) palette.outline else Color.Transparent,
        tween(220), label = "search focus"
    )
    Surface(
        modifier,
        shape = CircleShape,
        color = palette.surfaceContainer,
        border = BorderStroke(1.dp, border)
    ) {
        Row(Modifier.height(34.dp).padding(start = 12.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = palette.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value, setValue,
                Modifier.weight(1f).focusRequester(focus)
                    .semantics { contentDescription = "Поиск музыки" }
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                            search()
                            true
                        } else false
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.onSurface),
                cursorBrush = SolidColor(palette.primary),
                interactionSource = source,
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text("Поиск", style = MaterialTheme.typography.bodyMedium,
                            color = palette.onSurfaceVariant)
                        field()
                    }
                }
            )
            if (value.isNotBlank()) DesktopIconAction(Icons.Rounded.ArrowForward, "Найти", search, size = 27.dp)
        }
    }
}

@Composable
internal fun DesktopRail(page: DesktopPage, select: (DesktopPage) -> Unit, settings: () -> Unit, player: PlayerState) {
    val destinations = listOf(DesktopPage.Home, DesktopPage.Wave, DesktopPage.Search,
        DesktopPage.People, DesktopPage.Library)
    val history by PlaybackHistory.items
    val recents = history.filter { it.artwork != null }.take(4)
    BoxWithConstraints(Modifier.width(76.dp).fillMaxHeight()) {
        val spacious = maxHeight >= 540.dp
        val recentCount = if (maxHeight >= 700.dp) 4 else 2
        val gap = if (spacious) 14.dp else 4.dp
        Column(
            Modifier.fillMaxSize().padding(top = if (spacious) 16.dp else 4.dp, bottom = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            destinations.forEach { destination ->
                DesktopIconAction(destination.icon, destination.title, { select(destination) },
                    size = if (spacious) 44.dp else 34.dp, active = page == destination)
                Spacer(Modifier.height(gap))
            }
            if (spacious && recents.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                recents.take(recentCount).forEach { track ->
                    Cover(
                        track.color, 34.dp, corner = 8.dp,
                        modifier = Modifier.semantics { contentDescription = "Воспроизвести ${track.title}" }
                            .bouncyClick { player.play(track, recents) },
                        url = track.artwork
                    )
                    Spacer(Modifier.height(7.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            DesktopIconAction(Icons.Rounded.Settings, "Настройки", settings, size = 34.dp)
            Spacer(Modifier.height(gap))
            DesktopIconAction(DesktopPage.Profile.icon, DesktopPage.Profile.title, { select(DesktopPage.Profile) },
                size = 34.dp, active = page == DesktopPage.Profile)
        }
    }
}

@Composable
internal fun DesktopDock(player: PlayerState, onOpen: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val track = player.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.outlineVariant)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 3.dp)) {
            val roomy = maxWidth >= 760.dp
            val extra = maxWidth >= 525.dp
            val position = player.position
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(
                        targetState = track, modifier = Modifier.weight(1f),
                        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(150)) },
                        label = "dock track details"
                    ) { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = item != null, onClickLabel = "Открыть плеер", onClick = onOpen)
                                .padding(end = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item == null) {
                                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.MusicNote, null, tint = palette.onSurfaceVariant)
                                }
                            } else Cover(item.color, 38.dp, 8.dp, url = item.artwork)
                            Spacer(Modifier.width(if (extra) 10.dp else 6.dp))
                            Column {
                                Text(item?.title ?: if (extra) "Выберите трек через поиск" else "Нет трека",
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item != null) Text(item.artist, style = MaterialTheme.typography.bodySmall,
                                    color = palette.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(if (roomy) 6.dp else 1.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (roomy) {
                            val liked = track?.let { Likes.isLiked(it.id) } == true
                            DesktopIconAction(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (liked) "Убрать из любимых" else "Добавить в любимые",
                                { track?.let(Likes::toggle) }, enabled = track?.isReal == true, active = liked)
                            DesktopIconAction(Icons.Rounded.Shuffle, "Перемешать очередь", player::toggleShuffle,
                                enabled = track != null, active = player.shuffle)
                        }
                        DesktopIconAction(Icons.Rounded.SkipPrevious, "Предыдущий трек", player::prev,
                            size = if (extra) 34.dp else 28.dp, enabled = track != null)
                        AnimatedContent(
                            targetState = player.playing, transitionSpec = { fadeIn(tween(155)) togetherWith fadeOut(tween(115)) },
                            label = "dock playback action"
                        ) { playing ->
                            DesktopIconAction(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playing) "Пауза" else "Воспроизвести", player::toggle,
                                size = if (extra) 44.dp else 38.dp, primary = true, enabled = track != null)
                        }
                        DesktopIconAction(Icons.Rounded.SkipNext, "Следующий трек", player::next,
                            size = if (extra) 34.dp else 28.dp, enabled = track != null)
                        if (roomy) {
                            DesktopIconAction(Icons.Rounded.RepeatOne, "Повтор трека", { player.repeat = !player.repeat },
                                enabled = track != null, active = player.repeat)
                            DesktopVolumeMenu()
                        }
                    }
                    Row(Modifier.weight(if (extra) 1f else .54f), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        if (extra) Text("${fmt(position.toInt())}  /  ${fmt(player.duration)}",
                            style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant, maxLines = 1)
                        DesktopIconAction(Icons.Rounded.OpenInFull, "Открыть плеер", onOpen,
                            size = if (extra) 34.dp else 28.dp, enabled = track != null)
                    }
                }
                DockSeekSlider(player)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockSeekSlider(player: PlayerState) {
    val duration = player.duration.toFloat().coerceAtLeast(1f)
    var preview by remember(player.current?.id) { mutableStateOf<Float?>(null) }
    val progress = animateFloatAsState(
        (player.position / duration).coerceIn(0f, 1f),
        tween(170), label = "dock playback progress"
    )
    val palette = MaterialTheme.colorScheme
    Slider(
        value = preview ?: progress.value,
        onValueChange = { preview = it },
        onValueChangeFinished = { preview?.let { player.seek(it * duration) }; preview = null },
        enabled = player.current != null && player.duration > 0 && !player.loading,
        modifier = Modifier.fillMaxWidth().height(20.dp)
            .semantics { contentDescription = "Позиция воспроизведения" },
        thumb = {},
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(palette.outlineVariant, cornerRadius = radius)
                if (state.value > 0f) drawRoundRect(
                    palette.primary,
                    size = Size(size.width * state.value, size.height),
                    cornerRadius = radius
                )
            }
        }
    )
}

@Composable
private fun DesktopVolumeMenu() {
    var open by remember { mutableStateOf(false) }
    val saved by SettingsStore.current
    var volume by remember(saved.volume) { mutableFloatStateOf(saved.volume.toFloat()) }
    Box {
        DesktopIconAction(if (saved.volume == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            "Громкость", { open = !open }, active = open)
        DropdownMenu(open, { open = false }) {
            Column(Modifier.width(188.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text("Громкость ${volume.roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge)
                Slider(
                    volume, {
                        volume = it
                        Engine.setVolume(it.roundToInt())
                    },
                    valueRange = 0f..100f,
                    onValueChangeFinished = { SettingsStore.setVolume(volume.roundToInt()) }
                )
            }
        }
    }
}
