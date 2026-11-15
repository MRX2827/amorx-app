package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
internal fun DesktopControls(player: PlayerState, openAudio: () -> File?, fullscreen: () -> Unit) {
    var account by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            openAudio()?.let { file ->
                val track = Track(file.toURI().toString(), file.nameWithoutExtension, "Локальный файл",
                    0, colorFor(file.name), localPath = file.absolutePath)
                player.play(track, listOf(track))
            }
        }) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(7.dp)); Text("Открыть файл") }
        IconButton(onClick = { account = true }) { Icon(Icons.Rounded.Person, "Аккаунт AmorX") }
        IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Настройки") }
        IconButton(onClick = fullscreen) { Icon(Icons.Rounded.Fullscreen, "Полноэкранный режим") }
    }
    if (account) AccountDialog { account = false }
    if (settings) DesktopSettingsDialog { settings = false }
}

@Composable
private fun DesktopSettingsDialog(close: () -> Unit) {
    val saved by SettingsStore.current
    var selected by remember { mutableStateOf(saved.appearance) }
    var volume by remember { mutableStateOf(saved.volume.toFloat()) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Оформление")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to "Системное", "dark" to "Тёмное", "light" to "Светлое").forEach { (id, title) ->
                        FilterChip(selected == id, { selected = id }, { Text(title) })
                    }
                }
                Text("Громкость ${volume.toInt()}%")
                Slider(volume, { volume = it }, valueRange = 0f..100f)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                SettingsStore.save(DesktopSettings(selected, volume.toInt()))
                Engine.setVolume(volume.toInt())
                close()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Отмена") } }
    )
}
