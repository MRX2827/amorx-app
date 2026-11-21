package amorx

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Exercised in the packaged Windows EXE, with its actual bundled VLC engine. */
internal suspend fun verifyPlaybackControls(file: File) = coroutineScope {
    require(file.isFile)
    val player = PlayerState(this, record = {})
    val tracks = (0..7).map { Track("control-$it", "Control $it", "AmorX", 12, colorFor("$it"), localPath = file.absolutePath) }
    suspend fun ready() = withTimeout(12_000) {
        while (player.loading) delay(15)
        check(player.error == null) { player.error.orEmpty() }
    }
    player.play(tracks.first(), tracks)
    ready()
    player.toggleShuffle()
    var previous: String? = null
    repeat(3) { cycle ->
        val heard = linkedSetOf<String>()
        repeat(tracks.size) { index ->
            if (cycle > 0 || index > 0) { player.next(); ready() }
            val id = player.current!!.id
            check(id != previous) { "Consecutive duplicate $id" }
            check(heard.add(id)) { "Shuffle repeated $id before the full cycle" }
            previous = id
        }
    }
    player.toggle()
    withTimeout(5000) {
        while (withContext(Dispatchers.IO) { Engine.snapshot().phase } != AudioPhase.Paused) delay(15)
    }
    player.toggle()
    withTimeout(5000) {
        while (withContext(Dispatchers.IO) { Engine.snapshot().phase } != AudioPhase.Playing) delay(15)
    }
    // Cancel superseded starts and apply the final play/pause intent after a rapid sequence.
    repeat(12) { player.next(); player.toggle(); player.toggle() }
    ready()
    check(player.playing)
    withContext(Dispatchers.IO) { Engine.stop() }
    println("Playback controls verified: 3 complete shuffle cycles, pause/resume, and rapid skipping")
}
