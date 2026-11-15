package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutQuart
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val names = listOf("Онлайн", "Скачанное", "Волна", "Профиль")
private val tabIcons: List<ImageVector> = listOf(
    Icons.Rounded.MusicNote, Icons.Rounded.Download, Icons.Rounded.GraphicEq, Icons.Rounded.Person
)

@Composable
fun App(openAudio: () -> java.io.File?, fullscreen: () -> Unit, onInstalled: () -> Unit) {
    AmorxTheme {
        val player = remember { PlayerState() }
        var tab by remember { mutableStateOf(0) }
        var open by remember { mutableStateOf(false) }
        var railIn by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            railIn = true
            while (true) {
                delay(250)
                player.tick()
            }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        AnimatedVisibility(
                            visible = railIn,
                            enter = slideInHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                initialOffsetX = { -it }
                            ) + fadeIn(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        ) {
                            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                Spacer(Modifier.height(8.dp))
                                names.forEachIndexed { i, n ->
                                    NavigationRailItem(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        icon = { Icon(tabIcons[i], null) },
                                        label = { Text(n) }
                                    )
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        DesktopControls(player, openAudio, fullscreen)
                        DesktopUpdateBanner(onInstalled)
                        // Переходы вкладок как в NavHost телефона: fade 300 мс + сдвиг 350 мс, EaseInOutQuart
                        AnimatedContent(
                            targetState = tab,
                            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                            transitionSpec = {
                                val fwd = targetState > initialState
                                (fadeIn(animationSpec = tween(300)) +
                                    slideInHorizontally(
                                        animationSpec = tween(350, easing = EaseInOutQuart),
                                        initialOffsetX = { if (fwd) it else -it }
                                    )) togetherWith
                                    (fadeOut(animationSpec = tween(300)) +
                                        slideOutHorizontally(
                                            animationSpec = tween(350, easing = EaseInOutQuart),
                                            targetOffsetX = { if (fwd) -it else it }
                                        ))
                            },
                            label = "tabs"
                        ) { page ->
                            when (page) {
                                0 -> OnlineScreen(player)
                                1 -> DownloadsScreen(player)
                                2 -> WaveScreen(player)
                                else -> ProfileScreen(player)
                            }
                        }
                        val err = player.error
                        val dl = Downloads.message.value
                        if (err != null) {
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        } else if (player.loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                        }
                        if (dl != null) {
                            LaunchedEffect(dl) {
                                delay(4000)
                                if (Downloads.message.value == dl) Downloads.message.value = null
                            }
                            Text(
                                dl,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                        MiniPlayer(player) { open = true }
                        if (!wide) FloatingNav(names, tabIcons, tab) { tab = it }
                    }
                }
                PlayerLikeSheet(open) { FullPlayer(player) { open = false } }
            }
        }
    }
}
