package chromahub.rhythm.app.util

import chromahub.rhythm.app.network.LrcLibLyrics
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.presentation.screens.player.stageLines
import org.junit.Assert.*
import org.junit.Test

class LyricsPlaybackTimingTest {
    @Test fun instrumentalCuesAndExplicitEndsDoNotKeepThePreviousVerseActive() {
        val starts = longArrayOf(21_000, 25_000, 45_000, 48_000)
        val ends = longArrayOf(24_000, 25_000, 47_000, 48_000)
        assertEquals(-1, LyricClock.activeIndex(starts, ends, 20_999, 0))
        assertEquals(0, LyricClock.activeIndex(starts, ends, 21_000, 0))
        assertEquals(-1, LyricClock.activeIndex(starts, ends, 24_000, 0))
        assertEquals(-1, LyricClock.activeIndex(starts, ends, 30_000, 0))
        assertEquals(2, LyricClock.activeIndex(starts, ends, 45_000, 0))
        assertEquals(-1, LyricClock.activeIndex(starts, ends, 60_000, 0))
        // A real seek or repeat must still be able to return to an earlier verse.
        assertEquals(0, LyricClock.activeIndex(starts, ends, 21_000, 0))
        assertEquals(0, LyricClock.activeIndex(starts, ends, 20_800, 200))
    }

    @Test fun wordTimingStartsAtTheFirstWordRatherThanProviderPreroll() {
        val json = """[{"timestamp":0,"endtime":24000,"text":[{"text":"Verse","timestamp":21000,"endtime":24000}]}]"""
        val line = stageLines(LyricsData(null, null, json)).single()
        assertEquals(21_000L, line.time)
        assertEquals(24_000L, line.endTime)
    }

    @Test fun blankLrcCuesAndRepeatedChorusesArePreserved() {
        val lines = stageLines(LyricsData(null, "[00:21.00]Chorus\n[00:25.00]\n[00:45.00]Chorus\n[00:48.00]"))
        assertEquals(listOf(21_000L, 25_000L, 45_000L, 48_000L), lines.map { it.time })
        assertTrue(lines[1].text.isBlank())
        assertEquals("Chorus", lines[2].text)
    }

    @Test fun anotherRecordingCanSupplyWordsButCannotDriveHighlighting() {
        val row = LrcLibLyrics(1, trackName="Song", artistName="Artist", albumName=null,
            duration=257.0, plainLyrics=null, syncedLyrics="[00:01.00]Verse\n[00:03.00]Chorus")
        val data = PlayerLyricsLookup.recordingLyrics(row, 313_000)
        assertEquals("Verse\nChorus", data.plainLyrics)
        assertFalse(data.hasSyncedLyrics())
        assertFalse(PlayerLyricsLookup.recordingLyrics(row, 0).hasSyncedLyrics())
        assertTrue(PlayerLyricsLookup.recordingLyrics(row, 257_000).hasSyncedLyrics())
        assertTrue(PlayerLyricsLookup.needsTimingRefresh(LyricsData(null, row.syncedLyrics, source="LRCLib")))
        assertFalse(PlayerLyricsLookup.needsTimingRefresh(LyricsData(null, row.syncedLyrics, source="Local File")))
    }
}
