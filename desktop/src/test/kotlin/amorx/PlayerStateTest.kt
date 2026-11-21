package amorx

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class PlayerStateTest {
    private class FakeAudio : AudioOutput {
        val state = AtomicReference(AudioPhase.Stopped)
        var fail = false
        @Volatile var pauseDelay = 0L
        @Volatile var playDelay = 0L
        override fun play(url: String) { Thread.sleep(playDelay); state.set(if (fail) AudioPhase.Failed else AudioPhase.Playing) }
        override fun stop() { state.set(AudioPhase.Stopped) }
        override fun setPause(pause: Boolean) { Thread.sleep(pauseDelay); state.set(if (pause) AudioPhase.Paused else AudioPhase.Playing) }
        override fun seek(ms: Long) {}
        override fun snapshot() = AudioSnapshot(state.get(), 1000, 30_000)
    }
    private fun track(id: String) = Track(id, id, "test", 30, colorFor(id), localPath = "/test/$id")
    private fun player(scope: CoroutineScope, audio: FakeAudio, timeout: Long = 3000) = PlayerState(
        scope, audio, { it }, { it.localPath }, {}, timeout
    )
    private suspend fun ready(player: PlayerState) = withTimeout(5000) { while (player.loading) delay(5) }

    @Test fun rejectedStreamEnablesRetryInsteadOfPretendingToPlay() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val audio = FakeAudio().apply { fail = true }
            val player = player(scope, audio)
            player.play(track("a"))
            ready(player)
            assertFalse(player.playing)
            assertNotNull(player.error)
            audio.fail = false
            player.toggle()
            ready(player)
            assertTrue(player.playing)
            assertNull(player.error)
        } finally { scope.cancel() }
    }

    @Test fun slowNativePauseDoesNotBlockNextButtonOrInterface() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val audio = FakeAudio()
            val player = player(scope, audio)
            val tracks = listOf(track("a"), track("b"))
            player.play(tracks[0], tracks)
            ready(player)
            audio.pauseDelay = 200
            player.toggle()
            delay(20)
            val before = System.nanoTime()
            player.next()
            assertEquals("b", player.current?.id)
            assertTrue((System.nanoTime() - before) / 1_000_000 < 100)
            audio.pauseDelay = 0
            ready(player)
            assertTrue(player.playing)
            assertNull(player.error)
        } finally { scope.cancel() }
    }

    @Test fun slowNativeStartupTimesOutAndLatestTrackCanRecover() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val audio = FakeAudio().apply { playDelay = 300 }
            val player = player(scope, audio, 100)
            player.play(track("a"), listOf(track("a"), track("b")))
            ready(player)
            assertNotNull(player.error)
            assertFalse(player.playing)
            delay(350)
            audio.playDelay = 0
            player.next()
            ready(player)
            assertEquals("b", player.current?.id)
            assertNull(player.error)
            assertTrue(player.playing)
        } finally { scope.cancel() }
    }

    @Test fun shuffleAndRepeatedRowClicksKeepCycleAndPreviousHistoryIntact() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val player = player(scope, FakeAudio())
            val tracks = (0..7).map { track("song-$it") }
            player.play(tracks.first(), tracks)
            ready(player)
            player.repeat = true
            player.toggleShuffle()
            assertFalse(player.repeat)
            val heard = mutableListOf(player.current!!.id)
            repeat(7) {
                player.next()
                ready(player)
                heard += player.current!!.id
                // Clicking the already playing row must not discard listening history.
                player.play(player.current!!, tracks)
            }
            assertEquals(8, heard.toSet().size)
            player.prev()
            ready(player)
            assertEquals(heard[6], player.current?.id)
            player.next()
            ready(player)
            assertEquals(heard[7], player.current?.id)
            val cycle = mutableListOf<String>()
            repeat(8) { player.next(); ready(player); cycle += player.current!!.id }
            assertEquals(8, cycle.toSet().size)
        } finally { scope.cancel() }
    }
}
