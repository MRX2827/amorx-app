package chromahub.rhythm.app.features.online.account

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chromahub.rhythm.app.core.domain.backup.BackupRestoreManager
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.online.ArtworkUtil
import chromahub.rhythm.app.features.online.CloudSong
import chromahub.rhythm.app.features.online.OnlineDownloads
import chromahub.rhythm.app.features.online.OnlineTrack
import chromahub.rhythm.app.features.online.ProfileSongs
import chromahub.rhythm.app.features.online.ProfileStore
import chromahub.rhythm.app.features.online.WaveArtist
import chromahub.rhythm.app.features.online.WaveStore
import chromahub.rhythm.app.shared.data.model.AppSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val SECRET_MARKERS = listOf("secret", "password", "token", "api_key", "apikey")
private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.long(key: String): Long =
    get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

/** Убирает из копии всё, что похоже на ключи и пароли: в облако они уходить не должны. */
private fun stripSecrets(root: JsonObject) {
    for (section in listOf("preferences", "preferences_types", "stats_rhythm_guard_data", "stats_rhythm_guard_types")) {
        val obj = root.get(section)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val dead = obj.keySet().filter { k -> SECRET_MARKERS.any { k.contains(it, ignoreCase = true) } }
        dead.forEach { obj.remove(it) }
    }
}

// ---------------------------------------------------------------- профиль

private fun profileToJson(context: Context): JsonObject {
    ProfileStore.init(context)
    val o = JsonObject()
    o.addProperty("name", ProfileStore.name.value)
    o.addProperty("bio", ProfileStore.bio.value)
    o.addProperty("public", ProfileStore.isPublic.value)
    val path = ProfileStore.avatarPath.value
    if (path != null) {
        try {
            val f = File(path)
            if (f.exists() && f.length() < 400_000L) {
                o.addProperty("avatar", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
        } catch (_: Exception) {
        }
    }
    return o
}

private fun applyProfile(context: Context, o: JsonObject) {
    ProfileStore.init(context)
    val name = o.str("name").orEmpty()
    val bio = o.str("bio").orEmpty()
    if (name.isNotBlank() || bio.isNotBlank()) ProfileStore.save(name, bio)
    o.get("public")?.takeIf { it.isJsonPrimitive }?.let { ProfileStore.setPublic(it.asBoolean) }
    val b64 = o.str("avatar")
    if (!b64.isNullOrBlank()) {
        try {
            val f = File(context.cacheDir, "restored_avatar.jpg")
            f.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
            ProfileStore.setAvatar(Uri.fromFile(f))
        } catch (_: Exception) {
        }
    }
}

// ---------------------------------------------------------------- избранное, «Волна», скачанное

private fun onlineToJson(context: Context): JsonObject {
    ProfileSongs.init(context)
    WaveStore.init(context)
    OnlineDownloads.init(context)
    val o = JsonObject()

    val fav = JsonArray()
    ProfileSongs.songs.value.forEach { s ->
        val e = JsonObject()
        e.addProperty("id", s.id)
        e.addProperty("title", s.title)
        e.addProperty("artist", s.artist)
        if (s.artwork != null) e.addProperty("artwork", s.artwork)
        e.addProperty("dur", s.dur)
        fav.add(e)
    }
    o.add("favorites", fav)

    val wave = JsonArray()
    WaveStore.artists.value.forEach { a ->
        val e = JsonObject()
        e.addProperty("name", a.name)
        if (a.avatarUrl != null) e.addProperty("avatar", a.avatarUrl)
        wave.add(e)
    }
    o.add("wave", wave)

    // Файлы не загружаем: сохраняем только список, на новом телефоне треки докачаются
    val dl = JsonArray()
    OnlineDownloads.items.value.forEach { t ->
        val e = JsonObject()
        e.addProperty("videoUrl", t.videoUrl)
        e.addProperty("title", t.title)
        e.addProperty("artist", t.artist)
        e.addProperty("dur", t.durationMs)
        ArtworkUtil.forSong("online_" + t.id, null)?.let { e.addProperty("artwork", it) }
        dl.add(e)
    }
    o.add("downloads", dl)
    return o
}

private fun applyOnline(context: Context, o: JsonObject) {
    ProfileSongs.init(context)
    WaveStore.init(context)
    OnlineDownloads.init(context)

    val fav = o.get("favorites")?.takeIf { it.isJsonArray }?.asJsonArray
    if (fav != null) {
        val list = fav.mapNotNull { el ->
            val x = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = x.str("id") ?: return@mapNotNull null
            CloudSong(id, x.str("title").orEmpty(), x.str("artist").orEmpty(), x.str("artwork"), x.long("dur"))
        }
        if (list.isNotEmpty()) ProfileSongs.replaceAll(list)
    }

    val wave = o.get("wave")?.takeIf { it.isJsonArray }?.asJsonArray
    if (wave != null) {
        val list = wave.mapNotNull { el ->
            val x = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = x.str("name") ?: return@mapNotNull null
            WaveArtist(name, x.str("avatar"))
        }
        if (list.isNotEmpty()) {
            WaveStore.artists.value.map { it.name }.forEach { WaveStore.remove(it) }
            list.forEach { WaveStore.add(it) }
        }
    }

    val dl = o.get("downloads")?.takeIf { it.isJsonArray }?.asJsonArray
    if (dl != null) {
        val tracks = dl.mapNotNull { el ->
            val x = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val url = x.str("videoUrl") ?: return@mapNotNull null
            OnlineTrack(url, x.str("title").orEmpty(), x.str("artist").orEmpty(), x.long("dur"), x.str("artwork"))
        }
        restoreDownloads(tracks)
    }
}

/** Докачивает треки по два за раз, чтобы не забить сеть сотней одновременных загрузок. */
private fun restoreDownloads(tracks: List<OnlineTrack>) {
    if (tracks.isEmpty()) return
    restoreScope.launch {
        for (t in tracks) {
            if (OnlineDownloads.items.value.any { it.id == t.id }) continue
            var waited = 0
            while (OnlineDownloads.pending.value.size >= 2 && waited < 400) {
                delay(1500)
                waited++
            }
            try {
                OnlineDownloads.download(t)
            } catch (_: Exception) {
            }
            delay(300)
        }
    }
}

// ---------------------------------------------------------------- копия для облака

private fun managerFor(context: Context): BackupRestoreManager {
    val app = context.applicationContext
    return BackupRestoreManager(app, AppSettings.getInstance(app), RhythmDatabase.getInstance(app))
}

/** Копия для облака: настройки, плейлисты, статистика, профиль, избранное, «Волна», список скачанного. Без ключей и паролей. */
internal suspend fun createCloudBackup(context: Context, manager: BackupRestoreManager = managerFor(context)): String {
    val root = JsonParser.parseString(manager.createBackupPayload()).asJsonObject
    stripSecrets(root)
    root.add("amorx_profile", profileToJson(context))
    root.add("amorx_online", onlineToJson(context))
    return root.toString()
}

internal suspend fun restoreCloudBackup(
    context: Context,
    payload: String,
    manager: BackupRestoreManager = managerFor(context)
): Boolean {
    val root = try {
        JsonParser.parseString(payload).asJsonObject
    } catch (_: Exception) {
        return false
    }
    val profile = root.remove("amorx_profile")
    val online = root.remove("amorx_online")
    val ok = manager.restoreFromBackupPayload(root.toString())
    if (ok) {
        if (profile != null && profile.isJsonObject) applyProfile(context, profile.asJsonObject)
        if (online != null && online.isJsonObject) applyOnline(context, online.asJsonObject)
    }
    return ok
}

// ---------------------------------------------------------------- экраны

/** Экран «Аккаунт» для раздела настроек. */
@Composable
fun AccountSettingsHost(onBackClick: () -> Unit, appSettings: AppSettings) {
    val app = LocalContext.current.applicationContext
    val manager = remember { BackupRestoreManager(app, appSettings, RhythmDatabase.getInstance(app)) }
    AccountScreen(
        onBackClick = onBackClick,
        createBackup = { createCloudBackup(app, manager) },
        restoreBackup = { restoreCloudBackup(app, it, manager) }
    )
}

/** Тот же экран поверх всего приложения: открывается из профиля. */
@Composable
fun AccountDialogHost(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val manager = remember { managerFor(app) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AccountScreen(
                onBackClick = onDismiss,
                createBackup = { createCloudBackup(app, manager) },
                restoreBackup = { restoreCloudBackup(app, it, manager) }
            )
        }
    }
}

/** Карточка на экране профиля: показывает, вошли ли вы, и открывает экран аккаунта. */
@Composable
fun AccountEntryCard(onClick: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<AccountInfo?>(null) }
    LaunchedEffect(Unit) { CloudAccount.info(context).onSuccess { info = it } }
    val cur = info
    val member = cur != null && !cur.isGuest
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (member) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (member) "Аккаунт: ${cur?.email.orEmpty()}" else "Создайте аккаунт",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (member) "Данные сохраняются в облако. Нажмите, чтобы управлять"
                    else "Войдите или создайте аккаунт: профиль, плейлисты и скачанное восстановятся на любом телефоне",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onClick) { Text(if (member) "Открыть" else "Войти") }
        }
    }
}

/** Перезапускает приложение, чтобы восстановленные настройки и база применились целиком. */
internal fun restartApp(context: Context) {
    val app = context.applicationContext
    val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
    app.startActivity(intent)
    Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 500)
}

/**
 * Окно входа поверх всего приложения. Закрыть его нельзя: плеер открывается только после входа
 * или регистрации. Появляется при запуске без входа и после выхода из аккаунта.
 * Без интернета пускает внутрь только тех, кто уже входил на этом телефоне.
 */
@Composable
fun AccountGate() {
    val context = LocalContext.current
    val app = context.applicationContext
    val required by CloudAccount.gateRequired.collectAsState()
    LaunchedEffect(Unit) { CloudAccount.checkGate(context) }
    if (required) {
        val manager = remember { managerFor(app) }
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AccountScreen(
                    onBackClick = {},
                    createBackup = { createCloudBackup(app, manager) },
                    restoreBackup = { restoreCloudBackup(app, it, manager) },
                    gate = true,
                    onFinished = { CloudAccount.gatePassed() }
                )
            }
        }
    }
}
