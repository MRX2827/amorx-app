package amorx

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import java.io.File

object AppDir {
    val root: File = run {
        val override = System.getProperty("amorx.data.dir")
        val userHome = File(System.getProperty("user.home"))
        when {
            !override.isNullOrBlank() -> File(override)
            System.getProperty("os.name").startsWith("Windows") ->
                File(System.getenv("LOCALAPPDATA") ?: File(userHome, "AppData/Local").path, "AmorX Desktop")
            else -> File(userHome, "Library/Application Support/AmorX")
        }.apply { mkdirs() }
    }
}

data class Artist(val name: String, val avatar: String?)

/** Артисты «Моей волны», хранятся на Mac. */
object WaveStore {
    private val file = File(AppDir.root, "artists.json")
    private val gson = Gson()
    val artists = mutableStateOf(load())

    private fun load(): List<Artist> = runCatching {
        gson.fromJson(file.readText(), Array<Artist>::class.java)?.toList()
    }.getOrNull().orEmpty()

    private fun save() {
        runCatching { file.writeText(gson.toJson(artists.value)) }
    }

    fun add(a: Artist) {
        if (artists.value.any { it.name.equals(a.name, ignoreCase = true) }) return
        artists.value = artists.value + a
        save()
    }

    fun remove(name: String) {
        artists.value = artists.value.filter { !it.name.equals(name, ignoreCase = true) }
        save()
    }
}
