package chromahub.rhythm.app.features.online

import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Лента треков одного артиста: по очереди пробует разные поисковые запросы. */
private class ArtistFeed(val name: String) {
    private var variants = freshVariants()
    private val buffer = ArrayList<OnlineTrack>()
    private var loaded = 0

    private fun freshVariants() = ArrayDeque(
        listOf(
            name,
            "$name песни",
            "$name лучшие песни",
            "$name популярные треки",
            "$name hits",
            "$name official audio",
            "$name альбом",
            "$name live"
        )
    )

    fun reset() {
        variants = freshVariants()
        buffer.clear()
        loaded = 0
    }

    private fun relevant(t: OnlineTrack): Boolean =
        t.durationMs in 45_000L..(9 * 60_000L) &&
            (t.artist.contains(name, ignoreCase = true) || t.title.contains(name, ignoreCase = true))

    private suspend fun loadNext(exclude: Set<String>): Boolean {
        val query = variants.removeFirstOrNull() ?: return false
        val found = runCatching { OnlineRepository.search(query) }.getOrDefault(emptyList())
        val good = found.filter { relevant(it) }
        // Если имя на YouTube записано иначе, берём первые результаты как есть
        val pick = if (loaded == 0 && good.isEmpty()) found.take(5) else good
        loaded++
        for (t in pick.shuffled()) {
            if (t.id !in exclude && buffer.none { it.id == t.id }) buffer.add(t)
        }
        return true
    }

    suspend fun prefetch(exclude: Set<String>) {
        if (loaded == 0) loadNext(exclude)
    }

    suspend fun next(exclude: Set<String>): OnlineTrack? {
        while (true) {
            val idx = buffer.indexOfFirst { it.id !in exclude }
            if (idx >= 0) return buffer.removeAt(idx)
            if (!loadNext(exclude)) return null
        }
    }
}

/** Бесконечная подборка для списка рекомендаций: помнит, что уже показано, и умеет давать следующую порцию. */
class RecsFeed(artists: List<WaveArtist>) {
    private val feeds = artists.map { ArtistFeed(it.name) }.shuffled()
    private val seen = HashSet<String>()
    private var rr = 0
    private var started = false

    suspend fun next(count: Int): List<OnlineTrack> {
        if (feeds.isEmpty()) return emptyList()
        if (!started) {
            started = true
            coroutineScope {
                feeds.take(6).map { f -> async { f.prefetch(seen) } }.awaitAll()
            }
        }
        var out = pick(count)
        if (out.isEmpty()) {
            // всё показано: начинаем круг заново
            seen.clear()
            feeds.forEach { it.reset() }
            rr = 0
            out = pick(count)
        }
        return out
    }

    private suspend fun pick(count: Int): List<OnlineTrack> {
        val picked = ArrayList<OnlineTrack>()
        var emptyRounds = 0
        while (picked.size < count && feeds.isNotEmpty() && emptyRounds < feeds.size) {
            val feed = feeds[rr % feeds.size]
            rr++
            val track = feed.next(seen)
            if (track == null) {
                emptyRounds++
            } else {
                emptyRounds = 0
                seen.add(track.id)
                picked.add(track)
            }
        }
        return picked
    }
}

object OnlineWave {
    private const val FIRST_BATCH = 5
    private const val REFILL_BATCH = 8
    private const val REFILL_AT = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var feeds: List<ArtistFeed> = emptyList()
    private val played = HashSet<String>()
    private val waveSongIds = HashSet<String>()
    private var monitor: Job? = null
    private var refillJob: Job? = null
    private var refilling = false
    private var rr = 0

    fun stop() {
        monitor?.cancel()
        monitor = null
        refillJob?.cancel()
        refillJob = null
        refilling = false
    }

    /** Собирает первую пачку песен выбранных артистов. Продолжение подгружается само после begin(). */
    suspend fun buildSongs(artists: List<WaveArtist>, size: Int = FIRST_BATCH): List<Song> {
        stop()
        feeds = artists.map { ArtistFeed(it.name) }.shuffled()
        played.clear()
        waveSongIds.clear()
        rr = 0
        coroutineScope {
            feeds.take(6).map { f -> async { f.prefetch(played) } }.awaitAll()
        }
        return nextBatch(size)
    }

    /** Запускает воспроизведение и следит за очередью, подкидывая новые песни. */
    fun begin(vm: MusicViewModel, songs: List<Song>) {
        if (songs.isEmpty()) return
        monitor?.cancel()
        vm.playQueue(songs, enableShuffle = false)
        var seenWave = false
        monitor = scope.launch {
            combine(vm.currentQueue, vm.currentSong) { q, cur -> q to cur }
                .collect { (queue, cur) ->
                    if (cur == null) return@collect
                    if (cur.id !in waveSongIds) {
                        // Пользователь включил что-то своё: волна заканчивается
                        if (seenWave) stop()
                        return@collect
                    }
                    seenWave = true
                    val idx = queue.songs.indexOfFirst { it.id == cur.id }
                    if (idx < 0) return@collect
                    val remaining = queue.songs.size - 1 - idx
                    if (remaining <= REFILL_AT && !refilling) refill(vm)
                }
        }
    }

    private fun refill(vm: MusicViewModel) {
        refilling = true
        refillJob = scope.launch {
            try {
                var songs = nextBatch(REFILL_BATCH)
                if (songs.isEmpty()) {
                    // Всё прослушано: начинаем круг заново
                    played.clear()
                    feeds.forEach { it.reset() }
                    songs = nextBatch(REFILL_BATCH)
                }
                if (monitor?.isActive == true) {
                    songs.forEach { vm.addSongToQueue(it, silent = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // следующая проверка очереди попробует снова
            } finally {
                refilling = false
            }
        }
    }

    /** Подборка для списка рекомендаций: только метаданные, без ссылок на аудио. */
    suspend fun recommend(artists: List<WaveArtist>, size: Int = 20): List<OnlineTrack> {
        val local = artists.map { ArtistFeed(it.name) }.shuffled()
        val seen = HashSet<String>()
        val out = ArrayList<OnlineTrack>()
        coroutineScope {
            local.take(6).map { f -> async { f.prefetch(seen) } }.awaitAll()
        }
        var i = 0
        var empty = 0
        while (out.size < size && local.isNotEmpty() && empty < local.size) {
            val feed = local[i % local.size]
            i++
            val track = feed.next(seen)
            if (track == null) {
                empty++
            } else {
                empty = 0
                seen.add(track.id)
                out.add(track)
            }
        }
        return out
    }

    private suspend fun pickTracks(count: Int): List<OnlineTrack> {
        val picked = ArrayList<OnlineTrack>()
        var emptyRounds = 0
        while (picked.size < count && feeds.isNotEmpty() && emptyRounds < feeds.size) {
            val feed = feeds[rr % feeds.size]
            rr++
            val track = feed.next(played)
            if (track == null) {
                emptyRounds++
            } else {
                emptyRounds = 0
                played.add(track.id)
                picked.add(track)
            }
        }
        return picked
    }

    private suspend fun nextBatch(size: Int): List<Song> = coroutineScope {
        val gate = Semaphore(4)
        val result = ArrayList<Song>()
        var attempts = 0
        while (result.size < size && attempts < 3) {
            attempts++
            val tracks = pickTracks(size - result.size + 2)
            if (tracks.isEmpty()) break
            val songs = tracks.map { t ->
                async {
                    gate.withPermit {
                        runCatching { t.toSong(OnlineRepository.resolveAudioUrl(t.videoUrl)) }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
            result.addAll(songs)
        }
        val out = result.take(size)
        out.forEach { waveSongIds.add(it.id) }
        out
    }
}
