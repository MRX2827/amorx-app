package chromahub.rhythm.app.features.online.stories

import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class FriendEntry(val id: String, val name: String, val avatarB64: String?)

data class FriendsState(
    val friends: List<FriendEntry> = emptyList(),
    val incoming: List<FriendEntry> = emptyList(),
    val outgoing: List<FriendEntry> = emptyList()
)

enum class FriendStatus { NONE, OUTGOING, INCOMING, FRIENDS }

/**
 * Друзья (Supabase, таблица friendships). Историю видят только подтверждённые друзья.
 * Запрос отправляет один человек, второй сам решает: принять или отклонить.
 */
object CloudFriends {
    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state.asStateFlow()

    fun statusOf(id: String): FriendStatus {
        val s = _state.value
        return when {
            s.friends.any { it.id == id } -> FriendStatus.FRIENDS
            s.incoming.any { it.id == id } -> FriendStatus.INCOMING
            s.outgoing.any { it.id == id } -> FriendStatus.OUTGOING
            else -> FriendStatus.NONE
        }
    }

    private fun rest(token: String, path: String): Request.Builder =
        Request.Builder()
            .url(SupabaseConfig.URL + "/rest/v1/" + path)
            .header("apikey", SupabaseConfig.KEY)
            .header("Authorization", "Bearer $token")

    suspend fun refresh(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = CloudProfiles.session()
            val (code, body) = CloudProfiles.exec(
                rest(token, "friendships?select=requester_id,addressee_id,status&limit=500").get().build()
            )
            if (code !in 200..299) throw IOException("Не удалось загрузить друзей ($code)")
            val arr = JSONArray(body)
            data class Row(val other: String, val status: String, val mine: Boolean)
            val rows = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val req = o.getString("requester_id")
                val adr = o.getString("addressee_id")
                Row(if (req == uid) adr else req, o.getString("status"), req == uid)
            }

            val people = HashMap<String, FriendEntry>()
            if (rows.isNotEmpty()) {
                val ids = rows.map { it.other }.distinct().joinToString(",")
                val (pc, pb) = CloudProfiles.exec(
                    rest(token, "profiles?select=id,username,avatar_b64&id=in.($ids)").get().build()
                )
                if (pc in 200..299) {
                    val pa = JSONArray(pb)
                    for (i in 0 until pa.length()) {
                        val o = pa.getJSONObject(i)
                        val id = o.getString("id")
                        people[id] = FriendEntry(
                            id,
                            o.optString("username").ifBlank { "Без имени" },
                            if (o.isNull("avatar_b64")) null else o.getString("avatar_b64").takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
            fun entry(id: String) = people[id] ?: FriendEntry(id, "Без имени", null)

            _state.value = FriendsState(
                friends = rows.filter { it.status == "accepted" }.map { entry(it.other) }.sortedBy { it.name.lowercase() },
                incoming = rows.filter { it.status == "pending" && !it.mine }.map { entry(it.other) },
                outgoing = rows.filter { it.status == "pending" && it.mine }.map { entry(it.other) }
            )
        }
    }

    /** Если человек уже просил нас в друзья, запрос сразу принимается. */
    suspend fun request(userId: String): Result<Unit> {
        if (statusOf(userId) == FriendStatus.INCOMING) return accept(userId)
        return withContext(Dispatchers.IO) {
            runCatching {
                val (token, uid) = CloudProfiles.session()
                val body = JSONObject().put("requester_id", uid).put("addressee_id", userId)
                val req = rest(token, "friendships")
                    .header("Prefer", "return=minimal")
                    .post(body.toString().toRequestBody(CloudProfiles.JSON_TYPE))
                    .build()
                val (code, _) = CloudProfiles.exec(req)
                // 409: пара уже есть (например, запрос уже отправлен), для человека это тоже успех
                if (code !in 200..299 && code != 409) throw IOException("Не удалось отправить запрос ($code)")
            }
        }.also { refresh() }
    }

    suspend fun accept(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, _) = CloudProfiles.session()
            val req = rest(token, "rpc/accept_friend")
                .post(JSONObject().put("p_requester", userId).toString().toRequestBody(CloudProfiles.JSON_TYPE))
                .build()
            val (code, _) = CloudProfiles.exec(req)
            if (code !in 200..299) throw IOException("Не удалось принять запрос ($code)")
        }
    }.also { refresh() }

    /** Отклонить запрос, отменить свой запрос или удалить из друзей. */
    suspend fun remove(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (token, uid) = CloudProfiles.session()
            val filter = "or=(and(requester_id.eq.$uid,addressee_id.eq.$userId)," +
                "and(requester_id.eq.$userId,addressee_id.eq.$uid))"
            val (code, _) = CloudProfiles.exec(rest(token, "friendships?$filter").delete().build())
            if (code !in 200..299) throw IOException("Не удалось выполнить ($code)")
        }
    }.also { refresh() }
}
