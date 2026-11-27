package chromahub.rhythm.app.util

import chromahub.rhythm.app.network.LRCLibApiService
import chromahub.rhythm.app.network.LrcLibLyrics
import chromahub.rhythm.app.shared.data.model.LyricsData
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException

/** Metadata from video search is often "artist - song (Official Video)", with a channel as artist. */
internal object PlayerLyricsLookup {
    @Volatile private var retryAtNanos = 0L
    fun canSearch() = System.nanoTime() >= retryAtNanos
    data class Query(val artist: String, val title: String)
    private val videoTag = Regex("(?i)\\s*[\\[(][^\\])]*\\b(?:official|music video|audio|lyrics?|lyric video|hd|4k|720p|1080p|визуализатор|клип|текст)\\b[^\\])]*[\\])]\\s*")
    private val videoSuffix = Regex("(?i)\\s*[-–—|]\\s*(?:official(?: music)?(?: video| audio)?|lyrics?|hd|4k|720p|1080p)\\s*$")
    private val artistSuffix = Regex("(?i)(?:\\s*-\\s*Topic|VEVO|\\s+Official)\\s*$")
    private fun cleanTitle(value: String) = videoSuffix.replace(videoTag.replace(value, " "), "")
        .replace(Regex("\\s+"), " ").trim()
    private fun cleanArtist(value: String) = artistSuffix.replace(value, "").trim()
    private fun key(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .filter { it.isLetterOrDigit() }

    fun queries(artist: String, title: String, video: Boolean): List<Query> {
        val cleanArtist = cleanArtist(artist)
        val cleanTitle = cleanTitle(title)
        val split = cleanTitle.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        val inferred = if (split.size == 2 && (video || key(split[0]) == key(cleanArtist)))
            Query(cleanArtist(split[0]), split[1].trim()) else null
        return listOfNotNull(inferred, Query(cleanArtist, cleanTitle))
            .filter { it.artist.isNotBlank() && it.title.isNotBlank() }.distinct().take(2)
    }

    fun bestMatch(results: List<LrcLibLyrics>, query: Query, durationMs: Long): LrcLibLyrics? {
        val artist = key(query.artist); val title = key(query.title)
        if (artist.isEmpty() || title.isEmpty()) return null
        return results.filter { row ->
            val rowArtist = key(row.artistName.orEmpty())
            val rowTitle = key(row.trackName.orEmpty())
            val artistMatch = rowArtist == artist || (minOf(rowArtist.length, artist.length) >= 3 &&
                (rowArtist.contains(artist) || artist.contains(rowArtist)))
            row.hasLyrics() && rowTitle == title && artistMatch
        }.sortedWith(compareBy<LrcLibLyrics> { row ->
            if (durationMs > 0 && row.duration != null) kotlin.math.abs(row.duration * 1000 - durationMs) > 12_000 else false
        }.thenByDescending { it.hasSyncedLyrics() }.thenBy { row ->
            if (durationMs > 0 && row.duration != null) kotlin.math.abs(row.duration * 1000 - durationMs) else 0.0
        }).firstOrNull()
    }

    suspend fun find(service: LRCLibApiService?, artist: String, title: String, video: Boolean, durationMs: Long): LyricsData? {
        if (service == null || !canSearch()) return null
        for ((index, query) in queries(artist, title, video).withIndex()) {
            if (index > 0) delay(250)
            val rows = try {
                service.searchLyrics(trackName = query.title, artistName = query.artist)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: HttpException) {
                // Do not issue another query after a rate limit or temporary server failure.
                if (error.code() == 429 || error.code() >= 500) {
                    val seconds = error.response()?.headers()?.get("Retry-After")?.toLongOrNull()
                        ?.coerceIn(1, 31_536_000) ?: if(error.code() == 429) 60L else 5L
                    retryAtNanos = System.nanoTime() + seconds * 1_000_000_000L
                    return null
                }
                emptyList()
            } catch (_: Exception) { return null }
            val match = bestMatch(rows, query, durationMs) ?: continue
            return LyricsData(match.getPlainLyricsOrNull(), match.getSyncedLyricsOrNull(), source = "LRCLib")
        }
        return null
    }
}
