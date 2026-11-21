package amorx

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant

internal data class MusicBackupResult(val downloads: Int, val playlists: Int, val skipped: Int)
internal data class PlaylistShareResult(val link: String, val tracks: Int, val skipped: Int)

/** Android's existing backup and shared-playlist formats. Unknown phone settings survive a desktop backup. */
internal class DesktopMusicCloud(private val execute: (Request) -> Pair<Int, String>) {
    fun backup(token: String, uid: String, downloads: List<Track>, playlists: List<DesktopPlaylist>): MusicBackupResult {
        val portableDownloads = downloads.mapNotNull(::portableTrack).distinctBy { it.text("id") }
        val candidates = playlists.filter { visibleTo(it, uid) && (it.id == "cloud:$uid:amorx" || isAmorxPlaylistName(it.name)) }
        val own = candidates.firstOrNull()?.let { first -> listOf(first.copy(
            name = "Плейлист AmorX", tracks = candidates.flatMap { it.tracks }.distinctBy { it.id }
        )) }.orEmpty()
        val skipped = downloads.count { portableTrack(it) == null } + own.sumOf { p -> p.tracks.count { portableTrack(it) == null } }
        repeat(3) {
            val rows = rows(request(token, "backups")
                .url(endpoint("backups").addQueryParameter("select", "user_id,payload,updated_at")
                    .addQueryParameter("user_id", "eq.$uid").addQueryParameter("limit", "1").build()).get().build())
            val existing = rows.firstOrNull()?.asJsonObject
            if (existing != null && existing.text("user_id") != uid) throw IOException("Копия принадлежит другому аккаунту")
            val root = if (existing == null) JsonObject().apply {
                add("preferences", JsonObject())
                add("preferences_types", JsonObject())
                addProperty("backup_version", 4)
                addProperty("app_version", DesktopUpdate.currentVersion)
            } else existing.get("payload")?.decoded()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IOException("Облачная копия повреждена. Она оставлена без изменений")
            val online = root.get("amorx_online")?.let {
                if (!it.isJsonObject) throw IOException("Не удалось прочитать музыку в копии")
                it.asJsonObject
            } ?: JsonObject().also { root.add("amorx_online", it) }
            val remoteDownloads = online.array("downloads")
            online.add("downloads", merge(remoteDownloads, portableDownloads.map { track -> track.deepCopy().apply {
                addProperty("videoUrl", youtube(text("id")!!))
                text("art")?.let { addProperty("artwork", it) }
            } }, ::trackKey))

            val remotePlaylists = root.array("playlists_data").map { it.asJsonObject }.associateByTo(linkedMapOf()) { it.text("id") }
            val main = own.firstOrNull { p ->
                p.id == "cloud:$uid:amorx" ||
                    online.get("amorx_playlist")?.takeIf { it.isJsonObject }?.asJsonObject?.text("code")?.let { p.id == "cloud:$uid:shared:$it" } == true ||
                    isAmorxPlaylistName(p.name)
            }
            own.forEach { playlist ->
                val tracks = playlist.tracks.mapNotNull(::portableTrack).distinctBy { it.text("id") }
                if (playlist == main) {
                    val amorx = online.get("amorx_playlist")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    amorx.add("tracks", merge(amorx.array("tracks"), tracks, ::trackKey))
                    if (amorx.getAsJsonArray("tracks").size() > 1000) throw IOException("Плейлист AmorX на Android поддерживает до 1000 треков")
                    playlist.id.removePrefix("cloud:$uid:shared:").takeIf { playlist.id.startsWith("cloud:$uid:shared:") && codePattern.matches(it) }
                        ?.let { amorx.addProperty("code", it) }
                    online.add("amorx_playlist", amorx)
                } else {
                    val id = if (playlist.id.startsWith("cloud:$uid:backup:")) playlist.id.removePrefix("cloud:$uid:backup:") else "desktop-" + playlist.id.removePrefix("cloud:$uid:")
                    val saved = remotePlaylists[id] ?: JsonObject().apply {
                        addProperty("id", id)
                        addProperty("dateCreated", System.currentTimeMillis())
                    }
                    saved.addProperty("name", playlist.name)
                    saved.addProperty("dateModified", System.currentTimeMillis())
                    saved.add("songs", merge(saved.array("songs"), tracks.map(::androidSong), ::trackKey))
                    remotePlaylists[id] = saved
                }
            }
            if (remotePlaylists.isNotEmpty() || root.has("playlists_data")) {
                root.addProperty("playlists_data", JsonArray().apply { remotePlaylists.values.forEach(::add) }.toString())
            }
            root.addProperty("timestamp", System.currentTimeMillis())
            val row = JsonObject().apply {
                addProperty("user_id", uid)
                addProperty("payload", root.toString())
                addProperty("updated_at", Instant.now().toString())
            }
            val write = request(token, "backups").header("Prefer", "return=representation")
            if (existing == null) {
                write.url(endpoint("backups").addQueryParameter("on_conflict", "user_id").build())
                    .header("Prefer", "resolution=ignore-duplicates,return=representation").post(row.toString().toRequestBody(CloudProfiles.JSON))
            } else {
                val timestamp = existing.text("updated_at") ?: throw IOException("Копия не содержит даты. Обновите её с телефона")
                // Compare-and-swap avoids overwriting a backup saved by the phone while this request was running.
                write.url(endpoint("backups").addQueryParameter("user_id", "eq.$uid")
                    .addQueryParameter("updated_at", "eq.$timestamp").build()).patch(row.toString().toRequestBody(CloudProfiles.JSON))
            }
            val result = rows(write.build())
            if (result.size() > 0) return MusicBackupResult(portableDownloads.size, own.size, skipped)
        }
        throw IOException("Копия обновляется на другом устройстве. Повторите сохранение")
    }

    fun share(token: String, uid: String, playlist: DesktopPlaylist, ownedCode: String?): Pair<String, PlaylistShareResult> {
        if (!visibleTo(playlist, uid)) throw IOException("Плейлист принадлежит другому аккаунту")
        val tracks = playlist.tracks.mapNotNull(::portableTrack).distinctBy { it.text("id") }
        if (tracks.isEmpty()) throw IOException("В плейлисте нет онлайн-треков для отправки")
        if (tracks.size > 1000) throw IOException("В одной ссылке можно передать до 1000 треков")
        val body = JsonObject().apply {
            addProperty("title", playlist.name)
            add("tracks", JsonArray().apply { tracks.forEach(::add) })
        }
        var result = JsonArray()
        if (ownedCode != null && codePattern.matches(ownedCode)) {
            result = rows(request(token, "shared_playlists")
                .url(endpoint("shared_playlists").addQueryParameter("code", "eq.$ownedCode")
                    .addQueryParameter("owner_id", "eq.$uid").build())
                .header("Prefer", "return=representation").patch(body.toString().toRequestBody(CloudProfiles.JSON)).build())
        }
        if (result.size() == 0) {
            body.addProperty("owner_id", uid)
            result = rows(request(token, "shared_playlists").header("Prefer", "return=representation")
                .post(body.toString().toRequestBody(CloudProfiles.JSON)).build())
        }
        val code = result.firstOrNull()?.asJsonObject?.text("code")?.takeIf(codePattern::matches)
            ?: throw IOException("Сервер не вернул ссылку на плейлист")
        return code to PlaylistShareResult(
            "https://mrx2827.github.io/amorxsyte/p.html#playlist/$code", tracks.size, playlist.tracks.size - tracks.size
        )
    }

    private fun request(token: String, table: String) = Request.Builder().url(endpoint(table).build())
        .header("apikey", CloudConfig.KEY).header("Authorization", "Bearer $token")

    private fun rows(request: Request): JsonArray {
        val (code, body) = execute(request)
        if (code !in 200..299) throw IOException("Не удалось сохранить в облако ($code)")
        return try { JsonParser.parseString(body).asJsonArray }
        catch (_: RuntimeException) { throw IOException("Не удалось прочитать ответ облака") }
    }

    private fun androidSong(track: JsonObject) = JsonObject().apply {
        val id = track.text("id")!!
        addProperty("id", "online_$id")
        addProperty("title", track.text("title"))
        addProperty("artist", track.text("artist"))
        addProperty("album", "AmorX")
        addProperty("albumId", "")
        add("duration", track.get("dur"))
        // Same lazy stream resolution used by the Android importer, without expiring stream URLs.
        addProperty("uri", youtube(id) + "&expire=0#ytm=$id")
        track.text("art")?.let { addProperty("artworkUri", it) }
        addProperty("dateAdded", System.currentTimeMillis())
        addProperty("dateModified", System.currentTimeMillis())
        addProperty("discNumber", 1)
    }

    private fun merge(existing: List<JsonElement>, incoming: List<JsonObject>, key: (JsonObject) -> String?): JsonArray {
        val values = linkedMapOf<String, JsonElement>()
        existing.forEachIndexed { index, value ->
            if (!value.isJsonObject) throw IOException("Список в облачной копии повреждён")
            values[key(value.asJsonObject) ?: "unknown:$index"] = value
        }
        incoming.forEach { value -> values.putIfAbsent(key(value) ?: throw IOException("Не удалось определить трек"), value) }
        return JsonArray().apply { values.values.forEach(::add) }
    }

    private fun JsonElement.decoded(): JsonElement = if (isJsonPrimitive && asJsonPrimitive.isString) {
        try { JsonParser.parseString(asString) } catch (_: RuntimeException) { throw IOException("Облачная копия повреждена") }
    } else this
    private fun JsonObject.array(key: String): List<JsonElement> = get(key)?.decoded()?.let {
        if (!it.isJsonArray) throw IOException("Не удалось прочитать список в копии")
        it.asJsonArray.toList()
    }.orEmpty()
    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
    private fun endpoint(table: String) = (CloudConfig.URL + "/rest/v1/$table").toHttpUrl().newBuilder()

    companion object {
        private val videoPattern = Regex("[A-Za-z0-9_-]{11}")
        private val codePattern = Regex("[a-f0-9]{8}")
        fun visibleTo(playlist: DesktopPlaylist, uid: String) = !playlist.id.startsWith("cloud:") || playlist.id.startsWith("cloud:$uid:")
        private fun youtube(id: String) = "https://www.youtube.com/watch?v=$id"
        private fun urlId(url: String?): String? {
            val parsed = url?.toHttpUrlOrNull() ?: return null
            val id = when (parsed.host.lowercase()) {
                "youtu.be", "www.youtu.be" -> parsed.pathSegments.firstOrNull()
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" ->
                    if (parsed.pathSegments.firstOrNull() in setOf("shorts", "embed", "live")) parsed.pathSegments.getOrNull(1) else parsed.queryParameter("v")
                else -> null
            }
            return id?.takeIf(videoPattern::matches)
        }
        private fun trackKey(track: JsonObject): String? {
            val id = track.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("online_")?.removePrefix("offline_")
            return id?.takeIf(videoPattern::matches) ?: listOf("videoUrl", "uri").firstNotNullOfOrNull {
                urlId(track.get(it)?.takeIf { el -> el.isJsonPrimitive }?.asString)
            } ?: track.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        }
        private fun portableTrack(track: Track): JsonObject? {
            val id = track.id.removePrefix("online_").removePrefix("offline_").takeIf(videoPattern::matches) ?: urlId(track.videoUrl) ?: return null
            return JsonObject().apply {
                addProperty("id", id)
                addProperty("title", track.title)
                addProperty("artist", track.artist)
                addProperty("dur", track.seconds.coerceAtLeast(0).toLong() * 1000)
                addProperty("art", track.artwork?.takeIf { it.toHttpUrlOrNull()?.scheme in setOf("http", "https") }
                    ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg")
            }
        }
        private fun portableTrack(track: LibraryTrack) = portableTrack(Track(
            track.id, track.title, track.artist, track.seconds, colorFor(track.id), track.artwork, track.videoUrl
        ))
    }
}

/** Codes are scoped to both the signed-in user and the local playlist. */
internal object DesktopPlaylistLinks {
    private val file = File(AppDir.root, "playlist-links.json")
    private val links = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrElse { JsonObject() }
    @Synchronized fun get(uid: String, playlist: DesktopPlaylist): String? =
        links.get("$uid:${playlist.id}")?.asString ?: playlist.id.takeIf { it.startsWith("cloud:$uid:shared:") }?.substringAfterLast(':')
    @Synchronized fun put(uid: String, id: String, code: String) {
        val next = links.deepCopy().apply { addProperty("$uid:$id", code) }
        writeLocalJson(file, next.toString())
        links.addProperty("$uid:$id", code)
    }
}
