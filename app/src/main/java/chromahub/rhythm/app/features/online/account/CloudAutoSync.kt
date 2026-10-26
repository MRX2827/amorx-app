package chromahub.rhythm.app.features.online.account

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Отправляет копию в облако, когда приложение уходит в фон (не чаще раза в две минуты). */
object CloudAutoSync {
    private var started = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(app: Application) {
        // Только основной процесс: в процессе экрана ошибок настройки могут быть устаревшими.
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != app.packageName) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) {
                    scope.launch { CloudAccount.autoPush(app) { createCloudBackup(app) } }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
