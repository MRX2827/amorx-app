package amorx

import amorx.playback.NoRepeatShuffle
import amorx.playback.playbackIdentity
import amorx.playback.uniquePlaybackTracks
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class NoRepeatShuffleTest {
    @Test fun everyCycleContainsEverySongOnceAndDoesNotRepeatAtBoundary() {
        for (size in listOf(1, 2, 3, 7, 100)) for (seed in 0..40) {
            val ids = (0 until size).map { "song-$it" }
            val shuffle = NoRepeatShuffle(Random(seed))
            var current = ids.first()
            shuffle.reset(ids, current)
            val played = mutableListOf(current)
            repeat(size * 12 - 1) {
                val next = shuffle.next(ids, current)!!
                if (size > 1) assertNotEquals(current, next)
                played += next
                current = next
            }
            played.chunked(size).forEach { assertEquals(ids.toSet(), it.toSet()) }
        }
    }

    @Test fun backAndForwardDoNotReintroducePlayedSongsIntoTheBag() {
        val ids = (0..9).map { "song-$it" }
        val shuffle = NoRepeatShuffle(Random(15))
        shuffle.reset(ids, ids[0])
        val first = shuffle.next(ids, ids[0])!!
        val second = shuffle.next(ids, first)!!
        assertEquals(first, shuffle.previous(ids))
        assertEquals(second, shuffle.next(ids, first))
        val rest = (1..7).map { shuffle.next(ids, second)!! }
        assertEquals(ids.toSet(), (listOf(ids[0], first, second) + rest).toSet())
        assertEquals(0, shuffle.remainingCount)
    }

    @Test fun additionsArePlayedBeforeStartingAnotherCycleAndRemovedSongsNeverPlay() {
        val shuffle = NoRepeatShuffle(Random(42))
        shuffle.reset(listOf("a", "b", "c"), "a")
        assertEquals("b", shuffle.next(listOf("a", "b"), "a"))
        assertEquals("d", shuffle.next(listOf("a", "b", "d"), "b"))
        assertNull(shuffle.next(emptyList(), "d"))
    }

    @Test fun onlineAndDownloadedAliasesOnlyCountOnceAndCurrentCopyStaysAnchored() {
        val id = "dQw4w9WgXcQ"
        val input = listOf("online_$id", "other", "offline_$id", "other")
        assertEquals(listOf("other", "offline_$id"), uniquePlaybackTracks(input, { it }, "offline_$id"))
        val shuffle = NoRepeatShuffle(Random(1))
        shuffle.reset(input, "offline_$id")
        assertEquals("other", shuffle.next(input, "offline_$id"))
        assertEquals(id, playbackIdentity("online_$id"))
    }
}
