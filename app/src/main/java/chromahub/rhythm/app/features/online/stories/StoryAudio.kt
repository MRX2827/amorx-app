package chromahub.rhythm.app.features.online.stories

import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.CloudSong
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Звук историй-песен. Сами аудиофайлы мы нигде не храним: в истории лежит только песня и выбранный фрагмент.
 * Ссылку на звук находим заранее, пока человек листает ленту, поэтому фрагмент начинает играть сразу.
 */
internal object StoryAudio {
    /** Значения по умолчанию для старых историй без выбранного фрагмента. */
    const val DEFAULT_CLIP_MS = 15_000L
    const val MIN_CLIP_MS = 5_000L
    const val MAX_CLIP_MS = 30_000L

    private const val TTL_MS = 25 * 60_000L
    private val cache = HashMap<String, Pair<Long, Song>>()
    private val gate = Semaphore(2)

    private fun keyOf(song: CloudSong) = song.id + "|" + song.title

    @Synchronized
    fun cached(song: CloudSong): Song? {
        val k = keyOf(song)
        val e = cache[k] ?: return null
        return if (System.currentTimeMillis() - e.first < TTL_MS) e.second else { cache.remove(k); null }
    }

    @Synchronized
    private fun put(song: CloudSong, found: Song) {
        cache[keyOf(song)] = System.currentTimeMillis() to found
        if (cache.size > 40) cache.entries.minByOrNull { it.value.first }?.let { cache.remove(it.key) }
    }

    /** Песня, готовая к воспроизведению (с прямой ссылкой на звук), или null, если не нашли. */
    suspend fun resolve(song: CloudSong): Song? {
        cached(song)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                cached(song) ?: runCatching { CloudProfiles.resolveSong(song) }
                    .getOrNull()
                    ?.also { put(song, it) }
            }
        }
    }

    /** Заранее готовит звук для первых историй-песен ленты. Ошибки молча пропускаются. */
    suspend fun prefetch(stories: List<Story>) {
        stories.asSequence()
            .mapNotNull { it.song }
            .distinctBy { keyOf(it) }
            .filter { cached(it) == null }
            .take(8)
            .forEach { resolve(it) }
    }
}
