package chromahub.rhythm.app.features.online.stories

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Готовит видео для истории прямо на телефоне: первые 15 секунд, короткая сторона не больше 720,
 * H.264 ~2 Мбит/с. Получается около 4 МБ, чтобы не расходовать бесплатный трафик облака.
 */
@OptIn(UnstableApi::class)
object StoryCompressor {
    const val MAX_MS = 15_000L
    private const val SHORT_SIDE = 720
    private const val BITRATE = 2_000_000

    private data class Info(val w: Int, val h: Int, val durationMs: Long)

    private fun probe(context: Context, uri: Uri): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return if (rot == 90 || rot == 270) Info(h, w, dur) else Info(w, h, dur)
        } finally {
            runCatching { r.release() }
        }
    }

    suspend fun compress(context: Context, input: Uri): Result<File> {
        val info = withContext(Dispatchers.IO) { runCatching { probe(context, input) } }
            .getOrElse { return Result.failure(IOException("Не удалось прочитать видео")) }
        if (info.w <= 0 || info.h <= 0) return Result.failure(IOException("Это не видео или формат не поддерживается"))

        val scale = minOf(1f, SHORT_SIDE.toFloat() / minOf(info.w, info.h))
        val targetHeight = (info.h * scale).toInt().let { it - it % 2 }.coerceAtLeast(2)
        val out = File(context.cacheDir, "story_${System.currentTimeMillis()}.mp4")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0)
                    .setEndPositionMs(MAX_MS)
                    .build()
                val item = MediaItem.Builder().setUri(input).setClippingConfiguration(clip).build()
                val videoEffects = if (scale < 1f) listOf(Presentation.createForHeight(targetHeight)) else emptyList()
                val edited = EditedMediaItem.Builder(item)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()
                val encoder = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(BITRATE).build())
                    .build()
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoder)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Result.success(out))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            out.delete()
                            if (cont.isActive) cont.resume(Result.failure(IOException("Не удалось подготовить видео", exportException)))
                        }
                    })
                    .build()
                cont.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                    out.delete()
                }
                transformer.start(edited, out.absolutePath)
            }
        }
    }
}
