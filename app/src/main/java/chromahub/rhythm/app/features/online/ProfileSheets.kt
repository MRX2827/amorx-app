package chromahub.rhythm.app.features.online

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel

/** Какие внутренние экраны профиля открыты. Хранится здесь, чтобы профиль оставался под ними. */
object ProfileNavState {
    val people: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * Выезд снизу, как у плеера: та же пружина (NoBouncy, StiffnessLow), верхние углы 32dp,
 * которые выпрямляются у самого верха, содержимое проявляется по мере выезда.
 */
@Composable
fun PlayerLikeSheet(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().zIndex(1f)) {
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val offset = remember { Animatable(h) }
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

/** То же, но для экрана, которому нужен объект (собеседник): при закрытии держит его до конца анимации. */
@Composable
fun <T : Any> PlayerLikeSheetFor(
    item: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (item != null) last[0] = item
    PlayerLikeSheet(visible = item != null, modifier = modifier) {
        @Suppress("UNCHECKED_CAST")
        val shown = last[0] as T?
        if (shown != null) content(shown)
    }
}

/** Профиль остаётся на месте, «Найти людей» открывается поверх него. */
@Composable
fun ProfileScreen(
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose {
            ProfileNavState.people.value = false
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        ProfileScreenBody(musicViewModel = musicViewModel, onBack = onBack, modifier = modifier)
        PlayerLikeSheet(visible = ProfileNavState.people.value) {
            PeopleScreen(
                musicViewModel = musicViewModel,
                onBack = { ProfileNavState.people.value = false }
            )
        }
    }
}

@Composable
internal fun ChatInboxHost(musicViewModel: MusicViewModel) {
    var visible by remember { mutableStateOf(false) }
    val requested by ChatNotifier.openChats.collectAsState()
    LaunchedEffect(requested) {
        if (requested) {
            visible = true
            ChatNotifier.openChats.value = false
        }
    }
    PlayerLikeSheet(visible = visible) {
        ChatsScreen(musicViewModel = musicViewModel, onBack = { visible = false })
    }
}
