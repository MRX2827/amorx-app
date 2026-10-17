package chromahub.rhythm.app.features.online

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import coil.compose.AsyncImage

@Composable
fun DownloadsScreen(
    musicViewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { OnlineDownloads.init(context); true }

    val items by OnlineDownloads.items.collectAsState()
    val pending by OnlineDownloads.pending.collectAsState()
    var toDelete by remember { mutableStateOf<DownloadedTrack?>(null) }

    LaunchedEffect(Unit) {
        OnlineDownloads.messages.collect {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }

    val sorted = items.sortedByDescending { it.addedAt }
    val songs = sorted.map { it.toSong() }

    toDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить из загрузок?") },
            text = { Text("«${track.title}» будет удалён с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    OnlineDownloads.delete(track.id)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 180.dp)
    ) {
        item {
            Text(
                text = "Скачанное",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
        }

        if (sorted.isNotEmpty()) {
            item {
                val totalMb = sorted.sumOf { it.sizeBytes } / 1048576.0
                Text(
                    text = "${sorted.size} · ${"%.1f".format(totalMb)} МБ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        musicViewModel.playQueue(songs, enableShuffle = false, startIndex = 0)
                    }) { Text("Играть всё") }
                    FilledTonalButton(onClick = {
                        musicViewModel.playQueue(songs, enableShuffle = true)
                    }) { Text("Вперемешку") }
                }
                Spacer(Modifier.size(12.dp))
            }
        }

        if (pending.isNotEmpty()) {
            items(pending.values.toList(), key = { "p_" + it.track.id }) { p ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = "Загружается: ${p.track.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.size(6.dp))
                    if (p.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { p.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (sorted.isEmpty() && pending.isEmpty()) {
            item {
                Text(
                    text = "Здесь появятся песни, скачанные во вкладке «Онлайн». Они играют без интернета.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }

        itemsIndexed(sorted, key = { _, t -> t.id }) { index, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        musicViewModel.playQueue(songs, enableShuffle = false, startIndex = index)
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.artworkPath?.let { java.io.File(it) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(track.artist, "%.1f МБ".format(track.sizeBytes / 1048576.0))
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { toDelete = track }) {
                    Icon(
                        imageVector = OnlineIcons.Delete,
                        contentDescription = "Удалить",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
