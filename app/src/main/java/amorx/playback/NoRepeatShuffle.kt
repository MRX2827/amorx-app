package amorx.playback

import kotlin.random.Random

private val videoIdentity = Regex("[A-Za-z0-9_-]{11}")

/** Online and downloaded copies of the same YouTube song have one playback identity. */
fun playbackIdentity(id: String): String {
    val stripped = id.removePrefix("online_").removePrefix("offline_")
    return if (videoIdentity.matches(stripped)) stripped else id
}

fun <T> uniquePlaybackTracks(items: List<T>, id: (T) -> String, preferredId: String? = null): List<T> {
    val result = linkedMapOf<String, T>()
    val preferredKey = preferredId?.takeIf { wanted -> items.any { id(it) == wanted } }?.let(::playbackIdentity)
    items.forEach { item ->
        val itemId = id(item)
        val key = playbackIdentity(itemId)
        if (preferredKey == key && itemId != preferredId) return@forEach
        result.putIfAbsent(key, item)
    }
    return result.values.toList()
}

/** A shuffled bag per complete cycle, with independent back/forward playback history. */
class NoRepeatShuffle(private val random: Random = Random.Default) {
    private val played = hashSetOf<String>()
    private val remaining = mutableListOf<String>()
    private val history = mutableListOf<String>()
    private var cursor = -1
    val remainingCount get() = remaining.size

    fun reset(ids: List<String>, current: String?) {
        played.clear()
        remaining.clear()
        history.clear()
        cursor = -1
        sync(ids)
        current?.let(::select)
    }

    fun select(id: String) {
        val key = playbackIdentity(id)
        played += key
        remaining.remove(key)
        if (history.getOrNull(cursor) == key) return
        while (history.size > cursor + 1) history.removeAt(history.lastIndex)
        history += key
        cursor = history.lastIndex
        if (history.size > 4096) { history.removeAt(0); cursor-- }
    }

    fun next(ids: List<String>, current: String?): String? {
        val keys = sync(ids)
        if (keys.isEmpty()) return null
        val currentKey = current?.let(::playbackIdentity)
        if (cursor < 0 && currentKey != null) select(currentKey)
        while (cursor + 1 < history.size) {
            val forward = history[++cursor]
            if (forward in keys) return forward
        }
        if (remaining.isEmpty()) {
            played.clear()
            remaining += keys.shuffled(random)
            // Every new cycle contains all songs; only its first item must differ from the last song.
            if (remaining.size > 1 && remaining.first() == currentKey) {
                val swap = random.nextInt(1, remaining.size)
                remaining[0] = remaining[swap].also { remaining[swap] = remaining[0] }
            }
        }
        val next = remaining.removeAt(0)
        select(next)
        return next
    }

    fun previous(ids: List<String>): String? {
        val keys = sync(ids)
        while (cursor > 0) {
            val previous = history[--cursor]
            if (previous in keys) return previous
        }
        return null
    }

    private fun sync(ids: List<String>): Set<String> {
        val keys = ids.map(::playbackIdentity).toCollection(linkedSetOf())
        played.retainAll(keys)
        remaining.removeAll { it !in keys }
        val pending = remaining.toHashSet()
        remaining += keys.filter { it !in played && it !in pending }.shuffled(random)
        return keys
    }
}
