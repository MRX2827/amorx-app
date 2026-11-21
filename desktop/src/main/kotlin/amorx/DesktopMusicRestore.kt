package amorx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

internal enum class MusicRestorePhase { Discovering, Offer, Restoring, Finished, Failed }

internal data class MusicRestoreState(
    val phase: MusicRestorePhase = MusicRestorePhase.Discovering,
    val downloadCount: Int = 0,
    val playlistCount: Int = 0,
    val unavailable: Int = 0,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val progress: Float = 0f,
    val message: String? = null,
    val activeTitle: String? = null
)

internal class DesktopMusicRestore(
    private val scope: CoroutineScope,
    private val fetchPlan: suspend (DesktopCloudAccount) -> MusicRestorePlan = CloudProfiles::loadMusicRestore,
    private val downloadTrack: suspend (Track) -> Result<Downloaded> = Downloads::awaitDownload,
    private val savePlaylists: (DesktopCloudAccount, List<DesktopPlaylist>) -> Unit = { owner, playlists ->
        CloudProfiles.forAccount(owner) { DesktopLibrary.restorePlaylists(playlists).getOrThrow() }
    }
) {
    var state by mutableStateOf(MusicRestoreState())
        private set
    var visible by mutableStateOf(false)
        private set

    private var account: DesktopCloudAccount? = null
    private var plan: MusicRestorePlan? = null
    private var operation: Job? = null
    private var failedTracks = emptyList<Track>()

    fun changeAccount(next: DesktopCloudAccount?) {
        operation?.cancel()
        account = next
        plan = null
        failedTracks = emptyList()
        visible = false
        state = MusicRestoreState()
        if (next != null) discover(force = next.revision > 0)
    }

    fun show() {
        if (account == null) return
        if (plan != null && state.phase in setOf(MusicRestorePhase.Offer, MusicRestorePhase.Restoring)) {
            visible = true
            return
        }
        discover(force = true)
    }

    fun accept(downloads: Boolean, playlists: Boolean) {
        if (state.phase != MusicRestorePhase.Offer || (!downloads && !playlists)) return
        val found = plan ?: return
        val tracks = buildList {
            if (downloads) addAll(found.downloads)
            if (playlists) addAll(found.playlists.flatMap { list -> list.tracks.mapNotNull { it.toTrack() } })
        }.distinctBy { it.id }
        restore(tracks, if (playlists) found.playlists else emptyList())
    }

    fun decline() {
        visible = false
        if (state.phase == MusicRestorePhase.Discovering) operation?.cancel()
        if (state.phase != MusicRestorePhase.Offer) return
        val owner = account ?: return
        val found = plan ?: return
        scope.launch(Dispatchers.IO) { rememberDecision(owner, found) }
    }

    fun retry() {
        if (state.phase == MusicRestorePhase.Finished && failedTracks.isNotEmpty()) {
            restore(failedTracks, emptyList())
        } else discover(force = true)
    }

    private fun restore(tracks: List<Track>, playlists: List<DesktopPlaylist>) {
        val owner = account ?: return
        val found = plan ?: return
        operation?.cancel()
        failedTracks = emptyList()
        state = state.copy(
            phase = MusicRestorePhase.Restoring, total = tracks.size,
            completed = 0, failed = 0, progress = 0f, activeTitle = null
        )
        operation = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    savePlaylists(owner, playlists)
                }
                val nextTrack = tracks.iterator()
                coroutineScope {
                    repeat(2) {
                        launch {
                            while (nextTrack.hasNext()) {
                                currentCoroutineContext().ensureActive()
                                if (account != owner) return@launch
                                val track = nextTrack.next()
                                state = state.copy(activeTitle = track.title)
                                val result = downloadTrack(track)
                                currentCoroutineContext().ensureActive()
                                if (account != owner) return@launch
                                val completed = state.completed + if (result.isSuccess) 1 else 0
                                val failed = state.failed + if (result.isFailure) 1 else 0
                                if (result.isFailure) failedTracks = failedTracks + track
                                state = state.copy(
                                    completed = completed, failed = failed,
                                    progress = (completed + failed).toFloat() / tracks.size.coerceAtLeast(1)
                                )
                            }
                        }
                    }
                }
                state = state.copy(
                    phase = MusicRestorePhase.Finished, activeTitle = null, progress = 1f,
                    message = found.warnings.joinToString("\n").ifBlank { null }
                )
                if (failedTracks.isEmpty()) withContext(Dispatchers.IO) { rememberDecision(owner, found) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (account != owner) return@launch
                state = state.copy(
                    phase = MusicRestorePhase.Failed, activeTitle = null,
                    message = "Не удалось восстановить библиотеку. Уже скачанные песни доступны в «Скачанном»."
                )
            }
        }
    }

    private fun discover(force: Boolean) {
        val owner = account ?: return
        operation?.cancel()
        plan = null
        state = MusicRestoreState()
        visible = force
        operation = scope.launch {
            try {
                val found = fetchPlan(owner)
                withContext(Dispatchers.IO) {
                    savePlaylists(owner, found.playlists)
                }
                val decided = withContext(Dispatchers.IO) { decisionFile(owner).readTextOrNull() == fingerprint(found) }
                currentCoroutineContext().ensureActive()
                if (account != owner) return@launch
                plan = found
                val empty = found.downloads.isEmpty() && found.playlists.isEmpty()
                state = MusicRestoreState(
                    phase = if (empty) MusicRestorePhase.Finished else MusicRestorePhase.Offer,
                    downloadCount = found.downloads.size,
                    playlistCount = found.playlists.size,
                    unavailable = found.unavailableTracks,
                    message = if (empty) found.warnings.joinToString("\n").ifBlank {
                        "В облачной копии пока нет скачанных песен и плейлистов."
                    } else found.warnings.joinToString("\n").ifBlank { null }
                )
                visible = force || (!empty && !decided)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (account != owner) return@launch
                state = MusicRestoreState(
                    phase = MusicRestorePhase.Failed,
                    message = "Не удалось загрузить список песен из облака. Проверьте подключение и повторите."
                )
                visible = force
            }
        }
    }

    private fun decisionFile(owner: DesktopCloudAccount): File {
        val bytes = MessageDigest.getInstance("SHA-256").digest(owner.uid.toByteArray(Charsets.UTF_8))
        return File(AppDir.root, "music-restore/${HexFormat.of().formatHex(bytes)}.txt")
    }

    private fun fingerprint(found: MusicRestorePlan): String {
        val ids = found.downloads.map { it.id }.sorted() +
            found.playlists.sortedBy { it.id }.map { playlist ->
                playlist.id + ":" + playlist.name + ":" + playlist.tracks.joinToString(",") { it.id }
            }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ids.joinToString("\n").toByteArray()))
    }

    private fun rememberDecision(owner: DesktopCloudAccount, found: MusicRestorePlan) {
        val file = decisionFile(owner)
        runCatching {
            file.parentFile.mkdirs()
            writeLocalJson(file, fingerprint(found))
        }
    }

    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()
}
