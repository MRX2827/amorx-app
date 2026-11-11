package chromahub.rhythm.app.features.online.feed

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal class PreparedFeedMedia(val file: File, val mimeType: String, val extension: String, val preview: File?) {
    fun cleanup() {
        file.delete()
        preview?.delete()
    }
}

private const val MAX_VIDEO_BYTES = 40L * 1024 * 1024

internal suspend fun prepareFeedMedia(context: Context, uri: Uri): PreparedFeedMedia = withContext(Dispatchers.IO) {
    val type = context.contentResolver.getType(uri)?.lowercase().orEmpty()
    val videoExt = when (type) {
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        else -> null
    }
    val isImage = type.startsWith("image/")
    if (!isImage && videoExt == null) throw IOException("Выберите фото или видео в формате MP4, MOV, WebM или 3GP")
    val ext = videoExt ?: "jpg"
    val file = File.createTempFile("feed_", ".$ext", context.cacheDir)
    var preview: File? = null
    try {
        if (isImage) {
            val request = ImageRequest.Builder(context).data(uri).size(1600).allowHardware(false).build()
            val result = context.imageLoader.execute(request) as? SuccessResult
                ?: throw IOException("Не удалось открыть фото")
            file.outputStream().use {
                if (!result.drawable.toBitmap().compress(Bitmap.CompressFormat.JPEG, 88, it)) {
                    throw IOException("Не удалось подготовить фото")
                }
            }
        } else {
            val source = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть видео")
            source.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_VIDEO_BYTES) throw IOException("Размер видео должен быть до 40 МБ")
                        out.write(buffer, 0, size)
                    }
                }
            }
            if (file.length() == 0L) throw IOException("Видео пустое")
            preview = videoPreview(context, file)
        }
        PreparedFeedMedia(file, if (isImage) "image/jpeg" else type, ext, preview)
    } catch (e: Exception) {
        file.delete()
        preview?.delete()
        throw e
    }
}

private fun videoPreview(context: Context, video: File): File? = runCatching {
    val retriever = MediaMetadataRetriever()
    val frame = try {
        retriever.setDataSource(video.absolutePath)
        if (Build.VERSION.SDK_INT >= 27) {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) null else {
                val scale = minOf(1f, 640f / maxOf(width, height))
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
        } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } finally {
        retriever.release()
    } ?: return null
    val out = File.createTempFile("feed_preview_", ".jpg", context.cacheDir)
    try {
        out.outputStream().use {
            if (!frame.compress(Bitmap.CompressFormat.JPEG, 82, it)) throw IOException("Не удалось сохранить обложку")
        }
        out
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        frame.recycle()
    }
}.getOrNull()
