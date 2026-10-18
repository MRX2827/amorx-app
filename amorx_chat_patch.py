import re, sys, os, shutil

A = "app/src/main/java/chromahub/rhythm/app/"
O = A + "features/online/"
B = A + "shared/presentation/"
CP, PS, PE = O + "CloudProfiles.kt", O + "ProfileScreen.kt", O + "PeopleScreen.kt"
EX = B + "components/bottomsheets/ExtraControlBottomSheet.kt"
PL = B + "screens/player/PlayerScreen.kt"
NAV = A + "features/local/presentation/navigation/LocalNavigation.kt"
MAN = "app/src/main/AndroidManifest.xml"
NEWF = {O + "ProfileSongs.kt": None, O + "CloudChat.kt": None, O + "ChatScreens.kt": None, O + "ChatLive.kt": None}

if not os.path.isdir(O):
    print("СТОП: запускайте из папки ~/Desktop/Rhythm. Ничего не записано."); sys.exit(1)
for p in NEWF:
    if os.path.exists(p):
        print("СТОП: файл уже существует: " + p + ". Ничего не записано."); sys.exit(1)

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
def rx(t, pat, fn, name):
    new, n = re.subn(pat, fn, t)
    if n != 1:
        errors.append("%s: найдено %d, ожидалось 1" % (name, n)); return t
    return new

SONGS_KT = r'''package chromahub.rhythm.app.features.online

import android.content.Context
import android.widget.Toast
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object ArtworkUtil {
    fun videoId(id: String): String? = when {
        id.startsWith("online_") -> id.removePrefix("online_")
        id.startsWith("offline_") -> id.removePrefix("offline_")
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** Обложка, которую увидит другой телефон: только http(s). Для песен YouTube берём миниатюру по id видео. */
    fun forSong(id: String, artwork: String?): String? {
        if (artwork != null && artwork.startsWith("http")) return artwork
        val vid = videoId(id) ?: return null
        return "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
    }
}

/** Любимые песни профиля. Хранятся на телефоне, не зависят от лайков. */
object ProfileSongs {
    private const val PREFS = "amorx_profile_songs"
    private const val KEY = "songs"
    private const val MAX = 50

    val songs = MutableStateFlow<List<CloudSong>>(emptyList())
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playing = false

    fun normId(id: String): String =
        if (id.startsWith("offline_")) "online_" + id.removePrefix("offline_") else id

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        songs.value = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                CloudSong(
                    o.getString("id"),
                    o.optString("title"),
                    o.optString("artist"),
                    if (o.isNull("artwork")) null else o.optString("artwork"),
                    o.optLong("dur", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val ctx = appContext ?: return
        val a = JSONArray()
        songs.value.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("artist", it.artist)
                    .put("artwork", it.artwork ?: JSONObject.NULL)
                    .put("dur", it.dur)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun add(song: Song) {
        val id = normId(song.id)
        if (songs.value.any { it.id == id }) return
        val entry = CloudSong(
            id, song.title, song.artist,
            ArtworkUtil.forSong(song.id, song.artworkUri?.toString()),
            song.duration
        )
        songs.value = (listOf(entry) + songs.value).take(MAX)
        save()
    }

    fun remove(id: String) {
        val n = normId(id)
        songs.value = songs.value.filter { it.id != n }
        save()
    }

    /** Один раз переносит прежние лайки в список профиля. */
    fun migrateFromLikes(liked: List<Song>) {
        val ctx = appContext ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("migrated", false) || liked.isEmpty()) return
        liked.reversed().forEach { add(it) }
        p.edit().putBoolean("migrated", true).apply()
    }

    /** Скачанная песня играет с телефона, остальные берутся из YouTube Music. */
    fun play(context: Context, vm: MusicViewModel, song: CloudSong) {
        if (playing) return
        OnlineDownloads.init(context)
        val vid = ArtworkUtil.videoId(song.id)
        val local = vid?.let { v -> OnlineDownloads.items.value.firstOrNull { it.id == v } }
        if (local != null) {
            vm.playSong(local.toSong())
            return
        }
        playing = true
        scope.launch {
            val resolved = runCatching { CloudProfiles.resolveSong(song) }
            playing = false
            resolved.onSuccess { vm.playSong(it) }
                .onFailure {
                    Toast.makeText(context.applicationContext, "Не удалось найти песню", Toast.LENGTH_SHORT).show()
                }
        }
    }
}

/** Песня, которой хотят поделиться из плеера. Диалог выбора рисуется в ShareToChatHost. */
object ChatShare {
    val pending = MutableStateFlow<CloudSong?>(null)

    fun request(song: Song) {
        pending.value = CloudSong(
            ProfileSongs.normId(song.id), song.title, song.artist,
            ArtworkUtil.forSong(song.id, song.artworkUri?.toString()),
            song.duration
        )
    }
}
'''

CHAT_KT = r'''package chromahub.rhythm.app.features.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChatPeer(val id: String, val name: String, val avatarB64: String?)
data class ChatMessage(
    val id: Long,
    val fromMe: Boolean,
    val body: String,
    val track: CloudSong?,
    val at: Long,
    val read: Boolean
)
data class Conversation(val peer: ChatPeer, val last: ChatMessage, val unread: Int)

/** Сообщения между пользователями (Supabase, таблица messages). */
object CloudChat {
    @Volatile
    private var ensured = false

    private fun req(path: String, token: String): Request.Builder =
        Request.Builder()
            .url(SupabaseConfig.URL + path)
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer $token")

    private fun parseTime(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time ?: 0L
    }.getOrDefault(0L)

    private fun parseMessage(o: JSONObject, me: String): ChatMessage {
        val t = if (o.isNull("track")) null else o.optJSONObject("track")?.let { x ->
            CloudSong(
                x.optString("id"),
                x.optString("title"),
                x.optString("artist"),
                if (x.isNull("artwork")) null else x.optString("artwork").ifBlank { null },
                x.optLong("dur", 0L)
            )
        }
        return ChatMessage(
            o.getLong("id"),
            o.getString("sender_id") == me,
            o.optString("body"),
            t,
            parseTime(o.optString("created_at")),
            !o.isNull("read_at")
        )
    }

    /** Чтобы писать, у вас должна быть строка профиля на сервере (скрытая, пока вы её не опубликуете). */
    suspend fun ensureProfile(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ensured) return@runCatching
            val (token, uid) = CloudProfiles.session()
            val body = JSONObject().put("id", uid).put("username", name.trim())
            val r = req("/rest/v1/profiles", token)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(body.toString().toRequestBody(CloudProfiles.JSON_TYPE))
                .build()
            val (code, resp) = CloudProfiles.exec(r)
            if (code !in 200..299) {
                throw IOException(
                    if (resp.contains("23505")) "Это имя уже занято, смените его в профиле"
                    else "Не удалось создать профиль ($code)"
                )
            }
            ensured = true
        }
    }

    suspend fun send(toId: String, body: String, track: CloudSong?, myName: String): Result<Unit> {
        val ok = ensureProfile(myName)
        if (ok.isFailure) return ok
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = CloudProfiles.session()
                val o = JSONObject()
                    .put("sender_id", uid)
                    .put("recipient_id", toId)
                    .put("body", body.trim())
                if (track != null) {
                    o.put(
                        "track",
                        JSONObject()
                            .put("id", track.id)
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("artwork", track.artwork ?: JSONObject.NULL)
                            .put("dur", track.dur)
                    )
                }
                val r = req("/rest/v1/messages", token)
                    .header("Prefer", "return=minimal")
                    .post(o.toString().toRequestBody(CloudProfiles.JSON_TYPE))
                    .build()
                val (code, resp) = CloudProfiles.exec(r)
                if (code !in 200..299) {
                    throw IOException(
                        when {
                            code == 404 -> "Таблица messages не найдена: выполните SQL чатов в Supabase"
                            resp.contains("23503") -> "Получателя нет на сервере"
                            else -> "Не удалось отправить ($code)"
                        }
                    )
                }
                Unit
            }
        }
    }

    suspend fun thread(peerId: String): Result<List<ChatMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, me) = CloudProfiles.session()
            val f = "or=(and(sender_id.eq.$me,recipient_id.eq.$peerId),and(sender_id.eq.$peerId,recipient_id.eq.$me))"
            val r = req("/rest/v1/messages?select=*&$f&order=created_at.desc&limit=200", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code !in 200..299) throw IOException("Не удалось загрузить чат ($code)")
            val a = JSONArray(body)
            (0 until a.length()).map { parseMessage(a.getJSONObject(it), me) }.reversed()
        }
    }

    suspend fun markRead(peerId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val (token, me) = CloudProfiles.session()
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
            val r = req("/rest/v1/messages?recipient_id=eq.$me&sender_id=eq.$peerId&read_at=is.null", token)
                .header("Prefer", "return=minimal")
                .patch(JSONObject().put("read_at", now).toString().toRequestBody(CloudProfiles.JSON_TYPE))
                .build()
            CloudProfiles.exec(r)
        }
        Unit
    }

    suspend fun unreadTotal(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (CloudProfiles.myId() == null) return@runCatching 0
            val (token, me) = CloudProfiles.session()
            val r = req("/rest/v1/messages?select=id&recipient_id=eq.$me&read_at=is.null&limit=100", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code !in 200..299) throw IOException("code $code")
            JSONArray(body).length()
        }
    }

    suspend fun conversations(): Result<List<Conversation>> = withContext(Dispatchers.IO) {
        runCatching {
            if (CloudProfiles.myId() == null) return@runCatching emptyList<Conversation>()
            val (token, me) = CloudProfiles.session()
            val r = req("/rest/v1/messages?select=*&order=created_at.desc&limit=300", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code == 404) throw IOException("Таблица messages не найдена: выполните SQL чатов в Supabase")
            if (code !in 200..299) throw IOException("Не удалось загрузить чаты ($code)")
            val a = JSONArray(body)
            val grouped = LinkedHashMap<String, MutableList<ChatMessage>>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val peer = if (o.getString("sender_id") == me) o.getString("recipient_id") else o.getString("sender_id")
                grouped.getOrPut(peer) { mutableListOf() }.add(parseMessage(o, me))
            }
            if (grouped.isEmpty()) return@runCatching emptyList<Conversation>()
            val ids = grouped.keys.joinToString(",")
            val pr = req("/rest/v1/profiles?select=id,username,avatar_b64&id=in.($ids)", token).get().build()
            val (pc, pb) = CloudProfiles.exec(pr)
            val peers = HashMap<String, ChatPeer>()
            if (pc in 200..299) {
                val pa = JSONArray(pb)
                for (i in 0 until pa.length()) {
                    val o = pa.getJSONObject(i)
                    peers[o.getString("id")] = ChatPeer(
                        o.getString("id"),
                        o.optString("username"),
                        if (o.isNull("avatar_b64")) null else o.optString("avatar_b64")
                    )
                }
            }
            grouped.map { (id, list) ->
                Conversation(
                    peers[id] ?: ChatPeer(id, "Пользователь", null),
                    list.first(),
                    list.count { !it.fromMe && !it.read }
                )
            }
        }
    }
}
'''

SCREENS_KT = r'''package chromahub.rhythm.app.features.online

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeText(ms: Long, pattern: String): String =
    if (ms <= 0L) "" else SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

@Composable
private fun ChatAvatar(b64: String?, name: String, size: Dp) {
    val bmp = remember(b64) {
        b64?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun ChatsScreen(
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { CloudProfiles.init(context); true }
    var list by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var openPeer by remember { mutableStateOf<ChatPeer?>(null) }

    BackHandler(enabled = openPeer == null) { onBack() }

    LaunchedEffect(openPeer) {
        if (openPeer == null) {
            while (true) {
                CloudChat.conversations()
                    .onSuccess { list = it; error = null }
                    .onFailure { error = it.message }
                loaded = true
                delay(6000)
            }
        }
    }

    val peer = openPeer
    if (peer != null) {
        ThreadScreen(
            musicViewModel = musicViewModel,
            peer = peer,
            onBack = { openPeer = null },
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("‹ Назад") }
        }
        item {
            Text(
                "Сообщения",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        val err = error
        if (err != null) {
            item {
                Text(
                    err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        if (loaded && list.isEmpty() && err == null) {
            item {
                Text(
                    "Переписок пока нет. Откройте «Найти людей», выберите человека и нажмите «Написать».",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        items(list, key = { it.peer.id }) { c ->
            val t = c.last.track
            val prefix = if (c.last.fromMe) "Вы: " else ""
            val preview = if (t != null) prefix + "♪ " + t.title else prefix + c.last.body
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { openPeer = c.peer }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChatAvatar(c.peer.avatarB64, c.peer.name, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        c.peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        timeText(c.last.at, "dd.MM HH:mm"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (c.unread > 0) {
                        Spacer(Modifier.size(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                c.unread.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ThreadScreen(
    musicViewModel: MusicViewModel,
    peer: ChatPeer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { CloudProfiles.init(context); ProfileStore.init(context); true }
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    BackHandler(onBack = onBack)

    LaunchedEffect(peer.id) {
        while (true) {
            CloudChat.thread(peer.id)
                .onSuccess { messages = it; error = null }
                .onFailure { error = it.message }
            if (messages.any { !it.fromMe && !it.read }) CloudChat.markRead(peer.id)
            delay(3000)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        if (ProfileStore.name.value.trim().length < 2) {
            Toast.makeText(context, "Сначала укажите своё имя в профиле", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            sending = true
            CloudChat.send(peer.id, body, null, ProfileStore.name.value)
                .onSuccess {
                    text = ""
                    CloudChat.thread(peer.id).onSuccess { messages = it }
                }
                .onFailure {
                    Toast.makeText(context, it.message ?: "Не удалось отправить", Toast.LENGTH_LONG).show()
                }
            sending = false
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            TextButton(onClick = onBack) { Text("‹") }
            ChatAvatar(peer.avatarB64, peer.name, 40.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                peer.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val err = error
        if (err != null) {
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { m ->
                MessageBubble(m) { t -> ProfileSongs.play(context, musicViewModel, t) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = if (imeBottom > 0) 8.dp else 96.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = text.isNotBlank() && !sending) { Text("Отправить") }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, onPlay: (CloudSong) -> Unit) {
    val mine = m.fromMe
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .padding(10.dp)
        ) {
            val t = m.track
            if (t != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlay(t) }
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ArtworkUtil.forSong(t.id, t.artwork),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            t.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            t.artist,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (m.body.isNotBlank()) Spacer(Modifier.size(6.dp))
            }
            if (m.body.isNotBlank()) {
                Text(m.body, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                timeText(m.at, "HH:mm") + if (mine && m.read) " · прочитано" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
            )
        }
    }
}

/** Диалог «Поделиться в чат». Рисуется поверх любого экрана, в том числе плеера. */
@Composable
fun ShareToChatHost() {
    val song by ChatShare.pending.collectAsState()
    val s = song ?: return
    ShareToChatDialog(s) { ChatShare.pending.value = null }
}

@Composable
private fun PeerRow(name: String, avatar: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChatAvatar(avatar, name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShareToChatDialog(song: CloudSong, onDismiss: () -> Unit) {
    val context = LocalContext.current
    remember { CloudProfiles.init(context); ProfileStore.init(context); true }
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf<List<ChatPeer>>(emptyList()) }
    var found by remember { mutableStateOf<List<RemoteProfile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        CloudChat.conversations().onSuccess { list -> recent = list.map { c -> c.peer } }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            found = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        CloudProfiles.search(query)
            .onSuccess { list -> found = list.filter { p -> p.id != CloudProfiles.myId() } }
            .onFailure { info = it.message }
    }

    fun sendTo(peer: ChatPeer) {
        if (busy) return
        val myName = ProfileStore.name.value
        if (myName.trim().length < 2) {
            info = "Сначала укажите своё имя в профиле"
            return
        }
        scope.launch {
            busy = true
            CloudChat.send(peer.id, note, song, myName)
                .onSuccess {
                    Toast.makeText(context, "Отправлено: " + peer.name, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
                .onFailure { info = it.message ?: "Не удалось отправить" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поделиться в чат") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = ArtworkUtil.forSong(song.id, song.artwork),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Сообщение (необязательно)") },
                        maxLines = 2
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Найти человека по имени") },
                        singleLine = true
                    )
                    val i = info
                    if (i != null) {
                        Text(
                            i,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                }
                if (query.isBlank()) {
                    if (recent.isNotEmpty()) {
                        item { Text("Недавние", style = MaterialTheme.typography.titleSmall) }
                        items(recent, key = { "r_" + it.id }) { p ->
                            PeerRow(p.name, p.avatarB64) { sendTo(p) }
                        }
                    } else {
                        item {
                            Text(
                                "Найдите человека по имени, чтобы отправить ему песню",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(found, key = { "f_" + it.id }) { p ->
                        PeerRow(p.username, p.avatarB64) { sendTo(ChatPeer(p.id, p.username, p.avatarB64)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
'''

LIVE_KT = r'''package chromahub.rhythm.app.features.online

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
'''

# ---------- читаем файлы ----------
cp = rd(CP); ps = rd(PS); pe = rd(PE); ex = rd(EX); pl = rd(PL); nav = rd(NAV); man = rd(MAN)

# ================= ЧАСТЬ 1: профиль, обложки, чаты =================
cp = once(cp, "private val JSON_TYPE", "internal val JSON_TYPE", "CloudProfiles: JSON_TYPE")
cp = once(cp, "private fun exec(req: Request)", "internal fun exec(req: Request)", "CloudProfiles: exec")
cp = once(cp, "private fun session(): Pair<String, String> {", "internal fun session(): Pair<String, String> {", "CloudProfiles: session")

ps = once(ps, "import androidx.compose.material3.Switch\n",
          "import androidx.compose.material3.Switch\nimport androidx.compose.material3.Icon\nimport androidx.compose.material3.IconButton\n", "ProfileScreen: импорты")
ps = once(ps,
    "    val favSongs = remember(favIds, allSongs, recent) {\n        (recent + allSongs).distinctBy { it.id }.filter { it.id in favIds }\n    }\n",
    "    remember { ProfileSongs.init(context); true }\n"
    "    val favSongs by ProfileSongs.songs.collectAsState()\n"
    "    LaunchedEffect(favIds, allSongs, recent) {\n"
    "        ProfileSongs.migrateFromLikes((recent + allSongs).distinctBy { it.id }.filter { it.id in favIds })\n"
    "    }\n", "ProfileScreen: список песен")
ps = rx(ps,
    r'songs = favSongs\.map \{ s ->\s*CloudSong\(\s*s\.id, s\.title, s\.artist,\s*s\.artworkUri\?\.toString\(\)\?\.takeIf \{ u -> u\.startsWith\("http"\) \},\s*s\.duration\s*\)\s*\}',
    lambda m: "songs = favSongs", "ProfileScreen: публикация песен")
ps = once(ps, ".clickable { musicViewModel.playSong(song) }",
          ".clickable { ProfileSongs.play(context, musicViewModel, song) }", "ProfileScreen: клик по песне")
ps = once(ps, "model = song.artworkUri,", "model = ArtworkUtil.forSong(song.id, song.artwork),", "ProfileScreen: обложка")
ps = once(ps, "Поставьте лайк песне в плеере, и она появится здесь",
          "В плеере откройте «три точки» и нажмите «Добавить в профиль»", "ProfileScreen: подсказка")
IB = ('                IconButton(onClick = { ProfileSongs.remove(song.id) }) {\n'
      '                    Icon(imageVector = OnlineIcons.Close, contentDescription = "Убрать", modifier = Modifier.size(20.dp))\n'
      '                }\n')
ps = rx(ps,
    r'(\n                \}\n)(            \}\n        \}\n\n        item \{\n            Text\(\n                "По умолчанию профиль виден только вам\.")',
    lambda m: m.group(1) + IB + m.group(2), "ProfileScreen: кнопка удаления песни")
CHATBLK = ('    var showChats by remember { mutableStateOf(false) }\n'
           '    val unread by ChatNotifier.unread.collectAsState()\n'
           '    val openReq by ChatNotifier.openChats.collectAsState()\n'
           '    LaunchedEffect(openReq) {\n'
           '        if (openReq) {\n'
           '            showChats = true\n'
           '            ChatNotifier.openChats.value = false\n'
           '        }\n'
           '    }\n'
           '    if (showChats) {\n'
           '        ChatsScreen(musicViewModel = musicViewModel, onBack = { showChats = false })\n'
           '        return\n'
           '    }\n')
OLD_PEOPLE = "    if (showPeople) {\n        PeopleScreen(musicViewModel = musicViewModel, onBack = { showPeople = false })\n        return\n    }\n"
ps = once(ps, OLD_PEOPLE, CHATBLK + OLD_PEOPLE, "ProfileScreen: блок чатов")
ps = once(ps, 'FilledTonalButton(onClick = { showPeople = true }) { Text("Найти людей") }',
    'Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {\n'
    '                        FilledTonalButton(onClick = { showPeople = true }) { Text("Найти людей") }\n'
    '                        FilledTonalButton(onClick = { showChats = true }) {\n'
    '                            Text(if (unread > 0) "Сообщения ($unread)" else "Сообщения")\n'
    '                        }\n'
    '                    }\n'
    '                    ChatBackgroundSettings()', "ProfileScreen: кнопка сообщений")

pe = once(pe, "model = song.artwork,", "model = ArtworkUtil.forSong(song.id, song.artwork),", "PeopleScreen: обложка")
pe = once(pe, "    var playingKey by remember { mutableStateOf<String?>(null) }\n",
    "    var playingKey by remember { mutableStateOf<String?>(null) }\n"
    "    var chatPeer by remember { mutableStateOf<ChatPeer?>(null) }\n"
    "    val openChat = chatPeer\n"
    "    if (openChat != null) {\n"
    "        ThreadScreen(musicViewModel = musicViewModel, peer = openChat, onBack = { chatPeer = null })\n"
    "        return\n"
    "    }\n", "PeopleScreen: чат")
pe = once(pe, "            if (current.artists.isNotEmpty()) {\n",
    "            item {\n"
    "                if (current.id != myId) {\n"
    "                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {\n"
    "                        androidx.compose.material3.Button(onClick = {\n"
    "                            chatPeer = ChatPeer(current.id, current.username, current.avatarB64)\n"
    "                        }) { Text(\"Написать\") }\n"
    "                    }\n"
    "                }\n"
    "            }\n"
    "            if (current.artists.isNotEmpty()) {\n", "PeopleScreen: кнопка Написать")

ex = once(ex, "    onDownload: (() -> Unit)? = null\n) {",
    "    onDownload: (() -> Unit)? = null,\n"
    "    profileLabel: String? = null,\n"
    "    profileDescription: String? = null,\n"
    "    profileDone: Boolean = false,\n"
    "    onProfile: (() -> Unit)? = null,\n"
    "    onShareChat: (() -> Unit)? = null\n) {", "ExtraControl: параметры")
TILES = '''        if (onProfile != null && profileLabel != null) {
            add(ControlAction(
                icon = if (profileDone) MaterialSymbolIcon("person_remove") else MaterialSymbolIcon("person_add"),
                label = profileLabel,
                description = profileDescription,
                containerColor = if (profileDone) tertiary else secondary,
                iconColor = if (profileDone) onTertiary else onSecondary,
                onClick = {
                    HapticUtils.performHapticFeedback(context, haptic, HapticType.HEAVY)
                    dismissAndDo { onProfile() }
                }
            ))
        }
        if (onShareChat != null) {
            add(ControlAction(
                icon = MaterialSymbolIcon("send"),
                label = "Поделиться в чат",
                description = "Отправить другу",
                containerColor = secondary,
                iconColor = onSecondary,
                onClick = {
                    HapticUtils.performHapticFeedback(context, haptic, HapticType.HEAVY)
                    dismissAndDo { onShareChat() }
                }
            ))
        }

'''
ex = once(ex, "        if (onDownload != null && downloadLabel != null) {\n",
          TILES + "        if (onDownload != null && downloadLabel != null) {\n", "ExtraControl: плитки")

pl = once(pl, "import chromahub.rhythm.app.features.online.OnlineTrack\n",
    "import chromahub.rhythm.app.features.online.OnlineTrack\n"
    "import chromahub.rhythm.app.features.online.ProfileSongs\n"
    "import chromahub.rhythm.app.features.online.ChatShare\n", "PlayerScreen: импорты")
pl = once(pl, "val dlProgress = dlVideoId?.let { dlPending[it]?.progress }\n",
    "val dlProgress = dlVideoId?.let { dlPending[it]?.progress }\n"
    "            remember { ProfileSongs.init(context); true }\n"
    "            val profileSongsNow by ProfileSongs.songs.collectAsState()\n"
    "            val curSongForProfile = song\n"
    "            val inProfile = curSongForProfile != null &&\n"
    "                profileSongsNow.any { it.id == ProfileSongs.normId(curSongForProfile.id) }\n", "PlayerScreen: состояние профиля")
PARAMS = '''downloadDone = dlDone,
                profileLabel = if (curSongForProfile == null) null else if (inProfile) "В профиле" else "Добавить в профиль",
                profileDescription = if (inProfile) "Нажмите, чтобы убрать" else "Увидят друзья",
                profileDone = inProfile,
                onProfile = if (curSongForProfile == null) null else {
                    {
                        if (inProfile) {
                            ProfileSongs.remove(curSongForProfile.id)
                            android.widget.Toast.makeText(context, "Убрано из профиля", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            ProfileSongs.add(curSongForProfile)
                            android.widget.Toast.makeText(context, "Добавлено в профиль", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onShareChat = if (curSongForProfile == null) null else {
                    { ChatShare.request(curSongForProfile) }
                },
'''
pl = once(pl, "downloadDone = dlDone,\n", PARAMS, "PlayerScreen: параметры меню")

nav = rx(nav, r'(\n[ \t]*)(// Main content\n[ \t]*NavHost\()',
         lambda m: m.group(1) + "chromahub.rhythm.app.features.online.ShareToChatHost()" + m.group(1) + m.group(2),
         "LocalNavigation: хост диалога")

# ================= ЧАСТЬ 2: сообщения всегда, уведомления =================
cs = SCREENS_KT
cs = once(cs, "    LaunchedEffect(openPeer) {",
    "    val ver by ChatNotifier.version.collectAsState()\n    LaunchedEffect(openPeer, ver) {", "ChatScreens: список")
cs = once(cs, "delay(6000)", "delay(5000)", "ChatScreens: пауза списка")
cs = once(cs, "    LaunchedEffect(peer.id) {",
    "    val ver by ChatNotifier.version.collectAsState()\n"
    "    DisposableEffect(peer.id) {\n"
    "        ChatNotifier.activePeer = peer.id\n"
    "        onDispose { if (ChatNotifier.activePeer == peer.id) ChatNotifier.activePeer = null }\n"
    "    }\n"
    "    LaunchedEffect(peer.id, ver) {", "ChatScreens: чат")
cs = once(cs, "delay(3000)", "delay(2000)", "ChatScreens: пауза чата")

H = "chromahub.rhythm.app.features.online.ShareToChatHost()"
nav = once(nav, H,
    H + "\n        chromahub.rhythm.app.features.online.ChatLiveHost(onOpenChats = {\n"
        "            navController.navigate(Screen.Profile.route) { launchSingleTop = true }\n"
        "        })", "LocalNavigation: хост уведомлений")

if "ChatSyncService" in man:
    errors.append("в манифесте уже есть ChatSyncService")
need = ["android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"]
lines = "".join('\n    <uses-permission android:name="%s" />' % p for p in need if p not in man)
anchor = '<uses-permission android:name="android.permission.INTERNET" />'
man = once(man, anchor, anchor + lines, "Manifest: разрешения")
SERVICE = ('    <service\n'
           '        android:name="chromahub.rhythm.app.features.online.ChatSyncService"\n'
           '        android:exported="false"\n'
           '        android:foregroundServiceType="specialUse">\n'
           '        <property\n'
           '            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"\n'
           '            android:value="chat_message_sync" />\n'
           '    </service>\n')
man = once(man, "</application>", SERVICE + "</application>", "Manifest: служба")

# ================= итог =================
if errors:
    print("СТОП. Ничего не записано. Проблемы (%d):" % len(errors))
    for e in errors: print("  - " + e)
    sys.exit(1)

bk = os.path.expanduser("~/Desktop/chat_backup")
os.makedirs(bk, exist_ok=True)
for p in (CP, PS, PE, EX, PL, NAV, MAN):
    shutil.copy(p, os.path.join(bk, os.path.basename(p)))

for p, t in ((CP, cp), (PS, ps), (PE, pe), (EX, ex), (PL, pl), (NAV, nav), (MAN, man),
             (O + "ProfileSongs.kt", SONGS_KT), (O + "CloudChat.kt", CHAT_KT),
             (O + "ChatScreens.kt", cs), (O + "ChatLive.kt", LIVE_KT)):
    open(p, "w", encoding="utf-8").write(t)
print("Готово: профиль, обложки, чаты и уведомления добавлены. Копии: ~/Desktop/chat_backup")
