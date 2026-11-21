package chromahub.rhythm.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ShufflePlaybackActionTest {
    private val songs = (1..295).map { "song-$it" }

    @Test fun repeatedPlaylistButtonPressesKeepTheCurrentListeningCycle() {
        repeat(100) {
            assertEquals(ShufflePlaybackAction.KeepCurrent,
                shufflePlaybackAction(songs, songs.reversed(), enabled = true, ended = false))
        }
    }
    @Test fun enablesShuffleOnTheExistingPlaylistWithoutRestartingTheSong() {
        assertEquals(ShufflePlaybackAction.EnableCurrent, shufflePlaybackAction(songs, songs, false, false))
    }
    @Test fun notificationEnabledShuffleIsTheSameActiveModeAsThePlaylistButton() {
        assertEquals(ShufflePlaybackAction.KeepCurrent, shufflePlaybackAction(songs, songs, true, false))
    }
    @Test fun pendingShuffleIsNotStartedAgainByRapidTaps() {
        assertEquals(ShufflePlaybackAction.WaitForPending, shufflePlaybackAction(songs, emptyList(), false, false, songs.toSet()))
    }
    @Test fun shuffleRequestSupersedesAPendingOrderedPlaybackRequest() {
        assertEquals(ShufflePlaybackAction.StartQueue, shufflePlaybackAction(songs, songs, true, false, songs.toSet(), false))
    }
    @Test fun differentPlaylistOrCompletedPlaybackStartsANewQueue() {
        assertEquals(ShufflePlaybackAction.StartQueue, shufflePlaybackAction(songs, listOf("other"), true, false))
        assertEquals(ShufflePlaybackAction.StartQueue, shufflePlaybackAction(songs, songs, true, true))
    }
    @Test fun onlineAndDownloadedCopiesResolveToTheSamePlaylist() {
        assertEquals(ShufflePlaybackAction.KeepCurrent,
            shufflePlaybackAction(listOf("offline_dQw4w9WgXcQ"), listOf("online_dQw4w9WgXcQ"), true, false))
    }
}
