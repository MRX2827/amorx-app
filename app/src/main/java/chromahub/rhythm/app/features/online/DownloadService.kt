package chromahub.rhythm.app.features.online

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
        val first = build(OnlineDownloads.pending.value)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, first)
        }
        if (job == null) {
            val nm = getSystemService(NotificationManager::class.java)
            job = scope.launch {
                OnlineDownloads.pending.collect { map ->
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
            list.size == 1 -> list[0].track.title
            else -> list[0].track.title + " и ещё " + (list.size - 1)
        }
        val pct = if (list.isEmpty()) 0 else (list.map { it.progress }.average() * 100).toInt().coerceIn(0, 100)
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
