package chromahub.rhythm.app.features.online

import android.net.Uri
import kotlinx.coroutines.runBlocking

/**
 * Ссылки на аудио YouTube живут несколько часов. К ссылке добавляется метка с id видео,
 * и плеер обновляет протухшую ссылку прямо перед воспроизведением.
 */
object OnlineLinks {
    private const val MARK = "ytm="
    private const val MARGIN_SECONDS = 600L

    fun mark(url: String, videoId: String): String = url.substringBefore("#") + "#" + MARK + videoId

    /** Возвращает новую ссылку, если старая протухла. Иначе null: оставляем как есть. Вызывать не на главном потоке. */
    fun fresh(uri: Uri): Uri? {
        val frag = uri.fragment ?: return null
        if (!frag.startsWith(MARK)) return null
        val videoId = frag.removePrefix(MARK)
        if (videoId.isBlank()) return null
        val expire = uri.getQueryParameter("expire")?.toLongOrNull() ?: return null
        val now = System.currentTimeMillis() / 1000
        if (expire - now > MARGIN_SECONDS) return null
        return try {
            val url = runBlocking {
                OnlineRepository.resolveAudioUrl("https://www.youtube.com/watch?v=" + videoId)
            }
            Uri.parse(url)
        } catch (e: Exception) {
            null
        }
    }
}
