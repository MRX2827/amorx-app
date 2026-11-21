package amorx

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import java.io.File
import java.util.UUID

internal data class LibraryTrack(
    val id: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val artwork: String?,
    val videoUrl: String?,
    val localPath: String?
) {
    fun toTrack(): Track? {
        Downloads.find(id)?.let { return it.toTrack() }
        val local = localPath?.takeIf { File(it).isFile }
        if (local == null && videoUrl == null) return null
        return Track(id, title, artist, seconds, colorFor(id), artwork, videoUrl, local)
    }
}

internal data class DesktopPlaylist(val id: String, val name: String, val tracks: List<LibraryTrack>)

internal data class DesktopLibraryData(
    val local: List<LibraryTrack> = emptyList(),
    val playlists: List<DesktopPlaylist> = emptyList()
)

internal object DesktopLibrary {
    private val file = File(AppDir.root, "desktop-library.json")
    private val gson = Gson()
    val data = mutableStateOf(
        runCatching { gson.fromJson(file.readText(), DesktopLibraryData::class.java) }
            .getOrNull() ?: DesktopLibraryData()
    )
    val message = mutableStateOf<String?>(null)

    private fun save(next: DesktopLibraryData): Boolean = runCatching {
        writeLocalJson(file, gson.toJson(next))
        data.value = next
    }.onFailure {
        message.value = "Не удалось сохранить библиотеку"
    }.isSuccess

    @Synchronized
    fun openLocal(file: File): Track? {
        if (!file.isFile || !file.canRead()) {
            message.value = "Файл недоступен"
            return null
        }
        val id = file.toURI().toString()
        val entry = LibraryTrack(id, file.nameWithoutExtension, "Локальный файл", 0, null, null, file.absolutePath)
        val current = data.value
        save(current.copy(local = listOf(entry) + current.local.filterNot { it.id == id }))
        return entry.toTrack()
    }

    @Synchronized
    fun removeLocal(id: String) {
        val current = data.value
        save(current.copy(local = current.local.filterNot { it.id == id }))
    }

    @Synchronized
    fun createPlaylist(name: String, first: Track?): DesktopPlaylist? {
        val cleanName = name.trim().take(80)
        if (cleanName.isEmpty()) return null
        val playlist = DesktopPlaylist(UUID.randomUUID().toString(), cleanName, listOfNotNull(first?.toLibraryTrack()))
        val current = data.value
        return playlist.takeIf { save(current.copy(playlists = current.playlists + playlist)) }
    }

    @Synchronized
    fun addToPlaylist(id: String, track: Track) {
        val entry = track.toLibraryTrack() ?: return
        updatePlaylist(id) { playlist ->
            if (playlist.tracks.any { it.id == entry.id }) playlist
            else playlist.copy(tracks = playlist.tracks + entry)
        }
    }

    @Synchronized
    fun removeFromPlaylist(id: String, trackId: String) {
        updatePlaylist(id) { playlist -> playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId }) }
    }

    @Synchronized
    fun deletePlaylist(id: String): Boolean {
        val current = data.value
        return save(current.copy(playlists = current.playlists.filterNot { it.id == id }))
    }

    @Synchronized
    fun restorePlaylists(playlists: List<DesktopPlaylist>): Result<Unit> = runCatching {
        val current = data.value
        val merged = current.playlists.associateByTo(linkedMapOf()) { it.id }
        playlists.forEach { incoming ->
            val existing = merged[incoming.id]
            merged[incoming.id] = if (existing == null) {
                incoming.copy(tracks = incoming.tracks.distinctBy { it.id })
            } else {
                existing.copy(tracks = (existing.tracks + incoming.tracks).distinctBy { it.id })
            }
        }
        val next = current.copy(playlists = merged.values.toList())
        if (next != current && !save(next)) error("Не удалось сохранить восстановленные плейлисты")
    }

    private fun updatePlaylist(id: String, update: (DesktopPlaylist) -> DesktopPlaylist) {
        val current = data.value
        val next = current.playlists.map { if (it.id == id) update(it) else it }
        if (current.playlists != next) save(current.copy(playlists = next))
    }

    private fun Track.toLibraryTrack(): LibraryTrack? {
        if (!isReal) return null
        // Download rows keep the file for offline use and the original source when the download is removed.
        val downloaded = Downloads.find(id)
        val source = videoUrl ?: downloaded?.let { "https://www.youtube.com/watch?v=$id" }
        val cover = artwork?.takeUnless { downloaded != null && it.startsWith("file:") }
        return LibraryTrack(id, title, artist, seconds, cover, source, localPath)
    }
}
