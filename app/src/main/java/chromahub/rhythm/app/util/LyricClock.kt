package chromahub.rhythm.app.util

/** Uses the player's position in milliseconds, never a ratio multiplied by a separate duration. */
internal object LyricClock {
    const val DISPLAY_LEAD_MS = 600L
    fun presentationOffset(manualOffsetMs: Long): Long = manualOffsetMs + DISPLAY_LEAD_MS

    fun index(timestamps: LongArray, positionMs: Long, offsetMs: Long): Int {
        val time = positionMs.coerceAtLeast(0) + offsetMs
        var low = 0; var high = timestamps.lastIndex; var result = -1
        while(low <= high) {
            val middle = (low + high) ushr 1
            if(timestamps[middle] <= time) { result = middle; low = middle + 1 } else high = middle - 1
        }
        return result
    }
    fun seek(lyricMs: Long, offsetMs: Long, durationMs: Long): Long =
        (lyricMs - offsetMs).coerceAtLeast(0).let { if(durationMs > 0) it.coerceAtMost(durationMs) else it }

    /** Explicit ends and blank cues are silence, not a reason to keep singing the last line. */
    fun activeIndex(timestamps: LongArray, ends: LongArray, positionMs: Long, offsetMs: Long): Int {
        val index = index(timestamps, positionMs, offsetMs)
        if (index < 0) return -1
        val end = ends.getOrNull(index) ?: Long.MAX_VALUE
        return if (positionMs.coerceAtLeast(0) + offsetMs < end) index else -1
    }

    /** Reveal the next line early, but keep the current vocal active until its real end. */
    fun presentationIndex(timestamps: LongArray, ends: LongArray, positionMs: Long, offsetMs: Long): Int {
        var selected = index(timestamps,positionMs,presentationOffset(offsetMs))
        var silenceAt = Long.MAX_VALUE
        while(selected >= 0 && (ends.getOrNull(selected) ?: Long.MAX_VALUE) <= timestamps[selected]) {
            silenceAt = minOf(silenceAt,timestamps[selected])
            selected--
        }
        if(selected < 0) return -1
        val end = minOf(ends.getOrNull(selected) ?: Long.MAX_VALUE,silenceAt)
        return if(positionMs.coerceAtLeast(0)+offsetMs < end) selected else -1
    }
}

/** Session estimates can move slightly backwards when a fresh service update arrives.
 * Only a real player discontinuity (seek, repeat, transition, reconnect) starts a new clock.
 * Never extrapolate here: paused or buffering audio must not advance the lyrics.
 */
internal class LyricPositionTracker {
    private var epoch: Long? = null
    private var position = 0L

    fun sample(positionMs: Long, discontinuity: Long): Long {
        val incoming = positionMs.coerceAtLeast(0)
        position = if(epoch != discontinuity) incoming else maxOf(position, incoming)
        epoch = discontinuity
        return position
    }
}
