package chromahub.rhythm.app.util.coil

import amorx.playback.ArtworkQuality
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.net.Uri

/** New keys bypass cached low-resolution thumbnails; original remains the offline fallback. */
class QualityArtworkInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val source = when (val data = request.data) { is String -> data; is Uri -> data.toString(); else -> return chain.proceed(request) }
        val local = runCatching { Uri.parse(source) }.getOrNull()?.takeIf { it.scheme == "file" }?.path?.let(::File)
        val legacy = local?.takeIf { it.parentFile?.name == "online_downloads" && it.nameWithoutExtension.matches(Regex("[A-Za-z0-9_-]{11}")) }
        val remote = legacy?.let { "https://i.ytimg.com/vi/${it.nameWithoutExtension}/maxresdefault.jpg" } ?: source
        val options = (ArtworkQuality.candidates(remote) + source).distinct()
        if (options == listOf(source)) return chain.proceed(request)
        var last: ImageResult? = null
        for (url in options) {
            try {
                val result = chain.proceed(request.newBuilder().data(url).memoryCacheKey("art-hd-v1:$url").diskCacheKey("art-hd-v1:$url").build())
                if (result is SuccessResult) {
                    if (legacy != null && url != source) withContext(Dispatchers.IO) {
                        runCatching {
                            coil.Coil.imageLoader(request.context).diskCache?.openSnapshot("art-hd-v1:$url")?.use { snapshot ->
                                val raw = snapshot.data.toFile()
                                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(raw.path, bounds)
                                val old = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(legacy.path, old)
                                if (bounds.outWidth >= 480 && bounds.outWidth > old.outWidth) {
                                    val temp = File(legacy.parentFile, legacy.name + ".hdtmp")
                                    raw.copyTo(temp, overwrite = true)
                                    if (!temp.renameTo(legacy)) { temp.copyTo(legacy, overwrite = true); temp.delete() }
                                }
                            }
                        }
                    }
                    return result
                }
                last = result
            } catch (cancelled: CancellationException) { throw cancelled }
        }
        return last ?: chain.proceed(request)
    }
}
