package amorx

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun writeLocalJson(file: File, json: String) {
    val stage = Files.createTempFile(file.parentFile.toPath(), ".${file.name}-", ".tmp")
    try {
        Files.writeString(stage, json)
        try {
            Files.move(stage, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(stage, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(stage)
    }
}

internal data class DesktopSettings(val appearance: String = "system", val volume: Int = 100)

internal object SettingsStore {
    private val gson = Gson()
    private val file = File(AppDir.root, "settings.json")
    val current = mutableStateOf(
        runCatching { gson.fromJson(file.readText(), DesktopSettings::class.java) }.getOrNull() ?: DesktopSettings()
    )

    fun save(settings: DesktopSettings) {
        writeLocalJson(file, gson.toJson(settings))
        current.value = settings
    }
}
