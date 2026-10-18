package chromahub.rhythm.app.features.online

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PeopleScreen(
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { CloudProfiles.init(context); true }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RemoteProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<RemoteProfile?>(null) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var chatPeer by remember { mutableStateOf<ChatPeer?>(null) }
    PlayerLikeSheetFor(item = chatPeer) { p ->
        ThreadScreen(musicViewModel = musicViewModel, peer = p, onBack = { chatPeer = null })
    }

    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(400)
        loading = true
        error = null
        CloudProfiles.search(query)
            .onSuccess {
                results = it
                if (it.isEmpty()) error = "Никого не нашли"
            }
            .onFailure {
                results = emptyList()
                error = it.message ?: "Ошибка сети"
            }
        loading = false
    }

    BackHandler(enabled = chatPeer == null) { if (selected != null) selected = null else onBack() }

    val current = selected
    val myId = CloudProfiles.myId()

    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        if (current == null) {
            item {
                TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("‹ Назад") }
            }
            item {
                Text(
                    "Найти людей",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Имя пользователя") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp)
                )
            }
            item {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                } else {
                    Spacer(Modifier.size(12.dp))
                }
            }
            val err = error
            if (err != null) {
                item {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(results, key = { it.id }) { person ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { selected = person }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PeopleAvatar(person.avatarB64, null, person.username, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            person.username + if (person.id == myId) "  · это вы" else "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (person.bio.isNotBlank()) {
                            Text(
                                person.bio,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            item {
                TextButton(onClick = { selected = null }, modifier = Modifier.padding(top = 8.dp)) { Text("‹ К списку") }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PeopleAvatar(current.avatarB64, null, current.username, 112.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        current.username,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                    if (current.bio.isNotBlank()) {
                        Spacer(Modifier.size(4.dp))
                        Text(
                            current.bio,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            item {
                if (current.id != myId) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Button(onClick = {
                            chatPeer = ChatPeer(current.id, current.username, current.avatarB64)
                        }) { Text("Написать") }
                    }
                }
            }
            if (current.artists.isNotEmpty()) {
                item {
                    Text(
                        "Любимые артисты",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(current.artists) { artist ->
                            Column(
                                modifier = Modifier.width(72.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                PeopleAvatar(null, artist.avatar, artist.name, 64.dp)
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    artist.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Любимые песни",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                if (current.songs.isEmpty()) {
                    Text(
                        "Пока нет любимых песен",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(current.songs.size) { i ->
                val song = current.songs[i]
                val key = "$i-${song.id}"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (playingKey == null) {
                                scope.launch {
                                    playingKey = key
                                    val resolved = runCatching { CloudProfiles.resolveSong(song) }.getOrNull()
                                    playingKey = null
                                    if (resolved != null) {
                                        musicViewModel.playSong(resolved)
                                    } else {
                                        Toast.makeText(context, "Не удалось найти песню", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = ArtworkUtil.forSong(song.id, song.artwork),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (playingKey == key) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleAvatar(b64: String?, imageUrl: String?, label: String, size: Dp) {
    val bitmap = remember(b64) {
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
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            imageUrl != null -> AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            else -> Text(
                text = label.trim().take(1).uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
