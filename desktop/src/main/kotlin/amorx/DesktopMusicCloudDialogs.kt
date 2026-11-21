package amorx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DownloadedPlaylistDialog(playlists: List<DesktopPlaylist>, onSelected: (String) -> Unit, close: () -> Unit) {
    val downloads by Downloads.items
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    MusicActionDialog(Icons.Rounded.PlaylistAdd, "Все скачанные в плейлист", close) {
        Text("${downloads.size} треков. Уже добавленные песни не будут повторяться.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.heightIn(max = 230.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            playlists.forEach { playlist ->
                OutlinedButton(onClick = {
                    DesktopLibrary.addAllToPlaylist(playlist.id, downloads.map { it.toTrack() })
                        .onSuccess { onSelected(playlist.id); close() }
                        .onFailure { error = it.message }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.QueueMusic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(playlist.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(playlist.tracks.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("Или новый плейлист") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank() && downloads.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
            val playlist = DesktopLibrary.createPlaylistWithTracks(name, downloads.map { it.toTrack() })
            if (playlist != null) { onSelected(playlist.id); close() } else error = "Не удалось сохранить плейлист"
        }) { Text("Создать и добавить все") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun DesktopMusicBackupDialog(
    account: DesktopCloudAccount?, downloads: List<Track>, playlists: List<DesktopPlaylist>,
    signIn: () -> Unit, close: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val musicPlaylists = playlists.filter { isAmorxPlaylistName(it.name) || it.id.endsWith(":amorx") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MusicBackupResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    MusicActionDialog(if (result == null) Icons.Rounded.CloudUpload else Icons.Rounded.CheckCircle,
        if (result == null) "Копия для Android" else "Музыка сохранена", { if (!busy) close() }) {
        AnimatedContent(targetState = result, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "backup result") { saved ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved == null) {
                    Text("Сохраните все скачанные песни и один плейлист AmorX в своём аккаунте. На Android войдите в тот же аккаунт и восстановите облачную копию.")
                    Text("Скачанные: ${downloads.size}  ·  Плейлисты: ${if (musicPlaylists.isEmpty()) 0 else 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Сохраняются списки онлайн-треков. Телефон скачает музыку заново. Локальные файлы с компьютера не переносятся.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Скачанные: ${saved.downloads}  ·  Плейлисты: ${saved.playlists}")
                    Text("На телефоне откройте «Аккаунт AmorX» → «Восстановить из облака».", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (saved.skipped > 0) Text("Локальные треки без онлайн-источника пропущены: ${saved.skipped}.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (account == null) {
            Text("Для облачной копии войдите в аккаунт.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { close(); signIn() }, modifier = Modifier.fillMaxWidth()) { Text("Войти в AmorX") }
        } else if (result == null) {
            CloudActionProgress(busy, error)
            Button(enabled = !busy && (downloads.isNotEmpty() || musicPlaylists.isNotEmpty()), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null
                scope.launch {
                    try { result = CloudProfiles.saveMusicBackup(account, downloads, playlists) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить копию" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Сохраняем…" else if (error != null) "Повторить" else "Сохранить в облако") }
        } else Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun DesktopPlaylistShareDialog(account: DesktopCloudAccount?, playlist: DesktopPlaylist, signIn: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PlaylistShareResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    MusicActionDialog(Icons.Rounded.Share, "Поделиться плейлистом", { if (!busy) close() }) {
        Text(playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Создайте ссылку и отправьте её друзьям. Она открывается в AmorX на Android.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (account == null) {
            Button(onClick = { close(); signIn() }, modifier = Modifier.fillMaxWidth()) { Text("Войти в AmorX") }
        } else {
            val shared = result
            if (shared != null) {
                OutlinedTextField(shared.link, {}, readOnly = true, label = { Text("Ссылка на ${shared.tracks} треков") }, modifier = Modifier.fillMaxWidth())
                if (shared.skipped > 0) Text("Локальные файлы не включены: ${shared.skipped}.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    runCatching { clipboard.setText(AnnotatedString(shared.link)) }
                        .onSuccess { copied = true; error = null }
                        .onFailure { error = "Не удалось скопировать. Выделите ссылку и скопируйте вручную" }
                }, modifier = Modifier.fillMaxWidth()) {
                    AnimatedContent(copied, label = "playlist link copied") { Text(if (it) "Ссылка скопирована" else "Скопировать ссылку") }
                }
            } else {
                CloudActionProgress(busy, error)
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try { result = CloudProfiles.shareMusicPlaylist(account, playlist) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Не удалось создать ссылку" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Создаём ссылку…" else if (error != null) "Повторить" else "Создать ссылку") }
            }
            if (shared != null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CloudActionProgress(busy: Boolean, error: String?) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** Follows the desktop player's dark cards, animated entrance and smooth content resizing. */
@Composable
private fun MusicActionDialog(icon: ImageVector, title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, tween(300), label = "music action entrance")
    Dialog(onDismissRequest = close) {
        Surface(modifier = Modifier.width(460.dp).heightIn(max = 620.dp).graphicsLayer {
            alpha = progress; scaleX = .96f + .04f * progress; scaleY = scaleX; translationY = (1f - progress) * 18f
        }, shape = RoundedCornerShape(24.dp), color = Color(0xFF111114), border = BorderStroke(1.dp, Color(0xFF333338))) {
            Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xFF202029), Color(0xFF111114))))
                .verticalScroll(rememberScrollState()).animateContentSize(tween(240)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(28.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = close, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Close, "Закрыть") }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}
