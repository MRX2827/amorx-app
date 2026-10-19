package chromahub.rhythm.app.features.online

import android.content.Context
import android.net.Uri
import chromahub.rhythm.app.shared.data.model.Song
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

data class DownloadedTrack(
    val id: String,
    val videoUrl: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val audioPath: String,
    val artworkPath: String?,
    val sizeBytes: Long,
    val addedAt: Long
)

data class PendingDownload(val track: OnlineTrack, val progress: Float)

fun DownloadedTrack.toSong(): Song = Song(
    id = "offline_$id",
    title = title,
    artist = artist,
    album = "Скачанное",
    albumId = "",
    duration = durationMs,
    uri = Uri.fromFile(File(audioPath)),
    artworkUri = artworkPath?.let { Uri.fromFile(File(it)) }
)

object OnlineDownloads {
    private const val CHUNK = 2L * 1024 * 1024

    val items = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    val pending = MutableStateFlow<Map<String, PendingDownload>>(emptyMap())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var dir: File? = null
    private var appContext: Context? = null
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private val active = HashSet<String>()

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.applicationContext.filesDir, "online_downloads").apply { mkdirs() }
        dir = d
        appContext = context.applicationContext
        // Недокачанные файлы после закрытия приложения больше не нужны
        d.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
        val index = File(d, "index.json")
        if (index.exists()) {
            val loaded = runCatching {
                gson.fromJson(index.readText(), Array<DownloadedTrack>::class.java)?.toList()
            }.getOrNull().orEmpty()
            items.value = loaded.filter { File(it.audioPath).exists() }
        }
    }

    @Synchronized
    private fun saveIndex() {
        val d = dir ?: return
        runCatching { File(d, "index.json").writeText(gson.toJson(items.value)) }
    }

    fun download(track: OnlineTrack) {
        val d = dir ?: return
        val id = track.id
        if (items.value.any { it.id == id }) return
        synchronized(active) { if (!active.add(id)) return }
        pending.update { it + (id to PendingDownload(track, 0f)) }
        appContext?.let { DownloadService.start(it) }

        scope.launch {
            val part = File(d, "$id.part")
            try {
                val audio = OnlineRepository.resolveAudio(track.videoUrl)
                var lastPct = -1
                downloadFile(audio.url, part) { fraction ->
                    val pct = (fraction * 100).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        pending.update { it + (id to PendingDownload(track, fraction)) }
                    }
                }
                val target = File(d, "$id.${audio.suffix}")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("не удалось сохранить файл")
                val art = downloadArtwork(track.artworkUrl, File(d, "$id.jpg"))
                val entry = DownloadedTrack(
                    id = id,
                    videoUrl = track.videoUrl,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                    audioPath = target.absolutePath,
                    artworkPath = art,
                    sizeBytes = target.length(),
                    addedAt = System.currentTimeMillis()
                )
                items.update { list -> list.filter { it.id != id } + entry }
                saveIndex()
                messages.tryEmit("Скачано: ${track.title}")
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                messages.tryEmit("Не удалось скачать «${track.title}»: ${e.message}")
            } finally {
                synchronized(active) { active.remove(id) }
                pending.update { it - id }
            }
        }
    }

    fun delete(id: String) {
        val entry = items.value.firstOrNull { it.id == id } ?: return
        File(entry.audioPath).delete()
        entry.artworkPath?.let { File(it).delete() }
        items.update { list -> list.filter { it.id != id } }
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
                        // Сервер проигнорировал Range и отдал файл целиком
                        val len = body.contentLength()
                        val got = copy(body.byteStream(), fos) { sum ->
                            onProgress(if (len > 0) sum.toFloat() / len else 0f)
                        }
                        offset = got
                        total = got
                        return@use
                    }
                    val parsedTotal = resp.header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.toLongOrNull()
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
