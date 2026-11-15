package amorx

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val active = HashSet<String>()

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

    @Synchronized
    private fun saveIndex() {
        runCatching { File(dir, "index.json").writeText(gson.toJson(items.value)) }
    }

    fun download(t: Track) {
        val url = t.videoUrl ?: return
        if (find(t.id) != null) return
        synchronized(active) { if (!active.add(t.id)) return }
        pending.value = pending.value + (t.id to Pending(t, 0f))
        scope.launch {
            val part = File(dir, t.id + ".part")
            try {
                NewPipeDownloader.init()
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                val best = info.audioStreams
                    .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                    .maxWithOrNull(compareBy<AudioStream>({ it.format == MediaFormat.M4A }, { it.averageBitrate }))
                    ?: error("Нет доступного аудиопотока")
                var lastPct = -1
                downloadFile(best.content, part) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        pending.value = pending.value + (t.id to Pending(t, f))
                    }
                }
                val target = File(dir, t.id + "." + (best.format?.suffix ?: "m4a"))
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("не удалось сохранить файл")
                val art = downloadArtwork(t.artwork, File(dir, t.id + ".jpg"))
                val entry = Downloaded(
                    t.id, t.title, t.artist, t.seconds, target.absolutePath, art, target.length(), System.currentTimeMillis()
                )
                items.value = items.value.filter { it.id != t.id } + entry
                saveIndex()
                message.value = "Скачано: " + t.title
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Throwable) {
                part.delete()
                message.value = "Не удалось скачать «" + t.title + "»: " + e.message
            } finally {
                synchronized(active) { active.remove(t.id) }
                pending.value = pending.value - t.id
            }
        }
    }

    fun delete(id: String) {
        val e = items.value.firstOrNull { it.id == id } ?: return
        File(e.audioPath).delete()
        e.artPath?.let { File(it).delete() }
        items.value = items.value.filter { it.id != id }
        saveIndex()
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
        if (url.isNullOrBlank() || !url.startsWith("http")) return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val stream = resp.body?.byteStream() ?: return null
                stream.use { input -> FileOutputStream(out).use { f -> input.copyTo(f) } }
                out.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
