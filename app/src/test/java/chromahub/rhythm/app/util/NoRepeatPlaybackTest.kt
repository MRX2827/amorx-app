package chromahub.rhythm.app.util

import amorx.playback.uniquePlaybackTracks
import org.junit.Assert.assertEquals
import org.junit.Test

class NoRepeatPlaybackTest {
    private data class Entry(val id: String)

    @Test fun shuffleTreatsStreamAndDownloadAsOneSong() {
        val id = "dQw4w9WgXcQ"
        val songs = listOf(Entry("online_$id"), Entry("22"), Entry("offline_$id"), Entry("22"))
        val queue = uniquePlaybackTracks(songs, Entry::id)
        assertEquals(listOf("online_$id", "22"), queue.map { it.id })
    }

    @Test fun normalizationKeepsCurrentPlaybackEntryAndItsRelativePosition() {
        val id = "dQw4w9WgXcQ"
        val songs = listOf(Entry("online_$id"), Entry("22"), Entry("offline_$id"), Entry("33"))
        val queue = uniquePlaybackTracks(songs, Entry::id, "offline_$id")
        assertEquals(listOf("22", "offline_$id", "33"), queue.map { it.id })
    }
}
