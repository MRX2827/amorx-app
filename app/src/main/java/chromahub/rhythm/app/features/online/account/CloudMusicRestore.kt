package chromahub.rhythm.app.features.online.account

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI

internal data class RestoreMusicTrack(
    val id: String, val title: String, val artist: String, val durationMs: Long, val artwork: String?
) {
    fun json() = JsonObject().apply {
        addProperty("id", id)
        addProperty("title", title)
        addProperty("artist", artist)
        addProperty("dur", durationMs)
        artwork?.let { addProperty("art", it); addProperty("artwork", it) }
        addProperty("videoUrl", "https://www.youtube.com/watch?v=$id")
    }
}

/** A single personal AmorX playlist, plus the complete download list, on every platform. */
internal data class CloudMusicRestorePreview(
    val payload: String,
    val downloads: List<RestoreMusicTrack>,
    val playlist: List<RestoreMusicTrack>,
    val hasPlaylist: Boolean,
    val code: String?,
    val skipped: Int
) {
    fun selectedPayload(downloadIds: Set<String>, includePlaylist: Boolean): String {
        val root = JsonParser.parseString(payload).asJsonObject
        // Other database playlists must not replace the phone's library during a music restore.
        root.remove("playlists_data")
        val online = root.get("amorx_online")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val selected = downloads.filter { it.id in downloadIds }
        online.add("downloads", JsonArray().apply { selected.forEach { add(it.json()) } })
        if (includePlaylist && hasPlaylist) {
            online.add("amorx_playlist", JsonObject().apply {
                code?.let { addProperty("code", it) }
                add("tracks", JsonArray().apply { playlist.forEach { add(it.json()) } })
            })
        } else online.remove("amorx_playlist")
        root.add("amorx_online", online)
        return root.toString()
    }

    companion object {
        private val videoId = Regex("[A-Za-z0-9_-]{11}")
        fun parse(payload: String): CloudMusicRestorePreview {
            val root = try { JsonParser.parseString(payload).asJsonObject }
            catch (_: RuntimeException) { throw IOException("Не удалось прочитать облачную копию") }
            val online = root.get("amorx_online")?.takeIf { it.isJsonObject }?.asJsonObject
            var skipped = 0
            fun tracks(items: List<JsonElement>): List<RestoreMusicTrack> = items.mapNotNull { item ->
                val track = item.takeIf { it.isJsonObject }?.asJsonObject?.track()
                if (track == null) skipped++
                track
            }.distinctBy { it.id }
            val downloads = tracks(online?.array("downloads").orEmpty())
            val own = online?.get("amorx_playlist")?.takeIf { it.isJsonObject }?.asJsonObject
            val legacy = root.array("playlists_data").mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
                .filter { it.text("name")?.trim()?.lowercase() in setOf("amorx", "плейлист amorx") }
            val playlist = (tracks(own?.array("tracks").orEmpty()) + legacy.flatMap { tracks(it.array("songs")) }).distinctBy { it.id }
            if (playlist.size > 1000) throw IOException("Плейлист AmorX содержит больше 1000 треков")
            return CloudMusicRestorePreview(payload, downloads, playlist, own != null || legacy.isNotEmpty(),
                own?.text("code")?.takeIf { Regex("[a-f0-9]{8}").matches(it) }, skipped)
        }
        private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
        private fun JsonObject.array(key: String): List<JsonElement> {
            val value = get(key) ?: return emptyList()
            val decoded = if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                try { JsonParser.parseString(value.asString) }
                catch (_: RuntimeException) { throw IOException("Повреждён список музыки в копии") }
            } else value
            if (!decoded.isJsonArray) throw IOException("Повреждён список музыки в копии")
            return decoded.asJsonArray.toList()
        }
        private fun urlId(value: String?): String? = runCatching {
            val uri = URI(value ?: return@runCatching null)
            if (uri.scheme !in setOf("http", "https")) return@runCatching null
            val id = when (uri.host?.lowercase()) {
                "youtu.be", "www.youtu.be" -> uri.path.trim('/').substringBefore('/')
                "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" -> {
                    if (uri.path.substringBeforeLast('/') in setOf("/shorts", "/embed", "/live")) uri.path.substringAfterLast('/')
                    else uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                }
                else -> null
            }
            id?.takeIf(videoId::matches)
        }.getOrNull()
        private fun JsonObject.track(): RestoreMusicTrack? {
            val id = listOf("videoUrl", "uri", "url").firstNotNullOfOrNull { urlId(text(it)) }
                ?: text("id")?.removePrefix("online_")?.removePrefix("offline_")?.takeIf(videoId::matches) ?: return null
            val duration = listOf("dur", "durMs", "duration", "durationMs").firstNotNullOfOrNull { text(it)?.toLongOrNull() } ?: 0L
            val art = listOf("art", "artwork", "artworkUri").firstNotNullOfOrNull { key ->
                text(key)?.takeIf { runCatching { URI(it).scheme in setOf("http", "https") }.getOrDefault(false) }
            }
            return RestoreMusicTrack(id, text("title")?.takeIf { it.isNotBlank() } ?: "Без названия", text("artist").orEmpty(), duration.coerceAtLeast(0), art)
        }
    }
}
