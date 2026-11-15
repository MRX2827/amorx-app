package amorx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChatPeer(val id: String, val name: String, val avatarB64: String?)
data class ChatMessage(
    val id: Long, val fromMe: Boolean, val body: String, val track: CloudSong?, val at: Long, val read: Boolean
)
data class Conversation(val peer: ChatPeer, val last: ChatMessage, val unread: Int)

/** Сообщения между пользователями (Supabase, таблица messages) — те же запросы, что на телефоне. */
object CloudChat {
    @Volatile private var ensured = false

    private fun req(path: String, token: String): Request.Builder =
        Request.Builder()
            .url(CloudConfig.URL + path)
            .header("apikey", CloudConfig.KEY)
            .header("Authorization", "Bearer $token")

    private fun JsonObject.s(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun parseTime(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s.take(19))?.time ?: 0L
    }.getOrDefault(0L)

    private fun parseMessage(o: JsonObject, me: String): ChatMessage {
        val t = o.get("track")?.takeIf { it.isJsonObject }?.asJsonObject?.let { x ->
            CloudSong(
                x.s("id") ?: "", x.s("title") ?: "", x.s("artist") ?: "",
                x.s("artwork")?.ifBlank { null },
                x.get("dur")?.takeIf { v -> !v.isJsonNull }?.asLong ?: 0L
            )
        }
        return ChatMessage(
            o.get("id").asLong, o.s("sender_id") == me, o.s("body") ?: "", t,
            parseTime(o.s("created_at") ?: ""), o.get("read_at")?.let { !it.isJsonNull } ?: false
        )
    }

    /** Чтобы писать, у вас должна быть строка профиля на сервере (скрытая, пока не опубликуете). */
    private suspend fun ensureProfile(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ensured) return@runCatching
            val (token, uid) = CloudProfiles.session()
            val body = JsonObject().apply { addProperty("id", uid); addProperty("username", name.trim()) }
            val r = req("/rest/v1/profiles", token)
                .header("Prefer", "resolution=ignore-duplicates,return=minimal")
                .post(body.toString().toRequestBody(CloudProfiles.JSON))
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
                val o = JsonObject().apply {
                    addProperty("sender_id", uid)
                    addProperty("recipient_id", toId)
                    addProperty("body", body.trim())
                    if (track != null) {
                        add("track", JsonObject().apply {
                            addProperty("id", track.id)
                            addProperty("title", track.title)
                            addProperty("artist", track.artist)
                            if (track.artwork != null) addProperty("artwork", track.artwork)
                            addProperty("dur", track.dur)
                        })
                    }
                }
                val r = req("/rest/v1/messages", token)
                    .header("Prefer", "return=minimal")
                    .post(o.toString().toRequestBody(CloudProfiles.JSON))
                    .build()
                val (code, resp) = CloudProfiles.exec(r)
                if (code !in 200..299) {
                    throw IOException(
                        when {
                            code == 404 -> "Таблица messages не найдена в Supabase"
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
            JsonParser.parseString(body).asJsonArray.map { parseMessage(it.asJsonObject, me) }.reversed()
        }
    }

    suspend fun markRead(peerId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val (token, me) = CloudProfiles.session()
            val r = req("/rest/v1/messages?recipient_id=eq.$me&sender_id=eq.$peerId&read_at=is.null", token)
                .header("Prefer", "return=minimal")
                .patch(JsonObject().apply { addProperty("read_at", isoNow()) }.toString().toRequestBody(CloudProfiles.JSON))
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
            JsonParser.parseString(body).asJsonArray.size()
        }
    }

    suspend fun conversations(): Result<List<Conversation>> = withContext(Dispatchers.IO) {
        runCatching {
            if (CloudProfiles.myId() == null) return@runCatching emptyList<Conversation>()
            val (token, me) = CloudProfiles.session()
            val r = req("/rest/v1/messages?select=*&order=created_at.desc&limit=300", token).get().build()
            val (code, body) = CloudProfiles.exec(r)
            if (code == 404) throw IOException("Таблица messages не найдена в Supabase")
            if (code !in 200..299) throw IOException("Не удалось загрузить чаты ($code)")
            val grouped = LinkedHashMap<String, MutableList<ChatMessage>>()
            JsonParser.parseString(body).asJsonArray.forEach { e ->
                val o = e.asJsonObject
                val peer = if (o.s("sender_id") == me) o.s("recipient_id")!! else o.s("sender_id")!!
                grouped.getOrPut(peer) { mutableListOf() }.add(parseMessage(o, me))
            }
            if (grouped.isEmpty()) return@runCatching emptyList<Conversation>()
            val ids = grouped.keys.joinToString(",")
            val pr = req("/rest/v1/profiles?select=id,username,avatar_b64&id=in.($ids)", token).get().build()
            val (pc, pb) = CloudProfiles.exec(pr)
            val peers = HashMap<String, ChatPeer>()
            if (pc in 200..299) {
                JsonParser.parseString(pb).asJsonArray.forEach { e ->
                    val o = e.asJsonObject
                    val id = o.s("id") ?: return@forEach
                    peers[id] = ChatPeer(id, o.s("username") ?: "Пользователь", o.s("avatar_b64")?.ifBlank { null })
                }
            }
            grouped.map { (id, list) ->
                Conversation(peers[id] ?: ChatPeer(id, "Пользователь", null), list.first(), list.count { !it.fromMe && !it.read })
            }
        }
    }
}

/** Играет песню из чужого профиля или сообщения: ищет по «артист название». Возвращает текст ошибки или null. */
suspend fun playCloudSong(p: PlayerState, s: CloudSong): String? = try {
    val t = Repo.search(s.artist + " " + s.title).firstOrNull()
    if (t == null) "Песня не найдена" else { p.play(t, listOf(t)); null }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    "Ошибка: " + (e.message ?: e.javaClass.simpleName)
}

@Composable
fun ChatsScreen(onBack: () -> Unit, onOpen: (ChatPeer) -> Unit) {
    var list by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            CloudChat.conversations()
                .onSuccess { list = it; err = null }
                .onFailure { if (it !is CancellationException) err = it.message ?: "Ошибка" }
            loaded = true
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Text("Сообщения", style = MaterialTheme.typography.headlineSmall)
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (loaded && err == null && list.isEmpty()) {
            Text(
                "Пока нет диалогов. Откройте чужой профиль в «Найти людей» и нажмите «Написать»",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(list, key = { it.peer.id }) { c ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onOpen(c.peer) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        B64Avatar(c.peer.avatarB64, 48.dp, c.peer.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val preview = (if (c.last.fromMe) "Вы: " else "") +
                                (c.last.track?.let { "Трек: " + it.title } ?: c.last.body)
                            Text(
                                preview, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (c.unread > 0) Badge { Text(c.unread.toString()) }
                    }
                }
            }
        }
    }
}

@Composable
fun ThreadScreen(peer: ChatPeer, p: PlayerState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val state = rememberLazyListState()

    suspend fun refresh() {
        CloudChat.thread(peer.id)
            .onSuccess { if (it != msgs) msgs = it; err = null }
            .onFailure { if (it !is CancellationException) err = it.message ?: "Ошибка" }
        if (msgs.any { !it.fromMe && !it.read }) CloudChat.markRead(peer.id)
    }

    LaunchedEffect(peer.id) {
        while (true) {
            refresh()
            delay(3000)
        }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) state.scrollToItem(msgs.lastIndex)
    }

    fun send(track: CloudSong?) {
        val body = text.trim()
        if (sending || (body.isEmpty() && track == null)) return
        val name = CloudProfiles.ownProfile.value.profile.name
        if (name.length < 2) {
            err = "Сначала задайте имя в профиле и нажмите «Сохранить»"
            return
        }
        scope.launch {
            sending = true
            CloudChat.send(peer.id, body, track, name)
                .onSuccess { text = ""; err = null; refresh() }
                .onFailure { if (it !is CancellationException) err = it.message ?: "Ошибка" }
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            B64Avatar(peer.avatarB64, 40.dp, peer.name)
            Spacer(Modifier.width(12.dp))
            Text(peer.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                Bubble(m) { s -> scope.launch { err = playCloudSong(p, s) } }
            }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(1000) },
                placeholder = { Text("Сообщение") }, singleLine = true,
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { send(null); true } else false
                }
            )
            val cur = p.current
            if (cur != null) {
                IconButton(
                    onClick = { send(CloudSong(cur.id, cur.title, cur.artist, cur.artwork, cur.seconds * 1000L)) },
                    enabled = !sending
                ) { Icon(Icons.Rounded.MusicNote, "Отправить текущую песню") }
            }
            IconButton(onClick = { send(null) }, enabled = !sending) { Icon(Icons.AutoMirrored.Rounded.Send, "Отправить") }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, onPlay: (CloudSong) -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (m.fromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                m.track?.let { s ->
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPlay(s) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(colorFor(s.id), 44.dp, url = s.artwork)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (m.body.isNotBlank()) Text(m.body)
                Text(
                    timeFmt.format(Date(m.at)) + if (m.fromMe && m.read) "  ·  прочитано" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
