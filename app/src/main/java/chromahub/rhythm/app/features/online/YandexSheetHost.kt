package chromahub.rhythm.app.features.online

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel

/** Хост экрана плейлиста Яндекса: выезжает как плеер, уезжает вниз, когда открыт сам плеер. */
@Composable
fun YandexSheetHost(vm: MusicViewModel, route: String?) {
    val open by YandexImport.playlistOpen.collectAsState()
    val onPlayer = route == "player"
    LaunchedEffect(route) {
        if (route != null && route != "online" && route != "player") {
            YandexImport.playlistOpen.value = false
        }
    }
    PlayerLikeSheet(visible = open && !onPlayer) {
        YandexPlaylistScreen(vm)
    }
}
