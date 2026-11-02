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

/** Готовое к публикации видео. [trimmed] = true, если оно длиннее лимита и выложены только первые [StoryCompressor.MAX_MS]. */
data class PreparedVideo(val file: File, val durationMs: Long, val trimmed: Boolean)

/**
 * Готовит видео для истории прямо на телефоне.
 *
 * Видео идёт до конца, если оно не длиннее минуты. Чтобы файл оставался небольшим (около 8 МБ),
 * качество подбирается по длине: короткое видео получает больше битрейта, длинное меньше.
 * Только видео длиннее минуты обрезается, и человеку об этом сообщается заранее.
 */
@OptIn(UnstableApi::class)
object StoryCompressor {
    const val MAX_MS = 60_000L

    private const val TARGET_BYTES = 8_000_000L
    private const val AUDIO_BPS = 128_000L
    private const val MIN_VIDEO_BPS = 700_000L
    private const val MAX_VIDEO_BPS = 2_500_000L

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

    /** Битрейт видео под целевой размер файла для данной длины. */
    private fun videoBitrate(usedMs: Long): Int {
        val sec = (usedMs.coerceAtLeast(1_000L) / 1000.0)
        val total = (TARGET_BYTES * 8L / sec).toLong()
        return (total - AUDIO_BPS).coerceIn(MIN_VIDEO_BPS, MAX_VIDEO_BPS).toInt()
    }

    suspend fun compress(context: Context, input: Uri): Result<PreparedVideo> {
        val info = withContext(Dispatchers.IO) { runCatching { probe(context, input) } }
            .getOrElse { return Result.failure(IOException("Не удалось прочитать видео")) }
        if (info.w <= 0 || info.h <= 0) return Result.failure(IOException("Это не видео или формат не поддерживается"))

        val trimmed = info.durationMs > MAX_MS
        val usedMs = when {
            info.durationMs <= 0L -> MAX_MS // длина неизвестна: считаем с запасом
            trimmed -> MAX_MS
            else -> info.durationMs
        }
        val bitrate = videoBitrate(usedMs)
        val shortSide = if (bitrate < 1_300_000) 540 else 720
        val scale = minOf(1f, shortSide.toFloat() / minOf(info.w, info.h))
        val targetHeight = (info.h * scale).toInt().let { it - it % 2 }.coerceAtLeast(2)
        val out = File(context.cacheDir, "story_${System.currentTimeMillis()}.mp4")

        val result = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Result<File>> { cont ->
                val itemBuilder = MediaItem.Builder().setUri(input)
                if (trimmed) {
                    itemBuilder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(MAX_MS)
                            .build()
                    )
                }
                val videoEffects = if (scale < 1f) listOf(Presentation.createForHeight(targetHeight)) else emptyList()
                val edited = EditedMediaItem.Builder(itemBuilder.build())
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()
                val encoder = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
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
                            if (cont.isActive) {
                                cont.resume(Result.failure(IOException("Не удалось подготовить видео", exportException)))
                            }
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
        return result.map { PreparedVideo(it, usedMs, trimmed) }
    }
}
