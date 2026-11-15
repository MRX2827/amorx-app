package amorx

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopSettingsDialog(close: () -> Unit) {
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
