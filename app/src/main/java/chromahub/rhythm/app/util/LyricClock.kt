package chromahub.rhythm.app.util

/** Uses the player's position in milliseconds, never a ratio multiplied by a separate duration. */
internal object LyricClock {
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
}
