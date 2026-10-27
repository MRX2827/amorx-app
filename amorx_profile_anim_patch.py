#!/usr/bin/env python3
"""Профиль выбранного человека в «Найти людей» выезжает как плеер (PlayerLikeSheet)."""
import os, sys, shutil

ROOT = os.path.expanduser("/Users/mrx/Desktop/all backup and all files strings/Rhythm")
F = os.path.join(ROOT, "app/src/main/java/chromahub/rhythm/app/features/online/PeopleScreen.kt")
BK = os.path.expanduser("~/Desktop/people_backup")

def stop(msg):
    print("СТОП: " + msg); sys.exit(1)

if not os.path.isfile(F):
    stop("не найден " + F)
t = open(F, encoding="utf-8").read()
if "PersonProfileContent" in t:
    stop("патч уже применён")

def once(s, what):
    if t.count(s) != 1:
        stop(f"«{what}» найдено {t.count(s)} раз, нужно ровно 1. Ничего не записано.")

ELSE = "        } else {\n            item {\n                TextButton(onClick = { selected = null }"
END = "        }\n    }\n}\n\n@Composable\nprivate fun PeopleAvatar"
IFC = "        if (current == null) {\n"
CUR = "    val current = selected\n    val myId = CloudProfiles.myId()\n"
SHEET = "    PlayerLikeSheetFor(item = chatPeer) { p ->"
WRITE = "chatPeer = ChatPeer(current.id, current.username, current.avatarB64)"
BACK = "TextButton(onClick = { selected = null }"
for s, w in [(ELSE, "else-ветка"), (END, "конец функции"), (IFC, "if current == null"),
             (CUR, "val current"), (SHEET, "шторка чата"), (WRITE, "кнопка Написать"), (BACK, "К списку")]:
    once(s, w)

start = t.index(ELSE)
end = t.index(END)
body = t[start + len("        } else {\n"):end]
if WRITE not in body or BACK not in body:
    stop("тело профиля не совпало с ожидаемым")
body = body.replace(WRITE, "onWrite()").replace(BACK, "TextButton(onClick = onBack")

func = (
    "@Composable\n"
    "private fun PersonProfileContent(\n"
    "    current: RemoteProfile,\n"
    "    musicViewModel: MusicViewModel,\n"
    "    onBack: () -> Unit,\n"
    "    onWrite: () -> Unit\n"
    ") {\n"
    "    val context = LocalContext.current\n"
    "    val scope = rememberCoroutineScope()\n"
    "    val myId = CloudProfiles.myId()\n"
    "    var playingKey by remember { mutableStateOf<String?>(null) }\n"
    "    LazyColumn(\n"
    "        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),\n"
    "        contentPadding = PaddingValues(bottom = 180.dp)\n"
    "    ) {\n"
    + body +
    "    }\n"
    "}\n\n"
)

new = t[:start] + "        }\n    }\n}\n\n" + func + t[end + len(END) - len("@Composable\nprivate fun PeopleAvatar"):]
new = new.replace(IFC, "        run {\n", 1)
new = new.replace(CUR, "    val myId = CloudProfiles.myId()\n", 1)
new = new.replace(
    SHEET,
    "    PlayerLikeSheetFor(item = selected) { person ->\n"
    "        PersonProfileContent(\n"
    "            current = person,\n"
    "            musicViewModel = musicViewModel,\n"
    "            onBack = { selected = null },\n"
    "            onWrite = { chatPeer = ChatPeer(person.id, person.username, person.avatarB64) }\n"
    "        )\n"
    "    }\n" + SHEET, 1)

os.makedirs(BK, exist_ok=True)
shutil.copy(F, os.path.join(BK, "PeopleScreen.kt"))
open(F, "w", encoding="utf-8").write(new)
print("Готово: профиль в «Найти людей» теперь выезжает как плеер")
