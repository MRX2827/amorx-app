package chromahub.rhythm.app.util

import chromahub.rhythm.app.network.LRCLibApiService
import chromahub.rhythm.app.network.LrcLibLyrics
import chromahub.rhythm.app.network.PlainLyricsApiService
import chromahub.rhythm.app.shared.data.model.LyricsData
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException

/** Search variants are shared by automatic lookup, provider matching and manual corrections. */
internal object PlayerLyricsLookup {
    private const val RECORDING_SOURCE = "LRCLib · recording match"
    fun needsTimingRefresh(data: LyricsData): Boolean = data.source == "LRCLib" && data.hasSyncedLyrics()

    private fun matchesRecording(row: LrcLibLyrics, durationMs: Long): Boolean {
        val seconds = row.duration ?: return false
        return durationMs > 0 && seconds.isFinite() && kotlin.math.abs(seconds * 1000 - durationMs) <= 3000
    }

    /** Do not run another recording's timeline over this audio or stretch it to fit. */
    fun recordingLyrics(row: LrcLibLyrics, durationMs: Long): LyricsData {
        val synced = row.getSyncedLyricsOrNull()?.takeIf { matchesRecording(row, durationMs) }
        val plain = row.getPlainLyricsOrNull() ?: row.getSyncedLyricsOrNull()?.lineSequence()
            ?.map { it.replace(Regex("\\[[^]]*]|<\\d[^>]*>"), "").trim() }
            ?.filter { it.isNotBlank() }?.joinToString("\n")
        return LyricsData(plain, synced, source = RECORDING_SOURCE)
    }
    @Volatile private var retryAtNanos = 0L
    @Volatile private var plainRetryAtNanos = 0L
    fun canSearch() = System.nanoTime() >= retryAtNanos
    data class Query(val artist: String, val title: String, val edition: String? = null)
    private data class Attempt(val query: Query, val generic: Boolean = false, val titleOnly: Boolean = false)
    private val videoTag = Regex("(?i)\\s*[\\[(][^\\])]*\\b(?:official|music video|audio|lyrics?|lyric video|hd|4k|\\d{3,4}p|визуализатор|клип|текст|премьера)[^\\])]*[\\])]\\s*")
    private val videoSuffix = Regex("(?i)\\s*[-–—|]\\s*(?:official(?: music)?(?: video| audio)?|lyrics?|hd|4k|\\d{3,4}p)\\s*$")
    private val artistSuffix = Regex("(?i)(?:\\s*[-–—]\\s*Topic|VEVO|\\s+Official)\\s*$")
    private val feature = Regex("(?i)\\s*[\\[(]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+[^\\])]*[\\])]|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$")
    private val editionTag = Regex("(?i)\\s*[\\[(]\\s*(?:dirty|dyrty|explicit|clean|uncensored|censored|official version|original version)\\s*[\\])]\\s*")
    private val editionSuffix = Regex("(?i)\\s*[-–—|]\\s*(?:dirty|dyrty|explicit|clean|uncensored|censored|official version|original version)\\s*$")
    private val trackNumber = Regex("^(?:\\d{1,3}[.)]\\s*|0\\d\\s+|\\d{1,3}\\s+-\\s+)")
    private val separator = Regex("\\s+[-–—]\\s*|\\s*[-–—]\\s+|[–—]")
    private val collaborators = Regex("(?i)\\s*(?:,|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b)\\s*|\\s+(?:x|и|with)\\s+")

    private fun cleanArtist(value: String) = artistSuffix.replace(value, "").trim()
    private fun videoName(value: String): String {
        val text = videoSuffix.replace(videoTag.replace(value, " "), "")
        return trackNumber.replace(editionSuffix.replace(editionTag.replace(text, " "), ""), "")
            .replace(Regex("\\s+"), " ").trim()
    }
    private fun cleanTitle(value: String) = feature.replace(videoName(value), "").trim()

    private fun romanize(value: String, alternate: Boolean = false): String = buildString {
        for (char in value.lowercase(Locale.ROOT)) append(when(char) {
            'а' -> "a"; 'б' -> "b"; 'в' -> "v"; 'г','ґ','ғ' -> "g"; 'д' -> "d"; 'е','э' -> "e"
            'ё' -> if(alternate) "yo" else "e"; 'ж' -> "zh"; 'з' -> "z"; 'и','і' -> "i"
            'й' -> if(alternate) "i" else "y"; 'к' -> "k"; 'қ' -> "q"; 'л' -> "l"; 'м' -> "m"
            'н' -> "n"; 'ң' -> "ng"; 'о','ө' -> "o"; 'п' -> "p"; 'р' -> "r"; 'с' -> "s"; 'т' -> "t"
            'у','ү','ұ' -> "u"; 'ф' -> "f"; 'х' -> if(alternate) "h" else "kh"; 'һ' -> "h"
            'ц' -> "ts"; 'ч' -> "ch"; 'ш' -> "sh"; 'щ' -> "shch"; 'ы' -> "y"; 'ь','ъ' -> ""
            'ю' -> "yu"; 'я' -> "ya"; 'ї' -> "yi"; 'є' -> "ye"; 'ә' -> "a"; else -> char.toString()
        })
    }
    private fun key(value: String, alternate: Boolean = false) = Normalizer.normalize(romanize(value, alternate), Normalizer.Form.NFKD)
        .filter { it.isLetterOrDigit() }
    private fun keys(value: String) = setOf(key(value), key(value, true)).filter { it.isNotEmpty() }.toSet()
    private fun artistKeys(value: String) = collaborators.split(cleanArtist(value).replace('(', ',').replace(')', ','))
        .flatMap { keys(it) }.toSet()
    private fun censorship(value: String): String? = when {
        Regex("(?i)\\b(?:dirty|dyrty|explicit|uncensored)\\b").containsMatchIn(value) -> "explicit"
        Regex("(?i)\\b(?:clean|censored)\\b").containsMatchIn(value) -> "clean"
        else -> null
    }

    fun queries(artist: String, title: String, video: Boolean): List<Query> {
        val performer = cleanArtist(artist)
        val name = videoName(title)
        var split = name.split(separator, limit = 2)
        if (split.size == 1 && video && name.count { it == '-' } == 1) split = name.split('-', limit = 2)
        val inferred = if (split.size == 2 && (video || artistKeys(split[0]).intersect(artistKeys(performer)).isNotEmpty()))
            Query(cleanArtist(split[0]), cleanTitle(split[1]), censorship(title)) else null
        val original = if (inferred != null && artistKeys(inferred.artist).intersect(artistKeys(performer)).isNotEmpty()) null
            else Query(performer, cleanTitle(name), censorship(title))
        return listOfNotNull(inferred, original).filter { it.artist.isNotBlank() && it.title.isNotBlank() }.distinct().take(2)
    }

    fun matches(artist: String, title: String, query: Query): Boolean =
        artistKeys(artist).intersect(artistKeys(query.artist)).isNotEmpty() &&
            keys(cleanTitle(title)).intersect(keys(cleanTitle(query.title))).isNotEmpty()

    fun bestMatch(results: List<LrcLibLyrics>, query: Query, durationMs: Long): LrcLibLyrics? {
        val requestedEdition = query.edition ?: censorship(query.title)
        return results.filter { row ->
            val edition = censorship(row.trackName.orEmpty())
            row.hasLyrics() && matches(row.artistName.orEmpty(), row.trackName.orEmpty(), query) &&
                (edition == null || requestedEdition == null || edition == requestedEdition)
        }.sortedWith(compareBy<LrcLibLyrics> { row ->
            val difference = row.duration?.let { kotlin.math.abs(it * 1000 - durationMs) }
            when { durationMs <= 0 -> 0; difference == null -> 1; difference <= 3000 -> 0; else -> 2 }
        }.thenBy { row ->
            val seconds = row.duration
            if (durationMs > 0 && seconds != null) kotlin.math.abs(seconds * 1000 - durationMs) else Double.MAX_VALUE
        }.thenByDescending { it.hasSyncedLyrics() }).firstOrNull()
    }

    private fun retryDelay(error: HttpException): Long =
        (error.response()?.headers()?.get("Retry-After")?.toLongOrNull()?.coerceIn(1, 31_536_000)
            ?: if(error.code() == 429) 60L else 5L) * 1_000_000_000L

    suspend fun find(service: LRCLibApiService?, artist: String, title: String, video: Boolean, durationMs: Long): LyricsData? {
        if (service == null || !canSearch()) return null
        val candidates = queries(artist, title, video)
        val primary = candidates.firstOrNull() ?: return null
        val roman = Query(romanize(primary.artist), romanize(primary.title), primary.edition)
        // A non-empty response can contain only live versions: continue when no suitable match exists.
        val attempts = buildList {
            add(Attempt(primary))
            add(Attempt(primary, generic = true))
            if (roman != primary) add(Attempt(roman))
            candidates.drop(1).forEach { add(Attempt(it)) }
            add(Attempt(primary, titleOnly = true))
        }.distinct().take(5)
        var plainFallback: LyricsData? = null
        for ((index, attempt) in attempts.withIndex()) {
            if (index > 0) delay(250)
            val rows = try {
                when {
                    attempt.generic -> service.searchLyrics(query = "${attempt.query.artist} ${attempt.query.title}")
                    attempt.titleOnly -> service.searchLyrics(trackName = attempt.query.title)
                    else -> service.searchLyrics(trackName = attempt.query.title, artistName = attempt.query.artist)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: HttpException) {
                if (error.code() == 429 || error.code() >= 500) {
                    retryAtNanos = System.nanoTime() + retryDelay(error); return plainFallback
                }
                emptyList()
            } catch (_: Exception) { return plainFallback }
            val match = candidates.firstNotNullOfOrNull { bestMatch(rows, it, durationMs) } ?: continue
            val data = recordingLyrics(match, durationMs)
            if(data.hasSyncedLyrics()) return data
            if(plainFallback == null && data.hasLyrics()) plainFallback = data
        }
        return plainFallback
    }

    suspend fun findPlain(service: PlainLyricsApiService?, artist: String, title: String, video: Boolean): LyricsData? {
        if (service == null || System.nanoTime() < plainRetryAtNanos) return null
        val primary = queries(artist, title, video).firstOrNull() ?: return null
        val variants = listOf(primary, Query(romanize(primary.artist), romanize(primary.title), primary.edition)).distinct()
        for ((index, query) in variants.withIndex()) {
            if(index > 0) delay(250)
            try {
                val text = service.getLyrics(query.artist, query.title + if(query.edition == "clean") " (Clean)" else "").lyrics?.trim().orEmpty()
                if (text.length > 40 && !text.startsWith("<") && !LyricsData(text, null).isErrorMessage())
                    return LyricsData(text, null, source = "lyrics.ovh")
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: HttpException) {
                if(error.code() == 429 || error.code() >= 500) {
                    plainRetryAtNanos = System.nanoTime() + retryDelay(error); return null
                }
            } catch (_: Exception) { return null }
        }
        return null
    }

    fun cacheFileName(artist: String, title: String, songId: String? = null): String =
        rawCacheFileName(artist, title, LyricsIdentity.canonical(songId))

    fun cacheFileNames(artist: String, title: String, songId: String?): List<String> =
        LyricsIdentity.aliases(songId).map { rawCacheFileName(artist, title, it) }.distinct()

    private fun rawCacheFileName(artist: String, title: String, songId: String?): String {
        val signature = Normalizer.normalize(artist.trim(), Normalizer.Form.NFC) + "\u0000" + Normalizer.normalize(title.trim(), Normalizer.Form.NFC) + "\u0000" + songId.orEmpty()
        val hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(Charsets.UTF_8))
        return "v2_" + hash.joinToString("") { "%02x".format(it.toInt() and 255) } + ".json"
    }
}
