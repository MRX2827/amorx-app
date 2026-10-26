package chromahub.rhythm.app.features.update

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Кривая «с отскоком», как у остального интерфейса AmorX
private val BounceEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

// ================================================================ хост

/**
 * Ставится один раз рядом с AccountGate(). Сам проверяет обновления при открытии приложения,
 * показывает баннер сверху и окно обновления. Ничего не занимает в разметке экрана.
 */
@Composable
fun AmorUpdateHost() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val state by AmorUpdater.state.collectAsState()
    val sheetOpen by AmorUpdater.sheetOpen.collectAsState()
    val dismissed by AmorUpdater.dismissedLabel.collectAsState()

    LaunchedEffect(Unit) {
        AmorUpdater.init(app)
        delay(2500)
        AmorUpdater.autoCheck(app)
    }
    // Возврат в приложение: проверяем снова (не чаще, чем раз в несколько часов)
    val owner = remember(context) { findLifecycleOwner(context) }
    DisposableEffect(owner) {
        val o = owner
        if (o == null) {
            onDispose { }
        } else {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) scope.launch { AmorUpdater.autoCheck(app) }
            }
            o.lifecycle.addObserver(obs)
            onDispose { o.lifecycle.removeObserver(obs) }
        }
    }

    val release = state.currentRelease
    val showBanner = release != null && !sheetOpen && (dismissed != release.versionLabel)
    val bannerState = remember { MutableTransitionState(false) }
    bannerState.targetState = showBanner

    if (bannerState.currentState || bannerState.targetState) {
        val density = LocalDensity.current
        val topPx = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.roundToPx() }
        val widthDp = min(LocalConfiguration.current.screenWidthDp - 16, 480)
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, topPx),
            properties = PopupProperties(focusable = false, clippingEnabled = false)
        ) {
            AnimatedVisibility(
                visibleState = bannerState,
                enter = slideInVertically(spring(dampingRatio = 0.5f, stiffness = 360f)) { -it } +
                    fadeIn(tween(180)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 400f), initialScale = 0.92f),
                exit = slideOutVertically(tween(280, easing = FastOutLinearInEasing)) { -it } +
                    fadeOut(tween(200)) + scaleOut(tween(240), targetScale = 0.94f)
            ) {
                // Запас снизу, чтобы пружина не обрезалась краем окна
                Box(Modifier.width(widthDp.dp).padding(bottom = 28.dp)) {
                    // Баннер может исчезнуть раньше конца анимации: показываем последнее состояние
                    UpdateBanner(
                        state = state,
                        onOpen = { AmorUpdater.openSheet() },
                        onDismiss = { AmorUpdater.dismissBanner(app) }
                    )
                }
            }
        }
    }

    if (sheetOpen && release != null) {
        UpdateSheet(state = state, release = release, onClose = { AmorUpdater.closeSheet() })
    }
}

private fun findLifecycleOwner(context: Context): LifecycleOwner? {
    var c: Context? = context
    while (c != null) {
        if (c is LifecycleOwner) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

// ================================================================ баннер

private enum class HeroKind { Arrow, Progress, Done, Error }

private fun heroKind(s: AmorUpdateState): HeroKind = when (s) {
    is AmorUpdateState.Downloading -> HeroKind.Progress
    is AmorUpdateState.Ready -> HeroKind.Done
    is AmorUpdateState.Failed -> HeroKind.Error
    else -> HeroKind.Arrow
}

private fun progressOf(s: AmorUpdateState): Float =
    if (s is AmorUpdateState.Downloading && s.total > 0) (s.done.toFloat() / s.total.toFloat()).coerceIn(0f, 1f) else 0f

private fun mb(bytes: Long): String = String.format(Locale("ru", "RU"), "%.1f МБ", bytes / 1048576.0)

private fun speedText(bps: Long): String =
    if (bps <= 0) "" else String.format(Locale("ru", "RU"), "%.1f МБ/с", bps / 1048576.0)

private fun etaText(sec: Long): String = if (sec < 60) "$sec с" else "${sec / 60} мин ${sec % 60} с"

private fun bannerTitle(s: AmorUpdateState): String = when (s) {
    is AmorUpdateState.Downloading -> "Скачиваем обновление"
    is AmorUpdateState.Ready -> "Обновление готово"
    is AmorUpdateState.Failed -> "Не получилось"
    else -> "Доступно обновление"
}

private fun bannerSub(s: AmorUpdateState): String = when (s) {
    is AmorUpdateState.Available ->
        "AmorX ${s.release.versionLabel}" + if (s.release.apkSize > 0) " · " + mb(s.release.apkSize) else ""
    is AmorUpdateState.Downloading -> {
        val pct = (progressOf(s) * 100).toInt()
        "$pct%" + if (s.bytesPerSec > 0) " · " + speedText(s.bytesPerSec) else ""
    }
    is AmorUpdateState.Ready -> "Нажмите, чтобы установить ${s.release.versionLabel}"
    is AmorUpdateState.Failed -> s.message
    else -> ""
}

@Composable
private fun UpdateBanner(state: AmorUpdateState, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val mandatory = state.currentRelease?.mandatory == true
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.clickable(onClick = onOpen)) {
            Row(
                Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeroIcon(heroKind(state), progressOf(state), 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = bannerTitle(state),
                        transitionSpec = {
                            (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically(tween(200)) { -it / 2 })
                        },
                        label = "banner_title"
                    ) { t ->
                        Text(t, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        bannerSub(state),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                MiniPill(
                    text = if (state is AmorUpdateState.Ready) "Установить" else "Подробнее",
                    onClick = onOpen,
                    container = cs.primary,
                    content = cs.onPrimary
                )
                if (!mandatory) {
                    Spacer(Modifier.width(2.dp))
                    CloseButton(onDismiss, cs.onPrimaryContainer)
                }
            }
            if (state is AmorUpdateState.Downloading) {
                ShimmerBar(progressOf(state), Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp), 5.dp)
            }
        }
    }
}

// ================================================================ окно обновления

@Composable
private fun UpdateSheet(state: AmorUpdateState, release: AmorRelease, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val mandatory = release.mandatory
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val hint by AmorUpdater.hint.collectAsState()

    fun close() {
        if (closing) return
        closing = true
        visible = false
        scope.launch {
            delay(300)
            onClose()
        }
    }
    LaunchedEffect(Unit) { visible = true }

    // Встряска при ошибке
    val shake = remember { Animatable(0f) }
    val failed = state is AmorUpdateState.Failed
    LaunchedEffect(failed) {
        if (failed) {
            repeat(3) {
                shake.animateTo(10f, tween(55))
                shake.animateTo(-10f, tween(55))
            }
            shake.animateTo(0f, tween(60))
        }
    }

    Dialog(
        onDismissRequest = { if (!mandatory) close() },
        properties = DialogProperties(
            dismissOnBackPress = !mandatory,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        val scrim by animateColorAsState(
            if (visible) Color.Black.copy(alpha = 0.6f) else Color.Transparent,
            tween(300),
            label = "scrim"
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (!mandatory) close()
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(spring(dampingRatio = 0.72f, stiffness = 300f)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(280, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(220))
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                    color = cs.surface,
                    contentColor = cs.onSurface,
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                ) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                            .padding(top = 12.dp, bottom = 24.dp)
                            .offset { IntOffset(shake.value.roundToInt(), 0) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.width(40.dp).height(4.dp).clip(CircleShape)
                                .background(cs.onSurface.copy(alpha = 0.2f))
                        )
                        Spacer(Modifier.height(20.dp))
                        HeroIcon(heroKind(state), progressOf(state), 92.dp)
                        Spacer(Modifier.height(14.dp))
                        AnimatedContent(
                            targetState = sheetTitle(state),
                            transitionSpec = {
                                (fadeIn(tween(240, delayMillis = 80)) + slideInVertically(tween(300)) { it / 2 }) togetherWith
                                    (fadeOut(tween(140)) + slideOutVertically(tween(220)) { -it / 2 })
                            },
                            label = "sheet_title"
                        ) { t ->
                            Text(t, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(8.dp))
                        VersionRow(from = AmorUpdater.installedLabel(context), to = release.versionLabel)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (release.apkSize > 0) InfoChip(mb(release.apkSize))
                            InfoChip(if (release.source == "github") "GitHub" else "Сайт AmorX")
                            if (mandatory) InfoChip("обязательное", warn = true)
                        }
                        Spacer(Modifier.height(16.dp))
                        NotesBlock(release.notes)
                        Spacer(Modifier.height(18.dp))

                        AnimatedContent(
                            targetState = actionKind(state),
                            transitionSpec = {
                                (fadeIn(tween(260, delayMillis = 90)) + scaleIn(tween(300, easing = BounceEasing), initialScale = 0.9f)) togetherWith
                                    (fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.95f))
                            },
                            label = "sheet_action",
                            modifier = Modifier.fillMaxWidth()
                        ) { kind ->
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                when (kind) {
                                    0 -> {
                                        UpdBtn("Обновить сейчас", { AmorUpdater.startDownload(context, release) }, Modifier.fillMaxWidth())
                                        if (!mandatory) {
                                            Spacer(Modifier.height(6.dp))
                                            UpdText("Позже") { close() }
                                        }
                                    }
                                    1 -> {
                                        val d = state as? AmorUpdateState.Downloading
                                        if (d != null) DownloadBlock(d)
                                    }
                                    2 -> {
                                        UpdBtn(
                                            "Установить",
                                            { scope.launch { AmorUpdater.install(context) } },
                                            Modifier.fillMaxWidth(),
                                            pulse = true
                                        )
                                        AnimatedVisibility(visible = hint != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                                            Text(
                                                hint.orEmpty(),
                                                fontSize = 12.sp,
                                                textAlign = TextAlign.Center,
                                                color = cs.onSurface.copy(alpha = 0.65f),
                                                modifier = Modifier.padding(top = 10.dp)
                                            )
                                        }
                                        if (!mandatory) {
                                            Spacer(Modifier.height(4.dp))
                                            UpdText("Позже") { close() }
                                        }
                                    }
                                    else -> {
                                        val f = state as? AmorUpdateState.Failed
                                        Text(
                                            f?.message.orEmpty(),
                                            color = cs.error,
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )
                                        UpdBtn("Повторить", {
                                            AmorUpdater.resetFailure()
                                            AmorUpdater.startDownload(context, release)
                                        }, Modifier.fillMaxWidth())
                                        if (!mandatory) {
                                            Spacer(Modifier.height(6.dp))
                                            UpdText("Закрыть") {
                                                AmorUpdater.resetFailure()
                                                close()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sheetTitle(s: AmorUpdateState): String = when (s) {
    is AmorUpdateState.Downloading -> "Скачиваем обновление"
    is AmorUpdateState.Ready -> "Готово к установке"
    is AmorUpdateState.Failed -> "Не получилось"
    else -> "Доступно обновление"
}

private fun actionKind(s: AmorUpdateState): Int = when (s) {
    is AmorUpdateState.Downloading -> 1
    is AmorUpdateState.Ready -> 2
    is AmorUpdateState.Failed -> 3
    else -> 0
}

@Composable
private fun VersionRow(from: String, to: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(from, fontSize = 15.sp, color = cs.onSurface.copy(alpha = 0.6f))
        Text("  →  ", fontSize = 15.sp, color = cs.primary, fontWeight = FontWeight.Bold)
        Text(to, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.primary)
    }
}

@Composable
private fun InfoChip(text: String, warn: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (warn) cs.errorContainer else cs.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (warn) cs.onErrorContainer else cs.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Список изменений: строки появляются по очереди. */
@Composable
private fun NotesBlock(notes: String) {
    val cs = MaterialTheme.colorScheme
    val lines = remember(notes) { notes.lines().filter { it.isNotBlank() }.take(8) }
    if (lines.isNotEmpty()) {
        var shown by remember(notes) { mutableStateOf(0) }
        LaunchedEffect(notes) {
            for (i in 1..lines.size) {
                delay(80)
                shown = i
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceVariant.copy(alpha = 0.45f))
                .padding(16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Что нового", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
            lines.forEachIndexed { i, line ->
                AnimatedVisibility(
                    visible = i < shown,
                    enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 2 }
                ) {
                    Row {
                        Text("•  ", color = cs.primary, fontSize = 14.sp)
                        Text(line, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadBlock(d: AmorUpdateState.Downloading) {
    val cs = MaterialTheme.colorScheme
    val p = progressOf(d)
    val pct by animateIntAsState((p * 100).toInt(), tween(260), label = "pct")
    val eta = if (d.bytesPerSec > 0 && d.total > d.done) (d.total - d.done) / d.bytesPerSec else -1L
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$pct%", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = cs.primary)
        Spacer(Modifier.height(10.dp))
        ShimmerBar(p, Modifier.fillMaxWidth(), 14.dp)
        Spacer(Modifier.height(10.dp))
        val parts = buildList {
            if (d.total > 0) add(mb(d.done) + " из " + mb(d.total)) else add(mb(d.done))
            if (d.bytesPerSec > 0) add(speedText(d.bytesPerSec))
            if (eta >= 0) add("осталось " + etaText(eta))
        }
        Text(parts.joinToString(" · "), fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.65f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        MiniPill(
            text = "Отмена",
            onClick = { AmorUpdater.cancelDownload() },
            container = cs.surfaceVariant,
            content = cs.onSurfaceVariant
        )
    }
}

/** Полоса прогресса с бегущим бликом. */
@Composable
private fun ShimmerBar(progress: Float, modifier: Modifier, height: Dp) {
    val cs = MaterialTheme.colorScheme
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(260, easing = LinearEasing), label = "bar")
    val inf = rememberInfiniteTransition(label = "shimmer")
    val sh by inf.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "sh"
    )
    Box(modifier.height(height).clip(CircleShape).background(cs.primary.copy(alpha = 0.16f))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(p)
                .clip(CircleShape)
                .background(cs.primary)
                .drawWithContent {
                    drawContent()
                    val w = size.width
                    val x = sh * w
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                            startX = x - w * 0.3f,
                            endX = x + w * 0.3f
                        )
                    )
                }
        )
    }
}

// ================================================================ строка в настройках

/**
 * Строка «Обновления»: версия, кнопка проверки, статус. Ставится в профиль/настройки.
 */
@Composable
fun AmorUpdateRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val state by AmorUpdater.state.collectAsState()
    val installed = remember { AmorUpdater.installedLabel(app) }
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "rot"
    )
    val release = state.currentRelease
    val subtitle = when (state) {
        is AmorUpdateState.Checking -> "Проверяем…"
        is AmorUpdateState.UpToDate -> "У вас последняя версия"
        is AmorUpdateState.Downloading -> "Скачиваем " + (release?.versionLabel ?: "")
        is AmorUpdateState.Ready -> "Готово к установке: " + (release?.versionLabel ?: "")
        is AmorUpdateState.Available -> "Доступна версия " + (release?.versionLabel ?: "")
        is AmorUpdateState.Failed -> (state as AmorUpdateState.Failed).message
        else -> "Версия $installed"
    }
    val accent = release != null && state !is AmorUpdateState.Failed

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                if (release != null && state !is AmorUpdateState.Failed) {
                    AmorUpdater.openSheet()
                } else if (state !is AmorUpdateState.Checking) {
                    scope.launch { AmorUpdater.checkNow(app) }
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Проверить обновление", style = MaterialTheme.typography.titleSmall)
            AnimatedContent(
                targetState = subtitle,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                        (fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 2 })
                },
                label = "row_sub"
            ) { t ->
                Text(
                    t,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accent) cs.primary else cs.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        AnimatedContent(
            targetState = when {
                state is AmorUpdateState.Checking -> 0
                state is AmorUpdateState.UpToDate -> 1
                accent -> 2
                else -> 3
            },
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(320, easing = BounceEasing), initialScale = 0.6f)) togetherWith
                    (fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.7f))
            },
            label = "row_action"
        ) { kind ->
            when (kind) {
                0 -> RefreshIcon(cs.primary, rotation, 24.dp)
                1 -> CheckIcon(cs.primary, 24.dp)
                2 -> MiniPill(
                    text = if (state is AmorUpdateState.Ready) "Установить" else "Обновить",
                    onClick = { AmorUpdater.openSheet() },
                    container = cs.primary,
                    content = cs.onPrimary
                )
                else -> RefreshIcon(cs.onSurfaceVariant, 0f, 24.dp)
            }
        }
    }
}

// ================================================================ мелкие кнопки и иконки

@Composable
private fun UpdBtn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    pulse: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.45f, stiffness = 650f), label = "press")
    val inf = rememberInfiniteTransition(label = "pulse")
    val beat by inf.animateFloat(
        initialValue = 1f,
        targetValue = if (pulse) 1.035f else 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "beat"
    )
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .height(56.dp)
            .graphicsLayer {
                val s = press * beat
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(if (filled) cs.primary else Color.Transparent)
            .then(if (!filled) Modifier.border(1.dp, cs.outline, shape) else Modifier)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (filled) cs.onPrimary else cs.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun UpdText(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "txt")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = cs.onSurface.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MiniPill(text: String, onClick: () -> Unit, container: Color, content: Color) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.4f, stiffness = 700f), label = "pill")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(CircleShape)
            .background(container)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = content, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, color: Color) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.8f else 1f, spring(dampingRatio = 0.4f, stiffness = 700f), label = "close")
    Box(
        Modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.minDimension
            val sw = w * 0.16f
            drawLine(color, Offset(0f, 0f), Offset(w, w), sw, StrokeCap.Round)
            drawLine(color, Offset(w, 0f), Offset(0f, w), sw, StrokeCap.Round)
        }
    }
}

@Composable
private fun RefreshIcon(color: Color, rotation: Float, dimension: Dp) {
    Canvas(Modifier.size(dimension).graphicsLayer { rotationZ = rotation }) {
        val r = size.minDimension * 0.34f
        val c = Offset(size.width / 2, size.height / 2)
        val sw = size.minDimension * 0.11f
        val start = -60f
        val sweep = 270f
        drawArc(
            color, start, sweep, false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
        // наконечник стрелки на конце дуги
        val a = Math.toRadians((start + sweep).toDouble())
        val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        val tangent = Offset(-sin(a).toFloat(), cos(a).toFloat())
        val radial = Offset(cos(a).toFloat(), sin(a).toFloat())
        val s = size.minDimension * 0.2f
        val head = Path().apply {
            moveTo(p.x + tangent.x * s * 1.1f, p.y + tangent.y * s * 1.1f)
            lineTo(p.x + radial.x * s, p.y + radial.y * s)
            lineTo(p.x - radial.x * s, p.y - radial.y * s)
            close()
        }
        drawPath(head, color)
    }
}

@Composable
private fun CheckIcon(color: Color, dimension: Dp) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
    Canvas(Modifier.size(dimension)) {
        val w = size.minDimension
        val path = Path().apply {
            moveTo(w * 0.18f, w * 0.54f)
            lineTo(w * 0.42f, w * 0.76f)
            lineTo(w * 0.84f, w * 0.26f)
        }
        val pm = PathMeasure()
        pm.setPath(path, false)
        val dst = Path()
        pm.getSegment(0f, pm.length * prog.value, dst, true)
        drawPath(dst, color, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Большая иконка: стрелка с волнами, кольцо прогресса, галочка или «!». */
@Composable
private fun HeroIcon(kind: HeroKind, progress: Float, dimension: Dp) {
    val cs = MaterialTheme.colorScheme
    val accent = if (kind == HeroKind.Error) cs.error else cs.primary
    val onAccent = if (kind == HeroKind.Error) cs.onError else cs.onPrimary
    val inf = rememberInfiniteTransition(label = "hero")
    val t by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
        label = "t"
    )
    val smooth by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300, easing = LinearEasing), label = "ring")
    val fill by animateFloatAsState(if (kind == HeroKind.Done || kind == HeroKind.Error) 1f else 0f, tween(380), label = "fill")
    val mark = remember { Animatable(0f) }
    LaunchedEffect(kind) {
        mark.snapTo(0f)
        if (kind == HeroKind.Done || kind == HeroKind.Error) mark.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.size(dimension)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val sw = size.minDimension * 0.07f

        if (kind == HeroKind.Arrow) {
            for (k in 0..1) {
                val p = (t + k * 0.5f) % 1f
                drawCircle(accent.copy(alpha = (1f - p) * 0.35f), radius = r * (0.6f + p * 0.4f), center = c, style = Stroke(sw * 0.6f))
            }
        }
        drawCircle(accent.copy(alpha = 0.14f + 0.86f * fill), radius = r * 0.6f, center = c)

        if (kind == HeroKind.Progress) {
            val tl = Offset(c.x - r * 0.82f, c.y - r * 0.82f)
            val sz = Size(r * 1.64f, r * 1.64f)
            drawArc(accent.copy(alpha = 0.22f), 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(accent, -90f, 360f * smooth, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
        }

        when (kind) {
            HeroKind.Arrow, HeroKind.Progress -> {
                val bob = sin(t * 2 * PI).toFloat() * r * (if (kind == HeroKind.Arrow) 0.05f else 0.025f)
                drawArrowUp(Offset(c.x, c.y - bob), r * 0.3f, accent, sw)
            }
            HeroKind.Done -> {
                val path = Path().apply {
                    moveTo(c.x - r * 0.26f, c.y + r * 0.02f)
                    lineTo(c.x - r * 0.07f, c.y + r * 0.2f)
                    lineTo(c.x + r * 0.28f, c.y - r * 0.2f)
                }
                val pm = PathMeasure()
                pm.setPath(path, false)
                val dst = Path()
                pm.getSegment(0f, pm.length * mark.value, dst, true)
                drawPath(dst, onAccent, style = Stroke(sw * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            HeroKind.Error -> {
                val m = mark.value
                drawLine(onAccent, Offset(c.x, c.y - r * 0.3f), Offset(c.x, c.y - r * 0.3f + r * 0.36f * m), sw * 1.2f, StrokeCap.Round)
                if (m > 0.6f) drawCircle(onAccent, radius = sw * 0.75f, center = Offset(c.x, c.y + r * 0.24f))
            }
        }
    }
}

private fun DrawScope.drawArrowUp(c: Offset, half: Float, color: Color, stroke: Float) {
    drawLine(color, Offset(c.x, c.y + half), Offset(c.x, c.y - half), stroke, StrokeCap.Round)
    drawLine(color, Offset(c.x - half * 0.85f, c.y - half * 0.15f), Offset(c.x, c.y - half), stroke, StrokeCap.Round)
    drawLine(color, Offset(c.x + half * 0.85f, c.y - half * 0.15f), Offset(c.x, c.y - half), stroke, StrokeCap.Round)
}
