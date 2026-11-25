package amorx.playback

import java.net.URI

/** Only rewrite known, unsigned thumbnail endpoints; preserve arbitrary/signed URLs. */
object ArtworkQuality {
    fun candidates(source: String): List<String> {
        val uri = runCatching { URI(source) }.getOrNull() ?: return listOf(source)
        if (uri.scheme !in listOf("https", "http")) return listOf(source)
        val host = uri.host?.lowercase().orEmpty()
        if (host == "i.ytimg.com" || host == "img.youtube.com") {
            val id = Regex("^/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").find(uri.path.orEmpty())?.groupValues?.get(1)
            if (id != null) return (listOf("maxresdefault", "sddefault", "hqdefault").map { "https://i.ytimg.com/vi/$id/$it.jpg" } + source).distinct()
        }
        if (host.endsWith(".googleusercontent.com") || host == "lh3.ggpht.com") {
            val large = source.replace(Regex("=w\\d+-h\\d+"), "=w1200-h1200").replace(Regex("=s\\d+"), "=s1200")
            return listOf(large, source).distinct()
        }
        return listOf(source)
    }
}
