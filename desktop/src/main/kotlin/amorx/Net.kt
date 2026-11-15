package amorx

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

class NewPipeDownloader private constructor(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val url = request.url()
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", url)
            val text = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString()
            )
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        @Volatile private var ready = false
        fun init() {
            if (ready) return
            synchronized(this) {
                if (ready) return
                NewPipe.init(NewPipeDownloader(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()))
                ready = true
            }
        }
    }
}

private val palette = listOf(
    Color(0xFF7E57C2), Color(0xFFD81B60), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFF4511E), Color(0xFF00897B), Color(0xFFFB8C00), Color(0xFF5E35B1)
)

object Repo {
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
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
                        val id = item.url.substringAfter("v=", item.url).substringBefore("&")
                        Track(
                            id = id,
                            title = item.name,
                            artist = item.uploaderName ?: "",
                            seconds = item.duration.coerceAtLeast(0L).toInt(),
                            color = palette[(id.hashCode() and 0x7fffffff) % palette.size],
                            artwork = item.thumbnails.maxByOrNull { it.height }?.url,
                            videoUrl = item.url
                        )
                    }
                    .distinctBy { it.id }
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

    /** Ссылки живут недолго, поэтому получаем их только в момент воспроизведения. */
    suspend fun resolveAudio(videoUrl: String): String = withContext(Dispatchers.IO) {
        NewPipeDownloader.init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val best = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxWithOrNull(compareBy<AudioStream>({ it.format == MediaFormat.M4A }, { it.averageBitrate }))
            ?: error("Нет доступного аудиопотока")
        best.content
    }
}
