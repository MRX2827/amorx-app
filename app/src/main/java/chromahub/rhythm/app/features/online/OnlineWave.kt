package chromahub.rhythm.app.features.online

import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object OnlineWave {

    /** Собирает перемешанную подборку из выбранных артистов и сразу получает ссылки на аудио. */
    suspend fun buildSongs(artists: List<WaveArtist>, size: Int = 8): List<Song> = coroutineScope {
        val picked = artists.shuffled().take(6)
        if (picked.isEmpty()) return@coroutineScope emptyList<Song>()
        val perArtist = maxOf(3, (size + 3 + picked.size - 1) / picked.size)

        val groups: List<List<OnlineTrack>> = picked.map { artist ->
            async {
                val found = runCatching { OnlineRepository.search(artist.name) }
                    .getOrDefault(emptyList())
                val mine = found
                    .filter { it.artist.contains(artist.name, ignoreCase = true) }
                    .ifEmpty { found.take(3) }
                mine.take(15).shuffled().take(perArtist)
            }
        }.awaitAll()

        // Чередуем артистов, чтобы песни одного исполнителя не шли подряд
        val mixed = ArrayList<OnlineTrack>()
        var i = 0
        while (mixed.size < size + 3 && groups.any { i < it.size }) {
            groups.forEach { g -> if (i < g.size) mixed.add(g[i]) }
            i++
        }
        val candidates = mixed.distinctBy { it.videoUrl }

        val gate = Semaphore(4)
        candidates.map { track ->
            async {
                gate.withPermit {
                    runCatching { track.toSong(OnlineRepository.resolveAudioUrl(track.videoUrl)) }
                        .getOrNull()
                }
            }
        }.awaitAll().filterNotNull().take(size)
    }
}
