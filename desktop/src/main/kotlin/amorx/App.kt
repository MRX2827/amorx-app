package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class DesktopPage(val title: String, val icon: ImageVector) {
    Home("Главное", Icons.Rounded.Home),
    Wave("Моя волна", Icons.Rounded.GraphicEq),
    Search("Поиск музыки", Icons.Rounded.MusicNote),
    People("Люди", Icons.Rounded.Group),
    Library("Скачанное", Icons.Rounded.Download),
    Playlists("Плейлисты", Icons.Rounded.QueueMusic),
    Chats("Сообщения", Icons.AutoMirrored.Rounded.Chat),
    Profile("Профиль", Icons.Rounded.AccountCircle)
}

@Composable
fun App(openAudio: () -> java.io.File?, fullscreen: () -> Unit, onInstalled: () -> Unit) {
    val scope = rememberCoroutineScope()
    val accountIdentity by CloudProfiles.activeAccount
    val musicRestore = remember(scope) { DesktopMusicRestore(scope) }
    val player = remember(scope) { PlayerState(scope) }
    var page by remember { mutableStateOf(DesktopPage.Home) }
    var fullPlayer by remember { mutableStateOf(false) }
    var welcome by remember { mutableStateOf(DesktopWelcomeStore.shouldShow()) }
    var settings by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val search = remember(scope) { DesktopSearchState(scope) }
    val openLocal: () -> Unit = {
        openAudio()?.let { file ->
            DesktopLibrary.openLocal(file)?.let { player.play(it, listOf(it)) }
        }
    }
    fun navigate(destination: DesktopPage) {
        page = destination
        rootFocus.requestFocus()
    }
    fun finishWelcome(destination: DesktopPage) {
        DesktopWelcomeStore.markSeen()
        welcome = false
        navigate(destination)
    }

    LaunchedEffect(accountIdentity) {
        musicRestore.changeAccount(accountIdentity)
        accountIdentity?.let { CloudProfiles.syncOwnProfile(it) }
    }
    LaunchedEffect(player) {
        rootFocus.requestFocus()
        while (true) {
            delay(160)
            player.tick()
        }
    }
    val downloadMessage = Downloads.message.value
    val libraryMessage = DesktopLibrary.message.value
    LaunchedEffect(downloadMessage) {
        if (downloadMessage != null) {
            delay(4000)
            if (Downloads.message.value == downloadMessage) Downloads.message.value = null
        }
    }

    LaunchedEffect(libraryMessage) {
        if (libraryMessage != null) {
            delay(4000)
            if (DesktopLibrary.message.value == libraryMessage) DesktopLibrary.message.value = null
        }
    }

    AmorxTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
                .focusRequester(rootFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when {
                        event.key == Key.Escape && fullPlayer -> {
                            fullPlayer = false
                            rootFocus.requestFocus()
                            true
                        }
                        event.key == Key.Escape && welcome -> {
                            finishWelcome(DesktopPage.Home)
                            true
                        }
                        event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed) && !welcome -> {
                            fullPlayer = false
                            searchFocus.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(end = 12.dp, bottom = 12.dp)) {
                    DesktopHeader(
                        search = search,
                        searchFocus = searchFocus,
                        onSearch = {
                            if (search.submit() || search.submitted.isNotEmpty()) navigate(DesktopPage.Search)
                        },
                        onHome = { navigate(DesktopPage.Home) },
                        openLocal = openLocal,
                        fullscreen = fullscreen,
                        openChats = { navigate(DesktopPage.Chats) },
                        openAccount = { account = true },
                        openSettings = { settings = true },
                        openWelcome = {
                            navigate(DesktopPage.Home)
                            welcome = true
                        }
                    )
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        DesktopRail(page, ::navigate, { settings = true }, player)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            DesktopUpdateBanner(onInstalled)
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                AnimatedContent(
                                    targetState = page,
                                    modifier = Modifier.fillMaxSize(),
                                    transitionSpec = {
                                        val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                        (fadeIn(tween(270, delayMillis = 35)) +
                                            slideInHorizontally(tween(300)) { direction * (it / 48) }) togetherWith
                                            (fadeOut(tween(130)) +
                                                slideOutHorizontally(tween(170)) { -direction * (it / 72) })
                                    },
                                    label = "desktop navigation"
                                ) { destination ->
                                    when (destination) {
                                        DesktopPage.Home -> key(accountIdentity) {
                                            DesktopHome(
                                                player,
                                                openChats = { navigate(DesktopPage.Chats) },
                                                openPeople = { navigate(DesktopPage.People) }
                                            )
                                        }
                                        DesktopPage.Search -> OnlineScreen(player, search)
                                        DesktopPage.Library, DesktopPage.Playlists -> DownloadsScreen(
                                            player, openAudio = openAudio, onSignIn = { account = true },
                                            initialSection = if (destination == DesktopPage.Playlists) "playlists" else "downloads",
                                            onRestore = {
                                                if (accountIdentity == null) account = true
                                                else musicRestore.show()
                                            },
                                            restoreState = musicRestore.state.takeIf { it.phase == MusicRestorePhase.Restoring }
                                        )
                                        DesktopPage.Wave -> WaveScreen(player)
                                        DesktopPage.People, DesktopPage.Chats, DesktopPage.Profile -> key(accountIdentity) {
                                            val entry = when (destination) {
                                                DesktopPage.People -> ProfileEntry.People
                                                DesktopPage.Chats -> ProfileEntry.Chats
                                                else -> ProfileEntry.Profile
                                            }
                                            ProfileScreen(player, entry)
                                        }
                                    }
                                }
                            }
                            val message = player.error ?: libraryMessage ?: downloadMessage
                            if (message != null) {
                                Text(
                                    message, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = if (player.error != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            DesktopDock(player) {
                                rootFocus.requestFocus()
                                fullPlayer = true
                            }
                        }
                    }
                }
                PlayerLikeSheet(fullPlayer) {
                    FullPlayer(player, onWave = {
                        fullPlayer = false
                        navigate(DesktopPage.Wave)
                    }, fullscreen = fullscreen) {
                        fullPlayer = false
                        rootFocus.requestFocus()
                    }
                }
                AnimatedVisibility(welcome, enter = fadeIn(tween(280)), exit = fadeOut(tween(260))) {
                    DesktopWelcome(
                        openAccount = { account = true },
                        finish = ::finishWelcome
                    )
                }
            }
            if (musicRestore.visible) DesktopMusicRestoreDialog(
                musicRestore.state,
                onAccept = musicRestore::accept,
                onDecline = musicRestore::decline,
                onRetry = musicRestore::retry,
                onLibrary = {
                    musicRestore.decline()
                    navigate(DesktopPage.Library)
                }
            )
            if (settings) DesktopSettingsDialog { settings = false }
            if (account) AccountDialog { account = false }
        }
    }
}
