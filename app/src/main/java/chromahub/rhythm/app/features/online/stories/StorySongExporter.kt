package chromahub.rhythm.app.features.online.stories

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
internal object StorySongExporter {
    const val MAX_BYTES = 1_500_000L

    suspend fun export(context: Context, song: Song, startMs: Long, endMs: Long): Result<File> {
        if (startMs < 0 || endMs <= startMs || endMs - startMs > StoryAudio.MAX_CLIP_MS) {
            return Result.failure(IllegalArgumentException("Неверная длина фрагмента"))
        }

        val app = context.applicationContext
        val out = File(app.cacheDir, "story_audio_${UUID.randomUUID()}.m4a")
        var exported = false
        try {
            val result = withTimeoutOrNull(60_000L) {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine<Result<File>> { continuation ->
                        val source = StoryAudio.clip(song, song.id, startMs, endMs)
                        val item = EditedMediaItem.Builder(source).setRemoveVideo(true).build()
                        val encoder = DefaultEncoderFactory.Builder(app)
                            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(96_000).build())
                            .build()
                        val mediaSources = DefaultMediaSourceFactory(app)
                            .setDataSourceFactory(StoryAudio.dataSourceFactory(app))
                        val assetLoader = DefaultAssetLoaderFactory(
                            app,
                            DefaultDecoderFactory.Builder(app).build(),
                            Clock.DEFAULT,
                            mediaSources,
                            DataSourceBitmapLoader.Builder(app).build()
                        )
                        val transformer = Transformer.Builder(app)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(encoder)
                            .setAssetLoaderFactory(assetLoader)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (!continuation.isActive) return
                                    val result = if (out.length() in 1..MAX_BYTES) {
                                        Result.success(out)
                                    } else {
                                        Result.failure(IOException("Некорректный размер аудиофрагмента"))
                                    }
                                    continuation.resume(result)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resume(Result.failure(IOException("Не удалось подготовить фрагмент", exportException)))
                                    }
                                }
                            })
                            .build()
                        continuation.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post {
                                transformer.cancel()
                                out.delete()
                            }
                        }
                        try {
                            transformer.start(item, out.absolutePath)
                        } catch (e: Exception) {
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(IOException("Не удалось подготовить фрагмент", e)))
                            }
                        }
                    }
                }
            } ?: Result.failure(IOException("Подготовка фрагмента заняла слишком много времени"))
            exported = result.isSuccess
            return result
        } finally {
            if (!exported) out.delete()
        }
    }
}
