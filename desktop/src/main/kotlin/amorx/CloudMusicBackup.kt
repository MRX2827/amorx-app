package amorx

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat

internal data class MusicRestorePlan(
    val downloads: List<Track>,
    val playlists: List<DesktopPlaylist>,
    val unavailableTracks: Int = 0,
    val warnings: List<String> = emptyList()
)

internal class CloudMusicBackup(private val execute: (Request) -> Pair<Int, String>) {
    fun fetch(token: String, uid: String): MusicRestorePlan {
        var backup: JsonObject? = null
        var shared: List<JsonObject> = emptyList()
        val warnings = mutableListOf<String>()
        var failures = 0
        try {
            backup = backup(token, uid)
        } catch (error: IOException) {
            warnings += error.message ?: "Не удалось загрузить список скачанных песен"
            failures++
        }
        try {
            shared = sharedPlaylists(token, uid)
        } catch (error: IOException) {
            warnings += error.message ?: "Не удалось загрузить плейлисты"
            failures++
        }
        if (failures == 2) throw IOException(warnings.joinToString(". "))

        var unavailable = 0
        val downloads = backup?.getAsJsonObjectOrNull("amorx_online")?.array("downloads")?.mapNotNull { item ->
            val track = item.objectOrNull()?.track(allowBareId = true)
            if (track == null) unavailable++
            track
        }.orEmpty().distinctBy(Track::id)

        val playlists = mutableListOf<DesktopPlaylist>()
        backup?.array("playlists_data")?.forEachIndexed { index, item ->
            val playlist = item.objectOrNull()
            if (playlist != null) {
                val name = playlist.text("name")?.take(80) ?: "Плейлист ${index + 1}"
                val sourceId = playlist.text("id") ?: "name-" + stableKey(name)
                val tracks = playlist.array("songs").mapNotNull { song ->
                    val track = song.objectOrNull()?.track(allowBareId = false)
                    if (track == null) unavailable++
                    track?.libraryTrack()
                }.distinctBy(LibraryTrack::id)
                playlists += DesktopPlaylist("cloud:$uid:backup:$sourceId", name, tracks)
            }
        }
        val ownPlaylist = backup?.getAsJsonObjectOrNull("amorx_online")?.getAsJsonObjectOrNull("amorx_playlist")
        if (ownPlaylist != null) {
            val code = ownPlaylist.text("code")
            val tracks = ownPlaylist.array("tracks").mapNotNull { item ->
                val track = item.objectOrNull()?.track(allowBareId = true)
                if (track == null) unavailable++
                track?.libraryTrack()
            }.distinctBy(LibraryTrack::id)
            val id = if (code != null) "cloud:$uid:shared:$code" else "cloud:$uid:amorx"
            playlists += DesktopPlaylist(id, "Плейлист AmorX", tracks)
        }
        shared.forEach { playlist ->
            val code = playlist.text("code") ?: throw IOException("Не удалось определить облачный плейлист")
            val tracks = playlist.array("tracks").mapNotNull { item ->
                val track = item.objectOrNull()?.track(allowBareId = true)
                if (track == null) unavailable++
                track?.libraryTrack()
            }.distinctBy(LibraryTrack::id)
            playlists += DesktopPlaylist(
                "cloud:$uid:shared:$code",
                playlist.text("title")?.take(80) ?: "Плейлист AmorX",
                tracks
            )
        }
        return MusicRestorePlan(downloads, playlists.distinctBy(DesktopPlaylist::id), unavailable, warnings)
    }

    private fun backup(token: String, uid: String): JsonObject? {
        val url = endpoint("backups")
            .addQueryParameter("select", "user_id,payload")
            .addQueryParameter("user_id", "eq.$uid")
            .addQueryParameter("limit", "1").build()
        val rows = read(token, url.toString(), "Не удалось загрузить облачную копию музыки")
        val row = rows.firstOrNull()?.objectOrNull() ?: return null
        if (row.text("user_id") != uid) throw IOException("Облачная копия принадлежит другому аккаунту")
        val payload = row.get("payload")?.decoded() ?: return null
        return payload.objectOrNull() ?: throw IOException("Не удалось прочитать облачную копию музыки")
    }

    private fun sharedPlaylists(token: String, uid: String): List<JsonObject> {
        val playlists = mutableListOf<JsonObject>()
        var offset = 0
        do {
            val url = endpoint("shared_playlists")
                .addQueryParameter("select", "code,owner_id,title,tracks")
                .addQueryParameter("owner_id", "eq.$uid")
                .addQueryParameter("order", "code.asc")
                .addQueryParameter("limit", "100")
                .addQueryParameter("offset", offset.toString()).build()
            val page = read(token, url.toString(), "Не удалось загрузить облачные плейлисты")
            page.forEach { item ->
                val playlist = item.objectOrNull() ?: throw IOException("Не удалось прочитать облачный плейлист")
                if (playlist.text("owner_id") != uid) throw IOException("Плейлист принадлежит другому аккаунту")
                playlists += playlist
            }
            offset += page.size()
        } while (page.size() == 100)
        return playlists
    }

    private fun read(token: String, url: String, failure: String): JsonArray {
        val request = Request.Builder().url(url)
            .header("apikey", CloudConfig.KEY)
            .header("Authorization", "Bearer $token")
            .get().build()
        val (code, body) = execute(request)
        if (code !in 200..299) throw IOException("$failure ($code)")
        return try {
            JsonParser.parseString(body).asJsonArray
        } catch (_: RuntimeException) {
            throw IOException(failure)
        }
    }

    private fun endpoint(table: String) = (CloudConfig.URL + "/rest/v1/$table").toHttpUrl().newBuilder()

    private fun JsonObject.track(allowBareId: Boolean): Track? {
        val source = listOf("videoUrl", "video_url", "uri", "url").firstNotNullOfOrNull { key ->
            text(key)?.let(::youtubeId)
        }
        val identity = text("id")
        val prefixed = identity?.takeIf { it.startsWith("online_") || it.startsWith("offline_") }
            ?.substringAfter('_')?.takeIf(videoId::matches)
        val id = source ?: prefixed ?: identity?.takeIf { allowBareId && videoId.matches(it) } ?: return null
        val duration = listOf("dur", "durMs", "duration", "durationMs").firstNotNullOfOrNull { key ->
            get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
        } ?: 0L
        val artwork = listOf("artwork", "art", "artworkUri", "artworkUrl").firstNotNullOfOrNull { key ->
            text(key)?.takeIf { value ->
                value.toHttpUrlOrNull()?.scheme in setOf("https", "http")
            }
        }
        return Track(
            id = id,
            title = text("title")?.takeIf(String::isNotBlank) ?: "Без названия",
            artist = text("artist").orEmpty(),
            seconds = (duration.coerceAtLeast(0L) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            color = colorFor(id),
            artwork = artwork,
            videoUrl = "https://www.youtube.com/watch?v=$id"
        )
    }

    private fun youtubeId(value: String): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        val candidate = when (url.host.lowercase()) {
            "youtu.be", "www.youtu.be" -> url.pathSegments.firstOrNull()
            "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" ->
                if (url.pathSegments.firstOrNull() in setOf("shorts", "embed", "live")) url.pathSegments.getOrNull(1)
                else if (url.encodedPath == "/watch") url.queryParameter("v") else null
            else -> null
        }
        return candidate?.takeIf(videoId::matches)
    }

    private fun Track.libraryTrack() = LibraryTrack(id, title, artist, seconds, artwork, videoUrl, null)

    private fun JsonElement.objectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.getAsJsonObjectOrNull(key: String) = get(key)?.objectOrNull()

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf {
        it.isJsonPrimitive && !it.isJsonNull
    }?.asString?.takeIf(String::isNotBlank)

    private fun JsonElement.decoded(): JsonElement? {
        if (isJsonNull) return null
        if (!isJsonPrimitive || !asJsonPrimitive.isString) return this
        return try {
            JsonParser.parseString(asString)
        } catch (_: RuntimeException) {
            throw IOException("Не удалось прочитать облачную копию музыки")
        }
    }

    private fun JsonObject.array(key: String): List<JsonElement> {
        val value = get(key)?.decoded() ?: return emptyList()
        if (!value.isJsonArray) throw IOException("Не удалось прочитать список песен в облачной копии")
        return value.asJsonArray.toList()
    }

    private fun stableKey(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    private companion object {
        val videoId = Regex("[A-Za-z0-9_-]{11}")
    }
}
