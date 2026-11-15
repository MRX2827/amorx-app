package amorx

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

data class LikedSong(
    val id: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val artwork: String?,
    val localPath: String? = null
)

/** Понравившиеся песни на Mac. Если профиль опубликован, список сам уходит на сервер. */
object Likes {
    private val file = File(AppDir.root, "likes.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSync: Job? = null
    val items = mutableStateOf(load())

    private fun load(): List<LikedSong> =
        runCatching { gson.fromJson(file.readText(), Array<LikedSong>::class.java)?.toList() }.getOrNull().orEmpty()

    fun isLiked(id: String) = items.value.any { it.id == id }

    fun toggle(t: Track) {
        if (!t.isReal) return
        val saved = items.value.firstOrNull { it.id == t.id }
        if (saved != null) {
            remove(t.id)
            return
        }
        val path = t.localPath?.takeIf { t.id.startsWith("file:") }
        val song = LikedSong(t.id, t.title, t.artist, t.seconds, t.artwork, path)
        save(listOf(song) + items.value)
        syncFavorite(song, liked = true)
    }

    fun remove(id: String) {
        val removed = items.value.firstOrNull { it.id == id } ?: return
        save(items.value.filter { it.id != id })
        syncFavorite(removed, liked = false)
    }

    private fun save(songs: List<LikedSong>) {
        items.value = songs
        runCatching { writeLocalJson(file, gson.toJson(songs)) }
    }

    private fun LikedSong.toCloud(): CloudSong? = if (id.startsWith("file:")) null
        else CloudSong("online_" + id, title, artist, artwork?.takeIf { it.startsWith("http") }, seconds * 1000L)

    fun asCloud(): List<CloudSong> = items.value.mapNotNull { it.toCloud() }.take(50)

    private fun syncFavorite(song: LikedSong, liked: Boolean) {
        val cloudSong = song.toCloud() ?: return
        val state = CloudProfiles.ownProfile.value
        if (!state.ready || !state.profile.isPublic) return
        val previous = pendingSync
        pendingSync = scope.launch {
            previous?.join()
            CloudProfiles.setFavorite(state.account, cloudSong, liked)
        }
    }
}

internal fun LikedSong.toTrack(): Track? {
    Downloads.find(id)?.let { return it.toTrack() }
    if (id.startsWith("file:")) {
        val audio = localPath?.let(::File) ?: runCatching { File(java.net.URI(id)) }.getOrNull()
        if (audio?.isFile != true) return null
        return Track(id, title, artist, seconds, colorFor(id), artwork, localPath = audio.absolutePath)
    }
    return Track(id, title, artist, seconds, colorFor(id), artwork, videoUrl = "https://www.youtube.com/watch?v=$id")
}

@Composable
fun LikeButton(t: Track) {
    val liked = Likes.items.value.any { it.id == t.id }
    val s by animateFloatAsState(
        targetValue = if (liked) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "like"
    )
    IconButton(onClick = { Likes.toggle(t) }, modifier = Modifier.graphicsLayer(scaleX = s, scaleY = s)) {
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится",
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LikedScreen(p: PlayerState, onBack: () -> Unit) {
    val list = Likes.items.value
    val tracks = list.mapNotNull { it.toTrack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Text("Понравившиеся", style = MaterialTheme.typography.headlineSmall)
        }
        if (list.isEmpty()) {
            Text(
                "Пока пусто. Нажмите сердечко в полном плеере у песни, которая играет",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        } else if (tracks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Button(onClick = {
                    p.play(tracks.first(), tracks)
                }) { Text("Играть всё") }
                FilledTonalButton(onClick = {
                    val q = tracks.shuffled()
                    p.play(q.first(), q)
                }) { Text("Вперемешку") }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(list, key = { it.id }) { s ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable {
                        s.toTrack()?.let { p.play(it, tracks) }
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(colorFor(s.id), 52.dp, url = s.artwork)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.artist, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { Likes.remove(s.id) }) {
                            Icon(Icons.Rounded.Favorite, "Убрать", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
