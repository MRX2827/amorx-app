#!/usr/bin/env python3
# AmorX: кнопка «Скачать всё» в импортированном плейлисте.
# Запускать из корня проекта Rhythm (рядом с gradlew). Нужен применённый amorx_yandex_screen.py.
# Если что-то не сходится, ничего не записывает и пишет «СТОП».

import os
import shutil
import sys

BASE = "app/src/main/java/chromahub/rhythm/app/features/online"
YFILE = BASE + "/YandexImport.kt"
BACKUP = os.path.expanduser("~/Desktop/yandex_backup4")

if not os.path.isfile("gradlew") or not os.path.isfile(YFILE):
    print("СТОП: запустите скрипт из корня проекта Rhythm (рядом с gradlew)")
    sys.exit(1)

y = open(YFILE, encoding="utf-8").read()

EDITS = []

# 1. импорт
EDITS.append((
    "import kotlinx.coroutines.sync.withPermit\n",
    "import kotlinx.coroutines.sync.withPermit\nimport kotlinx.coroutines.withContext\n",
))

# 2. логика в object YandexImport
EDITS.append((
    "    val playlistOpen = MutableStateFlow(false)\n",
    r'''    val playlistOpen = MutableStateFlow(false)
    /** Сколько песен ещё осталось поставить на скачивание (0, если «Скачать всё» не запущено). */
    val downloading = MutableStateFlow(0)
    private var dlJob: Job? = null

    fun downloadAll() {
        if (dlJob?.isActive == true) return
        val queue = tracks.value.filter { t -> OnlineDownloads.items.value.none { it.id == t.id } }
        if (queue.isEmpty()) {
            message.value = "Все песни уже скачаны"
            return
        }
        dlJob = scope.launch {
            var left = queue.size
            downloading.value = left
            try {
                for (t in queue) {
                    while (OnlineDownloads.pending.value.size >= 3) delay(1000)
                    withContext(Dispatchers.Main) { OnlineDownloads.download(t) }
                    delay(300)
                    left--
                    downloading.value = left
                }
                message.value = "Все песни поставлены на скачивание"
            } finally {
                downloading.value = 0
            }
        }
    }

    fun stopDownloadAll() {
        dlJob?.cancel()
        downloading.value = 0
    }
''',
))

# 3. состояние и подтверждение в экране плейлиста
EDITS.append((
    "    var confirmDelete by remember { mutableStateOf(false) }\n",
    r'''    var confirmDelete by remember { mutableStateOf(false) }
    val dlLeft by YandexImport.downloading.collectAsState()
    var confirmDl by remember { mutableStateOf(false) }
    val toDownload = tracks.count { t -> downloaded.none { it.id == t.id } }

    if (confirmDl) {
        AlertDialog(
            onDismissRequest = { confirmDl = false },
            title = { Text("Скачать все песни?") },
            text = {
                Text(
                    "Будет скачано песен: " + toDownload +
                        ". Они займут место на устройстве, примерно по 5 МБ на песню."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDl = false
                    YandexImport.downloadAll()
                }) { Text("Скачать") }
            },
            dismissButton = { TextButton(onClick = { confirmDl = false }) { Text("Отмена") } }
        )
    }
''',
))

# 4. кнопка
OLD_BTN = '''                        FilledTonalButton(onClick = { playList(tracks.shuffled(), 0) }) {
                            Text("Вперемешку")
                        }
                    }
                    Spacer(Modifier.size(12.dp))
'''
NEW_BTN = '''                        FilledTonalButton(onClick = { playList(tracks.shuffled(), 0) }) {
                            Text("Вперемешку")
                        }
                    }
                    if (dlLeft > 0) {
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(onClick = { YandexImport.stopDownloadAll() }) {
                            Text("Остановить скачивание (" + dlLeft + ")")
                        }
                    } else if (toDownload > 0) {
                        Spacer(Modifier.size(8.dp))
                        FilledTonalButton(onClick = { confirmDl = true }) {
                            Text("Скачать всё (" + toDownload + ")")
                        }
                    }
                    Spacer(Modifier.size(12.dp))
'''
EDITS.append((OLD_BTN, NEW_BTN))

problems = []
if "YandexPlaylistScreen" not in y or "BackHandler" not in y:
    problems.append("сначала примените amorx_yandex_screen.py")
if "downloadAll" in y:
    problems.append("кнопка «Скачать всё» уже добавлена")
for old, _ in EDITS:
    c = y.count(old)
    if c != 1:
        problems.append("не найдено ровно одно место: %r (найдено %d)" % (old[:50], c))

if problems:
    print("СТОП. Ничего не записано. Проблемы:")
    for p in problems:
        print(" -", p)
    sys.exit(1)

os.makedirs(BACKUP, exist_ok=True)
shutil.copy2(YFILE, os.path.join(BACKUP, "YandexImport.kt"))
for old, new in EDITS:
    y = y.replace(old, new, 1)
open(YFILE, "w", encoding="utf-8").write(y)
print("Готово: кнопка «Скачать всё» добавлена. Копия: " + BACKUP)
