package amorx

import amorx.playback.ArtworkQuality
import java.net.URI
import java.io.File

private val artworkCache = object : LinkedHashMap<String, ByteArray>(16, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 8
}
internal fun qualityArtworkBytes(source: String): ByteArray? {
    synchronized(artworkCache) { artworkCache[source]?.let { return it } }
    val local = runCatching { URI(source).takeIf { it.scheme == "file" }?.let(::File) }.getOrNull()
    val original = local?.takeIf { it.isFile }?.readBytes()
    val originalWidth = original?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).use { image -> image.width } }.getOrNull() } ?: 0
    val legacy = local?.takeIf { it.parentFile?.name == "downloads" && it.nameWithoutExtension.matches(Regex("[A-Za-z0-9_-]{11}")) && originalWidth < 720 }
    val remote = legacy?.let { "https://i.ytimg.com/vi/${it.nameWithoutExtension}/maxresdefault.jpg" } ?: source
    for (candidate in ArtworkQuality.candidates(remote)) {
        val bytes = runCatching {
            if (candidate == source && original != null) original else {
                val connection = URI(candidate).toURL().openConnection().apply { connectTimeout = 2000; readTimeout = 4000 }
                connection.getInputStream().use { it.readNBytes(12 * 1024 * 1024 + 1) }
            }
        }.getOrNull() ?: continue
        if (bytes.size > 12 * 1024 * 1024) continue
        val width = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.width } }.getOrNull() ?: continue
        if (candidate != source && width < 480) continue
        if (legacy != null && width > originalWidth) runCatching {
            val temporary = File(legacy.parentFile, legacy.name + ".hdtmp"); temporary.writeBytes(bytes)
            java.nio.file.Files.move(temporary.toPath(), legacy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        synchronized(artworkCache) { artworkCache[source] = bytes }; return bytes
    }
    return original
}
