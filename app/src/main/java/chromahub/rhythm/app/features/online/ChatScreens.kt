package chromahub.rhythm.app.features.online

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

    val ver by ChatNotifier.version.collectAsState()
    LaunchedEffect(openPeer, ver) {
        if (openPeer == null) {
            while (true) {
                CloudChat.conversations()
                    .onSuccess { list = it; error = null }
                    .onFailure { error = it.message }
                loaded = true
                delay(5000)
            }
        }
    }

    PlayerLikeSheetFor(item = openPeer) { p ->
        ThreadScreen(
            musicViewModel = musicViewModel,
            peer = p,
            onBack = { openPeer = null }
        )
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

    val ver by ChatNotifier.version.collectAsState()
    DisposableEffect(peer.id) {
        ChatNotifier.activePeer = peer.id
        onDispose { if (ChatNotifier.activePeer == peer.id) ChatNotifier.activePeer = null }
    }
    LaunchedEffect(peer.id, ver) {
        while (true) {
            CloudChat.thread(peer.id)
                .onSuccess { messages = it; error = null }
                .onFailure { error = it.message }
            if (messages.any { !it.fromMe && !it.read }) CloudChat.markRead(peer.id)
            delay(2000)
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
