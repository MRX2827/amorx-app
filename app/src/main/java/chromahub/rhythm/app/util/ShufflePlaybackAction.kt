package chromahub.rhythm.app.util

import amorx.playback.playbackIdentity

enum class ShufflePlaybackAction { StartQueue, EnableCurrent, KeepCurrent, WaitForPending }

/** A playlist shuffle button starts/resumes a mode; it must not rebuild an active listening cycle. */
fun shufflePlaybackAction(
    requestedIds: List<String>, activeIds: List<String>, enabled: Boolean,
    ended: Boolean, pendingIds: Set<String>? = null, pendingShuffle: Boolean = true
): ShufflePlaybackAction {
    val requested = requestedIds.map(::playbackIdentity).toSet()
    if (pendingIds != null) return if (requested.isNotEmpty() && pendingIds == requested && pendingShuffle) {
        ShufflePlaybackAction.WaitForPending
    } else ShufflePlaybackAction.StartQueue
    val active = activeIds.map(::playbackIdentity).toSet()
    if (ended || requested.isEmpty() || active != requested) return ShufflePlaybackAction.StartQueue
    return if (enabled) ShufflePlaybackAction.KeepCurrent else ShufflePlaybackAction.EnableCurrent
}
