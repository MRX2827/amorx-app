package amorx

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class Downloaded(
    val id: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val audioPath: String,
    val artPath: String?,
    val sizeBytes: Long,
    val addedAt: Long
)

data class Pending(val track: Track, val progress: Float)

fun Downloaded.toTrack(): Track = Track(
    id = id, title = title, artist = artist, seconds = seconds, color = colorFor(id),
    artwork = artPath?.let { File(it).toURI().toString() },
    videoUrl = null, localPath = audioPath
)

/** Загрузки для офлайна: файлы в ~/Library/Application Support/AmorX/downloads. */
object Downloads {
    private const val CHUNK = 2L * 1024 * 1024
    private val dir = File(AppDir.root, "downloads").apply { mkdirs() }
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val jobs = HashMap<String, Deferred<Result<Downloaded>>>()
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    val items = mutableStateOf<List<Downloaded>>(emptyList())
    val pending = mutableStateOf<Map<String, Pending>>(emptyMap())
    val message = mutableStateOf<String?>(null)

    init {
        dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
        val index = File(dir, "index.json")
        if (index.exists()) {
            val loaded = runCatching {
                gson.fromJson(index.readText(), Array<Downloaded>::class.java)?.toList()
            }.getOrNull().orEmpty()
            items.value = loaded.filter { File(it.audioPath).exists() }
        }
    }

    fun find(id: String): Downloaded? = items.value.firstOrNull { it.id == id && File(it.audioPath).exists() }

    private fun saveIndex(next: List<Downloaded>) {
        writeLocalJson(File(dir, "index.json"), gson.toJson(next))
        items.value = next
    }

    fun download(track: Track) {
        val job = startDownload(track)
        job.start()
    }

    internal suspend fun awaitDownload(track: Track): Result<Downloaded> {
        find(track.id)?.let { return Result.success(it) }
        return startDownload(track).await()
    }

    @Synchronized
    private fun startDownload(track: Track): Deferred<Result<Downloaded>> {
        jobs[track.id]?.let { return it }
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                find(track.id)?.let { return@async Result.success(it) }
                val source = track.videoUrl ?: throw IOException("Нет источника песни")
                if (!videoId.matches(track.id)) throw IOException("Некорректный ID песни")
                updateProgress(track, 0f)
                val downloaded = fetchTrack(track, source)
                message.value = "Скачано: " + track.title
                Result.success(downloaded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.value = "Не удалось скачать «" + track.title + "»: " + error.message
                Result.failure(error)
            } finally {
                synchronized(this@Downloads) {
                    pending.value = pending.value - track.id
                    jobs.remove(track.id)
                }
            }
        }
        jobs[track.id] = job
        return job
    }

    @Synchronized
    private fun updateProgress(track: Track, progress: Float) {
        pending.value = pending.value + (track.id to Pending(track, progress.coerceIn(0f, 1f)))
    }

    private suspend fun fetchTrack(track: Track, source: String): Downloaded {
        val part = File(dir, track.id + ".part")
        try {
            NewPipeDownloader.init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, source)
            val best = info.audioStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .maxWithOrNull(compareBy<AudioStream>({ it.format == MediaFormat.M4A }, { it.averageBitrate }))
                ?: throw IOException("Нет доступного аудиопотока")
            var lastPercent = -1
            downloadFile(best.content, part) { progress ->
                val percent = (progress * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    updateProgress(track, progress)
                }
            }
            currentCoroutineContext().ensureActive()
            val target = File(dir, track.id + "." + (best.format?.suffix ?: "m4a"))
            java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val art = downloadArtwork(track.artwork, File(dir, track.id + ".jpg"))
            val entry = Downloaded(
                track.id, track.title, track.artist, track.seconds, target.absolutePath, art,
                target.length(), System.currentTimeMillis()
            )
            synchronized(this) {
                saveIndex(items.value.filterNot { it.id == entry.id } + entry)
            }
            return entry
        } finally {
            part.delete()
        }
    }

    @Synchronized
    fun delete(id: String) {
        val entry = items.value.firstOrNull { it.id == id } ?: return
        try {
            saveIndex(items.value.filterNot { it.id == id })
            File(entry.audioPath).delete()
            entry.artPath?.let { File(it).delete() }
        } catch (_: IOException) {
            message.value = "Не удалось удалить песню. Повторите попытку."
        }
    }

    /** Качаем кусками по 2 МБ: так YouTube не душит скорость. */
    private fun downloadFile(url: String, out: File, onProgress: (Float) -> Unit) {
        out.delete()
        FileOutputStream(out).use { fos ->
            var offset = 0L
            var total = -1L
            while (total < 0 || offset < total) {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$offset-${offset + CHUNK - 1}")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("пустой ответ")
                    val base = offset
                    if (resp.code == 200) {
                        val len = body.contentLength()
                        if (offset > 0) fos.channel.truncate(0).position(0)
                        val got = copy(body.byteStream(), fos) { sum ->
                            onProgress(if (len > 0) sum.toFloat() / len else 0f)
                        }
                        offset = got
                        total = got
                        return@use
                    }
                    val parsedTotal = resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    if (parsedTotal != null) total = parsedTotal
                    val got = copy(body.byteStream(), fos) { sum ->
                        onProgress(if (total > 0) (base + sum).toFloat() / total else 0f)
                    }
                    if (got == 0L) throw IOException("пустой фрагмент")
                    offset += got
                    if (parsedTotal == null && got < CHUNK) total = offset
                }
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit): Long {
        val buffer = ByteArray(32 * 1024)
        var sum = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            sum += n
            onBytes(sum)
        }
        return sum
    }

    private fun downloadArtwork(url: String?, out: File): String? {
        if (url.isNullOrBlank()) return null
        for (candidate in amorx.playback.ArtworkQuality.candidates(url)) {
            try {
                client.newCall(Request.Builder().url(candidate).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bytes = response.body?.bytes() ?: return@use
                    if (bytes.size > 12 * 1024 * 1024) return@use
                    val width = org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.width }
                    if (width < 160 || (candidate != url && width < 480)) return@use
                    val temporary = File(out.parentFile, out.name + ".hdtmp")
                    temporary.writeBytes(bytes)
                    if (!temporary.renameTo(out)) { temporary.copyTo(out, overwrite = true); temporary.delete() }
                    return out.absolutePath
                }
            } catch (_: Exception) { }
        }
        return null
    }
}
