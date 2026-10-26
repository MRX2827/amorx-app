import os, re, sys, shutil

A = "app/src/main/java/chromahub/rhythm/app/"
O = A + "features/online/"
DL = O + "OnlineDownloads.kt"
SVC = O + "DownloadService.kt"
MAN = "app/src/main/AndroidManifest.xml"

for f in (DL, MAN):
    if not os.path.exists(f):
        print("СТОП: нет файла", f, "(запускайте из ~/Desktop/Rhythm)"); sys.exit(1)
if os.path.exists(SVC):
    print("СТОП: DownloadService.kt уже есть."); sys.exit(1)

d = open(DL, encoding="utf-8").read()
m = open(MAN, encoding="utf-8").read()

obj = re.search(r"^object\s+(\w+)", d, re.M)
pd = re.search(r"class\s+PendingDownload\s*\(\s*(?:val\s+)?(\w+)\s*:\s*[\w.]+\s*,\s*(?:val\s+)?(\w+)\s*:\s*Float", d)
if not obj or not pd:
    print("СТОП: не нашёл объект загрузок или класс PendingDownload (object=%s, pending=%s)." % (bool(obj), bool(pd)))
    print("Пришлите: sed -n '1,50p' %s | pbcopy" % DL)
    sys.exit(1)
OBJ, F_TRACK, F_PROG = obj.group(1), pd.group(1), pd.group(2)

def once(text, old, new, name):
    n = text.count(old)
    if n != 1:
        print("СТОП %s: найдено %d, ожидалось 1. Ничего не записано." % (name, n)); sys.exit(1)
    return text.replace(old, new)

d = once(d, "    private var dir: File? = null\n",
            "    private var dir: File? = null\n    private var appContext: Context? = null\n", "поле appContext")
d = once(d, "        dir = d\n",
            "        dir = d\n"
            "        appContext = context.applicationContext\n"
            "        // Недокачанные файлы после закрытия приложения больше не нужны\n"
            "        d.listFiles { f -> f.extension == \"part\" }?.forEach { it.delete() }\n", "init")
d = once(d, "        pending.update { it + (id to PendingDownload(track, 0f)) }\n",
            "        pending.update { it + (id to PendingDownload(track, 0f)) }\n"
            "        appContext?.let { DownloadService.start(it) }\n", "запуск сервиса")

if "DownloadService" in m:
    print("СТОП: сервис уже есть в манифесте."); sys.exit(1)
m = once(m, "</application>",
            "    <service\n"
            "        android:name=\"chromahub.rhythm.app.features.online.DownloadService\"\n"
            "        android:exported=\"false\"\n"
            "        android:foregroundServiceType=\"specialUse\">\n"
            "        <property\n"
            "            android:name=\"android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE\"\n"
            "            android:value=\"music_download\" />\n"
            "    </service>\n"
            "</application>", "манифест")

bk = os.path.expanduser("~/Desktop/download_backup")
os.makedirs(bk, exist_ok=True)
shutil.copy(DL, bk); shutil.copy(MAN, bk)

KT = '''package chromahub.rhythm.app.features.online

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Держит процесс живым, пока идут загрузки, и показывает прогресс в шторке. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val first = build(__OBJ__.pending.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, first)
        }
        if (job == null) {
            val nm = getSystemService(NotificationManager::class.java)
            job = scope.launch {
                __OBJ__.pending.collect { map ->
                    if (map.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        nm.notify(ID, build(map))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun build(map: Map<String, PendingDownload>): Notification {
        val list = map.values.toList()
        val title = when {
            list.isEmpty() -> "Загрузка"
            list.size == 1 -> list[0].__TRACK__.title
            else -> list[0].__TRACK__.title + " и ещё " + (list.size - 1)
        }
        val pct = if (list.isEmpty()) 0 else (list.map { it.__PROG__ }.average() * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Скачивание")
            .setContentText(title)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4711

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }
    }
}
'''.replace("__OBJ__", OBJ).replace("__TRACK__", F_TRACK).replace("__PROG__", F_PROG)

open(SVC, "w", encoding="utf-8").write(KT)
open(DL, "w", encoding="utf-8").write(d)
open(MAN, "w", encoding="utf-8").write(m)
print("Готово: сервис загрузок подключен (объект %s, поля %s/%s)" % (OBJ, F_TRACK, F_PROG))
print("Копии: ~/Desktop/download_backup")
