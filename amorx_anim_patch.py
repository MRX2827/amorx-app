import os, sys, shutil

A = "app/src/main/java/chromahub/rhythm/app/"
O = A + "features/online/"
PS, PE, CS = O + "ProfileScreen.kt", O + "PeopleScreen.kt", O + "ChatScreens.kt"
NEW = O + "ProfileSheets.kt"

if not os.path.isdir(O):
    print("СТОП: запускайте из папки ~/Desktop/Rhythm. Ничего не записано."); sys.exit(1)
if os.path.exists(NEW):
    print("СТОП: файл уже существует: " + NEW + ". Ничего не записано."); sys.exit(1)

errors = []
def rd(p):
    try:
        return open(p, encoding="utf-8").read()
    except FileNotFoundError:
        errors.append("нет файла " + p); return ""
def once(t, old, new, name):
    n = t.count(old)
    if n != 1:
        errors.append("%s: найдено %d, ожидалось 1" % (name, n)); return t
    return t.replace(old, new)

SHEETS_KT = r'''package chromahub.rhythm.app.features.online

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
    val chats: MutableState<Boolean> = mutableStateOf(false)
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

/** Профиль остаётся на месте, «Найти людей» и «Сообщения» выезжают поверх него. */
@Composable
fun ProfileScreen(
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onDispose {
            ProfileNavState.people.value = false
            ProfileNavState.chats.value = false
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
        PlayerLikeSheet(visible = ProfileNavState.chats.value) {
            ChatsScreen(
                musicViewModel = musicViewModel,
                onBack = { ProfileNavState.chats.value = false }
            )
        }
    }
}
'''

# ---------- ProfileScreen.kt ----------
ps = rd(PS)
if "ProfileScreenBody" in ps:
    errors.append("ProfileScreen.kt: уже изменён (есть ProfileScreenBody)")
ps = once(ps, "fun ProfileScreen(", "fun ProfileScreenBody(", "ProfileScreen: переименование")
ps = once(ps, "    var showChats by remember { mutableStateOf(false) }\n",
          "    var showChats by remember { ProfileNavState.chats }\n", "ProfileScreen: состояние чатов")
ps = once(ps, "    var showPeople by remember { mutableStateOf(false) }\n",
          "    var showPeople by remember { ProfileNavState.people }\n", "ProfileScreen: состояние людей")
ps = once(ps,
    "    if (showChats) {\n"
    "        ChatsScreen(musicViewModel = musicViewModel, onBack = { showChats = false })\n"
    "        return\n"
    "    }\n", "", "ProfileScreen: блок чатов")
ps = once(ps,
    "    if (showPeople) {\n"
    "        PeopleScreen(musicViewModel = musicViewModel, onBack = { showPeople = false })\n"
    "        return\n"
    "    }\n", "", "ProfileScreen: блок людей")

# ---------- ChatScreens.kt ----------
cs = rd(CS)
cs = once(cs,
    "    val peer = openPeer\n"
    "    if (peer != null) {\n"
    "        ThreadScreen(\n"
    "            musicViewModel = musicViewModel,\n"
    "            peer = peer,\n"
    "            onBack = { openPeer = null },\n"
    "            modifier = modifier\n"
    "        )\n"
    "        return\n"
    "    }\n",
    "    PlayerLikeSheetFor(item = openPeer) { p ->\n"
    "        ThreadScreen(\n"
    "            musicViewModel = musicViewModel,\n"
    "            peer = p,\n"
    "            onBack = { openPeer = null }\n"
    "        )\n"
    "    }\n", "ChatScreens: диалог")

# ---------- PeopleScreen.kt ----------
pe = rd(PE)
pe = once(pe,
    "    val openChat = chatPeer\n"
    "    if (openChat != null) {\n"
    "        ThreadScreen(musicViewModel = musicViewModel, peer = openChat, onBack = { chatPeer = null })\n"
    "        return\n"
    "    }\n",
    "    PlayerLikeSheetFor(item = chatPeer) { p ->\n"
    "        ThreadScreen(musicViewModel = musicViewModel, peer = p, onBack = { chatPeer = null })\n"
    "    }\n", "PeopleScreen: диалог")
pe = once(pe, "    BackHandler { if (selected != null) selected = null else onBack() }",
          "    BackHandler(enabled = chatPeer == null) { if (selected != null) selected = null else onBack() }",
          "PeopleScreen: кнопка «Назад»")

if errors:
    print("СТОП. Ничего не записано. Проблемы (%d):" % len(errors))
    for e in errors: print("  - " + e)
    sys.exit(1)

bk = os.path.expanduser("~/Desktop/anim_backup")
os.makedirs(bk, exist_ok=True)
for p in (PS, PE, CS):
    shutil.copy(p, os.path.join(bk, os.path.basename(p)))

for p, t in ((PS, ps), (PE, pe), (CS, cs), (NEW, SHEETS_KT)):
    open(p, "w", encoding="utf-8").write(t)
print("Готово: анимации как у плеера добавлены. Копии: ~/Desktop/anim_backup")
