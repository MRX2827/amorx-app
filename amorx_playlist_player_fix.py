import sys, os, shutil
P = "app/src/main/java/chromahub/rhythm/app/features/online/PlaylistUi.kt"
def stop(m):
    print("СТОП " + m + ". Ничего не записано."); sys.exit(1)
s = open(P, encoding="utf-8").read()
OLD = '''    val open by PlaylistNav.open
    LaunchedEffect(route) {
        if (route != null && route != "online") PlaylistNav.open.value = false
    }
    BackHandler(enabled = open) { PlaylistNav.open.value = false }
    PlayerLikeSheet(visible = open) {'''
NEW = '''    val open by PlaylistNav.open
    val onPlayer = route == "player"
    LaunchedEffect(route) {
        if (route != null && route != "online" && route != "player") PlaylistNav.open.value = false
    }
    BackHandler(enabled = open && !onPlayer) { PlaylistNav.open.value = false }
    PlayerLikeSheet(visible = open && !onPlayer) {'''
if s.count(OLD) != 1: stop("блок PlaylistSheetHost найден %d раз (нужно 1)" % s.count(OLD))
bk = os.path.expanduser("~/Desktop/pl_fix_backup"); os.makedirs(bk, exist_ok=True)
shutil.copy2(P, os.path.join(bk, "PlaylistUi.kt"))
open(P, "w", encoding="utf-8").write(s.replace(OLD, NEW))
print("Готово: плейлист больше не закрывается при открытии плеера")
