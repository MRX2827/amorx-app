import os, sys, shutil

CS = "app/src/main/java/chromahub/rhythm/app/features/online/ChatScreens.kt"

if not os.path.isfile(CS):
    print("СТОП: запускайте из папки ~/Desktop/Rhythm (нет " + CS + "). Ничего не записано."); sys.exit(1)

t = open(CS, encoding="utf-8").read()
if "ADJUST_RESIZE" in t:
    print("СТОП: исправление уже применено. Ничего не записано."); sys.exit(1)

anchor = "    val listState = rememberLazyListState()\n"
if t.count(anchor) != 1:
    print("СТОП: ThreadScreen: найдено %d мест для вставки, ожидалось 1. Ничего не записано." % t.count(anchor)); sys.exit(1)

FIX = anchor + (
    "\n"
    "    // Пока открыт диалог, окно не двигается системой: панель ввода поднимает только imePadding\n"
    "    val hostView = androidx.compose.ui.platform.LocalView.current\n"
    "    DisposableEffect(hostView) {\n"
    "        val activity = generateSequence(hostView.context) { c -> (c as? android.content.ContextWrapper)?.baseContext }\n"
    "            .firstOrNull { c -> c is android.app.Activity } as? android.app.Activity\n"
    "        val window = activity?.window\n"
    "        val old = window?.attributes?.softInputMode\n"
    "        if (window != null && old != null) {\n"
    "            window.setSoftInputMode(\n"
    "                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or\n"
    "                    (old and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)\n"
    "            )\n"
    "        }\n"
    "        onDispose {\n"
    "            if (window != null && old != null) window.setSoftInputMode(old)\n"
    "        }\n"
    "    }\n"
)

bk = os.path.expanduser("~/Desktop/ime_backup")
os.makedirs(bk, exist_ok=True)
shutil.copy(CS, os.path.join(bk, os.path.basename(CS)))
open(CS, "w", encoding="utf-8").write(t.replace(anchor, FIX))
print("Готово: панель ввода больше не прыгает. Копия: ~/Desktop/ime_backup")
