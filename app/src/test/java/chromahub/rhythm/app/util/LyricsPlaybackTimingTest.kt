package chromahub.rhythm.app.util

import chromahub.rhythm.app.network.LrcLibLyrics
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.shared.presentation.screens.player.stageLines
import chromahub.rhythm.app.shared.presentation.screens.player.StageLine
import chromahub.rhythm.app.shared.presentation.screens.player.StageTimeline
import chromahub.rhythm.app.shared.presentation.screens.player.StageCue
import org.junit.Assert.*
import org.junit.Test

class LyricsPlaybackTimingTest {
    @Test fun shortBreathsKeepTheCompletedLineWithoutFlashingMusicOrFutureWords() {
        val timeline = StageTimeline(listOf(StageLine(21_000,"Verse",24_000),
            StageLine(24_000,""), StageLine(25_000,"Chorus",28_000),
            StageLine(28_000,""), StageLine(45_000,"Verse",48_000)))
        assertEquals(StageCue(), timeline.cue(20_999,0))
        assertTrue(timeline.history(timeline.cue(20_999,0)).isEmpty())
        assertEquals(StageCue(0,true), timeline.cue(23_999,0))
        assertEquals(StageCue(0,false), timeline.cue(24_000,0))
        assertEquals(StageCue(0,false), timeline.cue(24_999,0))
        assertEquals(StageCue(2,true), timeline.cue(25_000,0))
        assertEquals(listOf("Verse"),timeline.history(timeline.cue(25_000,0)).map { it.text })
        assertEquals(StageCue(), timeline.cue(28_000,0)) // real instrumental, not a breath
        assertEquals(StageCue(4,true), timeline.cue(45_000,0)) // legitimate repeated verse
    }

    @Test fun serviceClockCorrectionsDoNotReplayAVerseButRealSeeksAndRepeatDo() {
        val clock = LyricPositionTracker()
        assertEquals(999L,clock.sample(999,0))
        assertEquals(1020L,clock.sample(1020,0))
        assertEquals(1020L,clock.sample(980,0)) // service estimate corrected at a cue boundary
        assertEquals(1020L,clock.sample(1020,0)) // pause/buffering must not invent elapsed time
        assertEquals(1030L,clock.sample(1030,0))
        assertEquals(200L,clock.sample(200,1)) // real backward seek
        assertEquals(220L,clock.sample(220,1))
        assertEquals(0L,clock.sample(0,2)) // repeat/reconnect
    }

    @Test fun frameSequenceCannotFlashThePreviousVerseAtASessionUpdateBoundary() {
        val clock = LyricPositionTracker()
        val timeline = StageTimeline(listOf(StageLine(1_000,"First",1_999),StageLine(2_000,"Second",3_000)))
        val cues = listOf(999L,1_000L,1_998L,1_999L,2_001L,1_980L,2_002L)
            .map { timeline.cue(clock.sample(it,0),0).index }
        assertEquals(listOf(-1,0,0,0,1,1,1),cues)
        assertEquals(StageCue(0,true),timeline.cue(clock.sample(1_200,1),0))
    }

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

    @Test fun blankGapEndAtTheVocalTimestampCannotHideTheVocal() {
        val lines = stageLines(LyricsData(null, "[00:10.00]\n[00:10.00]Verse\n[00:12.00]Chorus"))
        assertEquals("Verse", lines.first().text)
        assertEquals(StageCue(0,true),StageTimeline(lines).cue(10_000,0))
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
