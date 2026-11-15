package amorx

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

internal class CloudProfileRemote(private val execute: (Request) -> Pair<Int, String>) {
    fun fetch(token: String, uid: String): MyProfile {
        val row = ownRow(token, uid, "username,bio,avatar_b64,is_public,updated_at")
            ?: return backup(token, uid)?.profile ?: MyProfile()
        val profile = MyProfile(
            name = row.text("username").orEmpty(),
            bio = row.text("bio").orEmpty(),
            avatar = row.text("avatar_b64"),
            isPublic = row.flag("is_public")
        )
        if (profile.isPublic) return profile

        // Android keeps edits to a private profile in its account backup.
        val saved = try {
            backup(token, uid)
        } catch (_: IOException) {
            null
        }
        val changed = row.text("updated_at")?.instant()
        return if (saved?.updatedAt != null && !saved.profile.isPublic && changed != null && saved.updatedAt > changed) {
            saved.profile
        } else profile
    }

    fun save(
        token: String,
        uid: String,
        profile: MyProfile,
        guestArtists: List<CloudArtist>? = null,
        guestSongs: List<CloudSong>? = null
    ) {
        val body = JsonObject().apply {
            addProperty("id", uid)
            addProperty("username", profile.name.trim())
            addProperty("bio", profile.bio.trim())
            putNullable("avatar_b64", profile.avatar)
            addProperty("is_public", profile.isPublic)
            addProperty("updated_at", Instant.now().toString())
            guestArtists?.let { artists ->
                add("favorite_artists", JsonArray().apply {
                    artists.take(50).forEach { artist ->
                        add(JsonObject().apply {
                            addProperty("name", artist.name)
                            putNullable("avatar", artist.avatar)
                        })
                    }
                })
            }
            guestSongs?.let { songs ->
                add("favorite_songs", JsonArray().apply {
                    songs.take(50).forEach { add(it.json()) }
                })
            }
        }
        val url = endpoint("profiles").addQueryParameter("on_conflict", "id").build()
        val (code, response) = execute(
            request(token).url(url)
                .header("Prefer", "resolution=merge-duplicates,missing=default,return=minimal")
                .post(body.toString().toRequestBody(CloudProfiles.JSON)).build()
        )
        if (code !in 200..299) {
            throw IOException(when {
                response.contains("23505") -> "Это имя уже занято, выберите другое"
                code == 404 -> "Таблица profiles не найдена в Supabase"
                else -> "Не удалось сохранить профиль ($code)"
            })
        }
    }

    fun setFavorite(token: String, uid: String, song: CloudSong, liked: Boolean) {
        val row = ownRow(token, uid, "favorite_songs") ?: return
        val songs = row.get("favorite_songs")?.takeUnless { it.isJsonNull }?.asJsonArray ?: JsonArray()
        fun matches(entry: JsonElement) =
            entry.isJsonObject && entry.asJsonObject.text("id") == song.id
        if (songs.any(::matches) == liked) return

        val updated = JsonArray().apply {
            if (liked) add(song.json())
            songs.forEach { if (!matches(it)) add(it) }
        }
        val body = JsonObject().apply { add("favorite_songs", updated) }
        val url = endpoint("profiles").addQueryParameter("id", "eq.$uid").build()
        val (code, _) = execute(
            request(token).url(url).header("Prefer", "return=minimal")
                .patch(body.toString().toRequestBody(CloudProfiles.JSON)).build()
        )
        if (code !in 200..299) throw IOException("Не удалось сохранить избранное в профиле ($code)")
    }

    private fun ownRow(token: String, uid: String, fields: String): JsonObject? =
        readRow(token, uid, "profiles", "id", fields, "Не удалось загрузить профиль")

    private data class ProfileBackup(val profile: MyProfile, val updatedAt: Instant?)

    private fun backup(token: String, uid: String): ProfileBackup? {
        val row = readRow(token, uid, "backups", "user_id", "payload,updated_at",
            "Не удалось загрузить облачную копию профиля") ?: return null
        val payload = row.get("payload")?.takeUnless { it.isJsonNull } ?: return null
        val raw = if (payload.isJsonPrimitive && payload.asJsonPrimitive.isString) {
            JsonParser.parseString(payload.asString)
        } else payload
        if (!raw.isJsonObject) throw IOException("Некорректная облачная копия профиля")
        val profile = raw.asJsonObject.get("amorx_profile")?.takeUnless { it.isJsonNull } ?: return null
        if (!profile.isJsonObject) throw IOException("Некорректная облачная копия профиля")
        val data = profile.asJsonObject
        return ProfileBackup(
            MyProfile(
                name = data.text("name").orEmpty(),
                bio = data.text("bio").orEmpty(),
                avatar = data.text("avatar"),
                isPublic = data.flag("public")
            ),
            row.text("updated_at")?.instant()
        )
    }

    private fun readRow(
        token: String, uid: String, table: String, owner: String, fields: String, failure: String
    ): JsonObject? {
        val url = endpoint(table).addQueryParameter("select", "$owner,$fields")
            .addQueryParameter(owner, "eq.$uid").addQueryParameter("limit", "1").build()
        val (code, response) = execute(request(token).url(url).get().build())
        if (code !in 200..299) throw IOException("$failure ($code)")
        val rows = JsonParser.parseString(response).asJsonArray
        if (rows.size() == 0) return null
        val row = rows[0].asJsonObject
        if (row.text(owner) != uid) throw IOException(failure)
        return row
    }

    private fun endpoint(table: String) = (CloudConfig.URL + "/rest/v1/$table").toHttpUrl().newBuilder()

    private fun request(token: String) = Request.Builder()
        .header("apikey", CloudConfig.KEY)
        .header("Authorization", "Bearer $token")

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.flag(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    private fun String.instant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

    private fun JsonObject.putNullable(key: String, value: String?) {
        if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value)
    }

    private fun CloudSong.json() = JsonObject().apply {
        addProperty("id", id)
        addProperty("title", title)
        addProperty("artist", artist)
        putNullable("artwork", artwork)
        addProperty("dur", dur)
    }
}
