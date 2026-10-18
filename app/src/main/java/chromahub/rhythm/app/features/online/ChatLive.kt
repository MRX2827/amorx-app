package chromahub.rhythm.app.features.online

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import chromahub.rhythm.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

/**
 * Постоянный слушатель новых сообщений: раз в секунду спрашивает сервер.
 * Приложение открыто: баннер сверху. Приложение свёрнуто: системное уведомление.
 */
object ChatNotifier {
    const val POLL_MS = 1000L
    private const val PREFS = "amorx_chat_notify"
    private const val CH_MESSAGES = "amorx_chat_messages"
    private const val CH_SYNC = "amorx_chat_sync"
    const val SYNC_ID = 7421

    data class Incoming(val id: Long, val fromId: String, val fromName: String, val text: String)

    val unread = MutableStateFlow(0)
    val version = MutableStateFlow(0)
    val incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 8)
    val backgroundEnabled = MutableStateFlow(true)
    val openChats = MutableStateFlow(false)

    @Volatile var activePeer: String? = null
    @Volatile var serviceRunning = false
    @Volatile private var visibleActivities = 0
    private var app: Context? = null
    private var job: Job? = null
    private val names = HashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs() = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val isForeground: Boolean get() = visibleActivities > 0

    @Synchronized
    fun init(context: Context, fromUi: Boolean) {
        if (app != null) return
        val a = context.applicationContext
        app = a
        CloudProfiles.init(a)
        backgroundEnabled.value = prefs().getBoolean("bg", true)
        (a as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { visibleActivities++ }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) { if (visibleActivities > 0) visibleActivities-- }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        visibleActivities = if (fromUi) 1 else 0
    }

    @Synchronized
    fun start(context: Context) {
        init(context, false)
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun askedPermission(): Boolean = prefs().getBoolean("asked_perm", false)
    fun markAsked() { prefs().edit().putBoolean("asked_perm", true).apply() }

    fun setBackground(context: Context, enabled: Boolean) {
        init(context, true)
        prefs().edit().putBoolean("bg", enabled).apply()
        backgroundEnabled.value = enabled
        if (enabled) startService(context)
        else runCatching { context.stopService(Intent(context, ChatSyncService::class.java)) }
    }

    fun startService(context: Context) {
        if (serviceRunning) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ChatSyncService::class.java))
        }
    }

    private suspend fun loop() {
        var tick = 0
        var fails = 0
        while (true) {
            try {
                pollOnce(tick % 3 == 0)
                fails = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fails++
            }
            tick++
            delay(if (fails > 5) 5000L else POLL_MS)
        }
    }

    private fun get(path: String, token: String): Pair<Int, String> =
        CloudProfiles.exec(
            Request.Builder()
                .url(SupabaseConfig.URL + path)
                .header("apikey", SupabaseConfig.KEY)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        )

    private suspend fun pollOnce(refreshUnread: Boolean) {
        val ctx = app ?: return
        if (CloudProfiles.myId() == null) return
        val (token, me) = CloudProfiles.session()
        val p = prefs()
        var last = p.getLong("last_$me", -1L)

        if (last < 0) {
            val (c, b) = get("/rest/v1/messages?select=id&recipient_id=eq.$me&order=id.desc&limit=1", token)
            if (c == 404) return
            if (c !in 200..299) throw IOException("code $c")
            val a = JSONArray(b)
            last = if (a.length() > 0) a.getJSONObject(0).getLong("id") else 0L
            p.edit().putLong("last_$me", last).apply()
            CloudChat.unreadTotal().onSuccess { unread.value = it }
            return
        }

        val (code, body) = get(
            "/rest/v1/messages?select=id,sender_id,body,track&recipient_id=eq.$me&id=gt.$last&order=id.asc&limit=20",
            token
        )
        if (code == 404) return
        if (code !in 200..299) throw IOException("code $code")
        val arr = JSONArray(body)
        if (arr.length() > 0) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                val from = o.getString("sender_id")
                val text = buildString {
                    val t = o.optJSONObject("track")
                    if (t != null) append("♪ ").append(t.optString("title"))
                    val txt = o.optString("body")
                    if (txt.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(txt)
                    }
                }
                last = id
                deliver(ctx, id, from, nameOf(from, token), text)
            }
            p.edit().putLong("last_$me", last).apply()
            version.value = version.value + 1
            CloudChat.unreadTotal().onSuccess { unread.value = it }
        } else if (refreshUnread) {
            CloudChat.unreadTotal().onSuccess { unread.value = it }
        }
    }

    private fun nameOf(id: String, token: String): String {
        names[id]?.let { return it }
        return try {
            val (c, b) = get("/rest/v1/profiles?select=username&id=eq.$id", token)
            if (c in 200..299) {
                val a = JSONArray(b)
                if (a.length() > 0) {
                    val n = a.getJSONObject(0).optString("username")
                    if (n.isNotBlank()) {
                        names[id] = n
                        return n
                    }
                }
            }
            "Новое сообщение"
        } catch (e: Exception) {
            "Новое сообщение"
        }
    }

    private fun deliver(ctx: Context, id: Long, fromId: String, name: String, text: String) {
        val foreground = isForeground
        if (foreground && activePeer == fromId) return
        if (foreground) incoming.tryEmit(Incoming(id, fromId, name, text))
        else notifySystem(ctx, fromId, name, text)
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (nm.getNotificationChannel(CH_SYNC) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_SYNC, "Работа в фоне", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun openApp(ctx: Context): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notifySystem(ctx: Context, fromId: String, name: String, text: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(ctx))
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(fromId.hashCode(), n)
    }

    fun syncNotification(ctx: Context): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AmorX")
            .setContentText("Сообщения включены")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(ctx))
            .build()
    }
}

/** Держит процесс живым, чтобы сообщения приходили при закрытом приложении. */
class ChatSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ChatNotifier.init(applicationContext, false)
        val n = ChatNotifier.syncNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ChatNotifier.SYNC_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ChatNotifier.SYNC_ID, n)
            }
        } catch (e: Exception) {
            ChatNotifier.serviceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        ChatNotifier.serviceRunning = true
        ChatNotifier.start(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        ChatNotifier.serviceRunning = false
        super.onDestroy()
    }
}

/** Живёт во всём приложении: запускает слушатель, службу, спрашивает разрешение и рисует баннер. */
@Composable
fun ChatLiveHost(onOpenChats: () -> Unit) {
    val context = LocalContext.current
    remember { ChatNotifier.init(context, true); true }
    val bgEnabled by ChatNotifier.backgroundEnabled.collectAsState()
    var banner by remember { mutableStateOf<ChatNotifier.Incoming?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { ChatNotifier.start(context) }

    LaunchedEffect(bgEnabled) {
        while (true) {
            if (CloudProfiles.myId() != null) {
                if (Build.VERSION.SDK_INT >= 33 && !ChatNotifier.askedPermission() &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ChatNotifier.markAsked()
                    permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (bgEnabled && !ChatNotifier.serviceRunning) ChatNotifier.startService(context)
            }
            delay(5000)
        }
    }

    LaunchedEffect(Unit) { ChatNotifier.incoming.collect { banner = it } }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(4500)
            banner = null
        }
    }

    val b = banner
    if (b != null) {
        Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
            Card(
                modifier = Modifier
                    .padding(top = 48.dp, start = 12.dp, end = 12.dp)
                    .widthIn(max = 480.dp)
                    .clickable {
                        banner = null
                        ChatNotifier.openChats.value = true
                        onOpenChats()
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        b.fromName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        b.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Переключатель фоновых уведомлений для экрана профиля. */
@Composable
fun ChatBackgroundSettings() {
    val context = LocalContext.current
    remember { ChatNotifier.init(context, true); true }
    val enabled by ChatNotifier.backgroundEnabled.collectAsState()
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Сообщения в фоне", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Уведомления приходят, даже когда приложение закрыто. В шторке будет тихое постоянное уведомление",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = { ChatNotifier.setBackground(context, it) })
        }
        if (enabled) {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("Не усыплять приложение в фоне") }
        }
    }
}
