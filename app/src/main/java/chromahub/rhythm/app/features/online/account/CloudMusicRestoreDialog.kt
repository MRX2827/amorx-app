package chromahub.rhythm.app.features.online.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import chromahub.rhythm.app.features.online.OnlineIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CloudMusicRestoreDialog(
    preview: CloudMusicRestorePreview,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRestore: (Set<String>, Boolean) -> Unit
) {
    var selected by remember(preview) { mutableStateOf(preview.downloads.map { it.id }.toSet()) }
    var includePlaylist by remember(preview) { mutableStateOf(preview.hasPlaylist) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(if (entered) 1f else .93f, tween(280), label = "restore dialog entrance")
    AlertDialog(
        modifier = Modifier.scale(scale),
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(OnlineIcons.Download, null, Modifier.size(32.dp)) },
        title = { Text("Вернуть вашу музыку?") },
        text = {
            Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Все скачанные треки и один плейлист AmorX. Выберите, что восстановить. Приложение останется открытым.")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (preview.downloads.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selected = if (selected.size == preview.downloads.size) emptySet() else preview.downloads.map { it.id }.toSet()
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = selected.size == preview.downloads.size, enabled = !busy, onCheckedChange = {
                                    selected = if (it) preview.downloads.map { t -> t.id }.toSet() else emptySet()
                                })
                                Text("Все скачанные · ${preview.downloads.size}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        items(preview.downloads, key = { "download:${it.id}" }) { track ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selected = if (track.id in selected) selected - track.id else selected + track.id
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = track.id in selected, enabled = !busy, onCheckedChange = {
                                    selected = if (it) selected + track.id else selected - track.id
                                })
                                RestoreTrackTitle(track)
                            }
                        }
                    }
                    if (preview.hasPlaylist) {
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { includePlaylist = !includePlaylist }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = includePlaylist, enabled = !busy, onCheckedChange = { includePlaylist = it })
                                Text("Плейлист AmorX · ${preview.playlist.size}", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        items(preview.playlist, key = { "playlist:${it.id}" }) { track ->
                            Box(Modifier.fillMaxWidth().padding(start = 48.dp, top = 6.dp, bottom = 6.dp)) { RestoreTrackTitle(track) }
                        }
                    }
                    if (preview.downloads.isEmpty() && !preview.hasPlaylist) item { Text("В этой копии пока нет скачанных треков и плейлиста AmorX.") }
                }
                Text("Скачанное будет загружаться по два трека. Готовые песни появятся в «Скачанном».", style = MaterialTheme.typography.bodySmall)
                if (preview.skipped > 0) Text("Недоступные локальные треки пропущены: ${preview.skipped}.", style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !busy && (selected.isNotEmpty() || includePlaylist && preview.hasPlaylist), onClick = { onRestore(selected, includePlaylist) }) {
                AnimatedContent(targetState = busy, label = "restore action") { Text(if (it) "Восстанавливаем…" else "Восстановить") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Не сейчас") } }
    )
}

@Composable
private fun RestoreTrackTitle(track: RestoreMusicTrack) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
