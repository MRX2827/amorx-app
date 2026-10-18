#!/usr/bin/env python3
import os, sys, shutil
ROOT = os.getcwd()
CS = os.path.join(ROOT, "app/src/main/java/chromahub/rhythm/app/features/online/ChatScreens.kt")
if not os.path.exists(CS):
    print("СТОП ChatScreens.kt не найден. Запускайте из ~/Desktop/Rhythm. Ничего не записано."); sys.exit(1)
s = open(CS, encoding="utf-8").read()

if "AMORX_KBD_SMOOTH" in s:
    print("СТОП патч уже применён. Ничего не записано."); sys.exit(1)

EDITS = [
 ("режим окна",
  "android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or",
  "android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or"),
 ("чтение клавиатуры в композиции",
  "    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)\n",
  "    // AMORX_KBD_SMOOTH: высота клавиатуры читается на этапе layout, без перекомпоновки на каждом кадре\n    val imeInsets = WindowInsets.ime\n"),
 ("отступ под полем ввода",
  "                .padding(top = 8.dp, bottom = if (imeBottom > 0) 8.dp else 96.dp),\n",
  "                .padding(top = 8.dp)\n"
  "                .layout { measurable, constraints ->\n"
  "                    val reserve = 96.dp.roundToPx()\n"
  "                    val minPad = 8.dp.roundToPx()\n"
  "                    val extra = (reserve - imeInsets.getBottom(this)).coerceAtLeast(minPad)\n"
  "                    val p = measurable.measure(constraints.offset(vertical = -extra))\n"
  "                    layout(p.width, p.height + extra) { p.placeRelative(0, 0) }\n"
  "                },\n"),
]
for name, old, new in EDITS:
    n = s.count(old)
    if n != 1:
        print(f"СТОП место «{name}» найдено {n} раз (нужно 1). Ничего не записано."); sys.exit(1)
for name, old, new in EDITS:
    s = s.replace(old, new)

need = ["import androidx.compose.ui.layout.layout", "import androidx.compose.ui.unit.offset"]
lines = s.split("\n")
idx = next((i for i, l in enumerate(lines) if l.startswith("import ")), None)
if idx is None:
    print("СТОП не нашёл импорты. Ничего не записано."); sys.exit(1)
for imp in need:
    if imp not in s and imp.rsplit(".",1)[0] + ".*" not in s:
        lines.insert(idx, imp)
s = "\n".join(lines)

bk = os.path.expanduser("~/Desktop/kbd_backup"); os.makedirs(bk, exist_ok=True)
shutil.copy2(CS, os.path.join(bk, "ChatScreens.kt"))
open(CS, "w", encoding="utf-8").write(s)
print("Готово: панель ввода привязана к клавиатуре без сдвига окна. Копия: ~/Desktop/kbd_backup")
