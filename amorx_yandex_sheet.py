import sys, os, shutil
A = "app/src/main/java/chromahub/rhythm/app/"
O = A + "features/online/"
SCREEN = O + "OnlineScreen.kt"
NAV = A + "features/local/presentation/navigation/LocalNavigation.kt"
NEWF = O + "YandexSheetHost.kt"

def stop(m):
    print("СТОП " + m + ". Ничего не записано."); sys.exit(1)

if os.path.exists(NEWF): stop("файл уже существует: " + NEWF)
for p in (SCREEN, NAV):
    if not os.path.exists(p): stop("не найден файл " + p)
scr = open(SCREEN, encoding="utf-8").read()
nav = open(NAV, encoding="utf-8").read()

OLD = '''    val ypOpen by YandexImport.playlistOpen.collectAsState()
    if (ypOpen) {
        YandexPlaylistScreen(musicViewModel = musicViewModel, modifier = modifier)
        return
    }
'''
if scr.count(OLD) != 1:
    stop("OnlineScreen: блок ypOpen найден %d раз (нужно 1)" % scr.count(OLD))
scr = scr.replace(OLD, "")

ANCHOR = "chromahub.rhythm.app.features.online.PlaylistSheetHost(viewModel, currentRoute)"
if nav.count(ANCHOR) != 1:
    stop("LocalNavigation: PlaylistSheetHost найден %d раз (нужно 1)" % nav.count(ANCHOR))
if "YandexSheetHost" in nav:
    stop("патч уже применён")
nav = nav.replace(
    ANCHOR,
    ANCHOR + "\n        chromahub.rhythm.app.features.online.YandexSheetHost(viewModel, currentRoute)"
)

CODE = '''package chromahub.rhythm.app.features.online

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
'''

bk = os.path.expanduser("~/Desktop/yp_backup"); os.makedirs(bk, exist_ok=True)
shutil.copy2(SCREEN, os.path.join(bk, "OnlineScreen.kt"))
shutil.copy2(NAV, os.path.join(bk, "LocalNavigation.kt"))
open(SCREEN, "w", encoding="utf-8").write(scr)
open(NAV, "w", encoding="utf-8").write(nav)
open(NEWF, "w", encoding="utf-8").write(CODE)
print("Готово: плейлист Яндекса теперь выезжает как плеер. Копии: ~/Desktop/yp_backup")
