package chromahub.rhythm.app.features.online

import android.net.Uri
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class OnlineTrack(
    val videoUrl: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val artworkUrl: String?
) {
    val id: String get() = videoUrl.substringAfter("v=", videoUrl).substringBefore("&")
}

data class ResolvedAudio(val url: String, val suffix: String)

fun OnlineTrack.toSong(streamUrl: String): Song = Song(
    id = "online_$id",
    title = title,
    artist = artist,
    album = "YouTube Music",
    albumId = "",
    duration = durationMs,
    uri = Uri.parse(OnlineLinks.mark(streamUrl, id)),
    artworkUri = artworkUrl?.let { Uri.parse(it) }
)

object OnlineRepository {

    suspend fun search(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val service = ServiceList.YouTube
        val filters: List<List<String>> = listOf(
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
            emptyList()
        )
        var lastError: Exception? = null
        for (filter in filters) {
            try {
                val handler = service.searchQHFactory.fromQuery(query, filter, "")
                val info = SearchInfo.getInfo(service, handler)
                val tracks = info.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .map { item ->
                        OnlineTrack(
                            videoUrl = item.url,
                            title = item.name,
                            artist = item.uploaderName ?: "",
                            durationMs = item.duration.coerceAtLeast(0L) * 1000L,
                            artworkUrl = item.thumbnails.maxByOrNull { it.height }?.url
                        )
                    }
                if (tracks.isNotEmpty()) return@withContext tracks
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        emptyList()
    }

    suspend fun searchArtists(query: String): List<WaveArtist> = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val service = ServiceList.YouTube
        val filters: List<List<String>> = listOf(
            listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS),
            listOf(YoutubeSearchQueryHandlerFactory.CHANNELS)
        )
        var lastError: Exception? = null
        for (filter in filters) {
            try {
                val handler = service.searchQHFactory.fromQuery(query, filter, "")
                val info = SearchInfo.getInfo(service, handler)
                val list = info.relatedItems
                    .filterIsInstance<ChannelInfoItem>()
                    .map { ch ->
                        WaveArtist(
                            name = ch.name.removeSuffix(" - Topic"),
                            avatarUrl = ch.thumbnails.maxByOrNull { img -> img.height }?.url
                        )
                    }
                    .distinctBy { it.name.lowercase() }
                if (list.isNotEmpty()) return@withContext list.take(15)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        emptyList()
    }

    /** Ссылки на аудио протухают, поэтому получаем их только перед воспроизведением или загрузкой. */
    suspend fun resolveAudio(videoUrl: String): ResolvedAudio = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val best = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxWithOrNull(
                compareBy<AudioStream>(
                    { it.format == MediaFormat.M4A },
                    { it.averageBitrate }
                )
            ) ?: error("Нет доступного аудиопотока")
        ResolvedAudio(best.content, best.format?.suffix ?: "m4a")
    }

    suspend fun resolveAudioUrl(videoUrl: String): String = resolveAudio(videoUrl).url
}

/** Состояние поиска живёт вне экрана, чтобы не терять результаты при смене вкладок. */
object OnlineSearchState {
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun search(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            loading.value = true
            error.value = null
            try {
                val found = OnlineRepository.search(text)
                results.value = found
                if (found.isEmpty()) error.value = "Ничего не найдено"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = "Ошибка поиска: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                if (isActive) loading.value = false
            }
        }
    }
}
