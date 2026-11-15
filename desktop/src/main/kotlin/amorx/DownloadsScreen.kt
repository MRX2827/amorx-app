package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DownloadsScreen(p: PlayerState) {
    val stored by Downloads.items
    val pend by Downloads.pending
    var toDelete by remember { mutableStateOf<Downloaded?>(null) }
    val sorted = stored.sortedByDescending { it.addedAt }
    val tracks = sorted.map { it.toTrack() }

    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить из загрузок?") },
            text = { Text("«${d.title}» будет удалён с компьютера.") },
            confirmButton = {
                TextButton(onClick = { Downloads.delete(d.id); toDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Text(
                "Скачанное",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
            )
        }
        if (sorted.isNotEmpty()) {
            item {
                val mb = sorted.sumOf { it.sizeBytes } / 1048576.0
                Text(
                    "${sorted.size} · ${"%.1f".format(mb)} МБ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { p.play(tracks[0], tracks) }) { Text("Играть всё") }
                    FilledTonalButton(onClick = {
                        val sh = tracks.shuffled()
                        p.play(sh[0], sh)
                    }) { Text("Вперемешку") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        items(pend.values.toList(), key = { "p_" + it.track.id }) { d ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    "Загружается: ${d.track.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                if (d.progress > 0f) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (sorted.isEmpty() && pend.isEmpty()) {
            item {
                Text(
                    "Здесь появятся песни, скачанные во вкладке «Онлайн». Они играют без интернета.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        items(tracks, key = { it.id }) { t ->
            AnimateIn(Modifier.animateItem()) {
                TrackRow(t, p.current?.id == t.id && p.playing, onClick = { p.play(t, tracks) }) {
                    IconButton(onClick = { toDelete = sorted.firstOrNull { it.id == t.id } }) {
                        Icon(Icons.Rounded.Delete, "Удалить")
                    }
                }
            }
        }
    }
}
