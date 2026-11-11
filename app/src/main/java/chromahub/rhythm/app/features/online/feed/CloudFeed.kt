package chromahub.rhythm.app.features.online.feed

import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.SupabaseConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class FeedPost(
    val id: String,
    val authorId: String,
    val authorName: String,
    val caption: String,
    val mimeType: String,
    val path: String,
    val previewPath: String?,
    val createdAt: String,
    val likes: Int,
    val comments: Int,
    val liked: Boolean
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

internal data class FeedComment(val id: String, val authorName: String, val text: String, val createdAt: String)
internal data class FeedCounts(val followers: Int, val following: Int)
internal class FeedPage(val posts: List<FeedPost>, val hasMore: Boolean, val session: FeedSession)

internal class FeedSession(val userId: String, private val token: String) {
    val headers: Map<String, String>
        get() = mapOf("apikey" to SupabaseConfig.KEY, "Authorization" to "Bearer $token")

    fun request(url: String): Request.Builder = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }
}

internal object CloudFeed {
    private const val REST = SupabaseConfig.URL + "/rest/v1"
    private const val STORAGE = SupabaseConfig.URL + "/storage/v1/object"
    private const val BUCKET = "feed-media"
    private const val PAGE_SIZE = 20
    const val COMMENT_PAGE_SIZE = 40
    val changes = MutableStateFlow(0)

    fun mediaUrl(path: String): String = "$STORAGE/authenticated/$BUCKET/$path"

    suspend fun getSession(): Result<FeedSession> = network { session() }

    private fun session(): FeedSession {
        val (token, id) = CloudProfiles.session()
        return FeedSession(id, token)
    }

    suspend fun page(authorId: String?, before: FeedPost?, own: Boolean): Result<FeedPage> = network {
        val session = session()
        val args = JSONObject()
        val author = if (own) session.userId else authorId
        if (author != null) args.put("p_author", author)
        if (before != null) args.put("p_before", before.createdAt).put("p_before_id", before.id)
        val rows = rpc(session, "feed_page", args, "Не удалось загрузить публикации")
        val posts = (0 until minOf(PAGE_SIZE, rows.length())).map { index ->
            val row = rows.getJSONObject(index)
            FeedPost(
                id = row.getString("id"),
                authorId = row.getString("author_id"),
                authorName = row.getString("author_name"),
                caption = row.optString("caption"),
                mimeType = row.getString("media_type"),
                path = row.getString("media_path"),
                previewPath = row.optString("preview_path").takeUnless { row.isNull("preview_path") },
                createdAt = row.getString("created_at"),
                likes = row.optInt("likes"),
                comments = row.optInt("comments"),
                liked = row.optBoolean("liked")
            )
        }
        FeedPage(posts, rows.length() > PAGE_SIZE, session)
    }

    suspend fun counts(authorId: String? = null): Result<FeedCounts> = network {
        val session = session()
        val row = rpc(session, "feed_profile_counts", JSONObject().put("p_user", authorId ?: session.userId),
            "Не удалось загрузить подписки").optJSONObject(0)
        FeedCounts(row?.optInt("followers") ?: 0, row?.optInt("following") ?: 0)
    }

    suspend fun isFollowing(authorId: String): Result<Boolean> = network {
        val session = session()
        val url = table("feed_follows", "select" to "author_id", "follower_id" to "eq.${session.userId}",
            "author_id" to "eq.$authorId", "limit" to "1")
        JSONArray(send(session.request(url).get(), "Не удалось загрузить подписку")).length() > 0
    }

    suspend fun setFollowing(authorId: String, following: Boolean): Result<Unit> = network {
        val session = session()
        if (following) {
            insert(session, "feed_follows", JSONObject().put("follower_id", session.userId)
                .put("author_id", authorId), "Не удалось подписаться")
        } else {
            val url = table("feed_follows", "follower_id" to "eq.${session.userId}", "author_id" to "eq.$authorId")
            send(session.request(url).delete(), "Не удалось отменить подписку")
        }
        changes.update { it + 1 }
    }

    suspend fun publish(id: String, authorName: String, caption: String, media: PreparedFeedMedia): Result<Unit> = network {
        val session = session()
        val path = "${session.userId}/$id.${media.extension}"
        val previewPath = media.preview?.let { "${session.userId}/$id-preview.jpg" }
        val existing = table("feed_posts", "select" to "id", "id" to "eq.$id", "author_id" to "eq.${session.userId}")
        if (JSONArray(send(session.request(existing).get(), "Не удалось проверить публикацию")).length() == 0) {
            upload(session, path, media.file, media.mimeType)
            if (media.preview != null && previewPath != null) upload(session, previewPath, media.preview, "image/jpeg")
            val row = JSONObject().put("id", id).put("author_id", session.userId)
                .put("author_name", displayName(authorName)).put("caption", caption.trim().take(1000))
                .put("media_type", media.mimeType).put("media_path", path)
                .put("preview_path", previewPath ?: JSONObject.NULL)
            insert(session, "feed_posts", row, "Не удалось опубликовать")
        }
        changes.update { it + 1 }
    }

    suspend fun delete(post: FeedPost): Result<Unit> = network {
        val session = session()
        check(post.authorId == session.userId)
        val url = table("feed_posts", "id" to "eq.${post.id}", "author_id" to "eq.${session.userId}")
        send(session.request(url).delete(), "Не удалось удалить публикацию")
        for (path in listOfNotNull(post.path, post.previewPath)) {
            runCatching { CloudProfiles.exec(session.request("$STORAGE/$BUCKET/$path").delete().build()) }
        }
        changes.update { it + 1 }
    }

    suspend fun setLiked(post: FeedPost, liked: Boolean): Result<Unit> = network {
        val session = session()
        if (liked) {
            insert(session, "feed_likes", JSONObject().put("post_id", post.id)
                .put("user_id", session.userId), "Не удалось поставить отметку")
        } else {
            val url = table("feed_likes", "post_id" to "eq.${post.id}", "user_id" to "eq.${session.userId}")
            send(session.request(url).delete(), "Не удалось убрать отметку")
        }
    }

    suspend fun comments(postId: String, offset: Int): Result<List<FeedComment>> = network {
        val session = session()
        val url = table("feed_comments", "select" to "id,author_name,body,created_at", "post_id" to "eq.$postId",
            "order" to "created_at.desc,id.desc", "offset" to offset.toString(), "limit" to COMMENT_PAGE_SIZE.toString())
        val rows = JSONArray(send(session.request(url).get(), "Не удалось загрузить комментарии"))
        (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            FeedComment(row.getString("id"), row.getString("author_name"), row.getString("body"), row.getString("created_at"))
        }
    }

    suspend fun comment(postId: String, id: String, authorName: String, text: String): Result<Unit> = network {
        val session = session()
        val row = JSONObject().put("id", id).put("post_id", postId).put("user_id", session.userId)
            .put("author_name", displayName(authorName)).put("body", text.trim().take(500))
        insert(session, "feed_comments", row, "Не удалось отправить комментарий")
    }

    private fun displayName(name: String): String = name.trim().take(80).ifEmpty { "Без имени" }

    private fun table(name: String, vararg params: Pair<String, String>): String =
        "$REST/$name".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()

    private fun rpc(session: FeedSession, name: String, data: JSONObject, error: String): JSONArray =
        JSONArray(send(session.request("$REST/rpc/$name").post(data.toString().toRequestBody(CloudProfiles.JSON_TYPE)), error))

    private fun insert(session: FeedSession, table: String, row: JSONObject, error: String) {
        send(session.request("$REST/$table").header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(row.toString().toRequestBody(CloudProfiles.JSON_TYPE)), error)
    }

    private fun upload(session: FeedSession, path: String, file: File, type: String) {
        send(session.request("$STORAGE/$BUCKET/$path").header("x-upsert", "true")
            .post(file.asRequestBody(type.toMediaType())), "Не удалось загрузить файл")
    }

    private fun send(request: Request.Builder, error: String): String {
        val (code, body) = CloudProfiles.exec(request.build())
        if (code !in 200..299) throw IOException("$error ($code)")
        return body
    }

    private suspend fun <T> network(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
