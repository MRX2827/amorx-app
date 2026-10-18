package chromahub.rhythm.app.features.online

import android.content.Context
import android.widget.Toast
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object ArtworkUtil {
    fun videoId(id: String): String? = when {
        id.startsWith("online_") -> id.removePrefix("online_")
        id.startsWith("offline_") -> id.removePrefix("offline_")
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** Обложка, которую увидит другой телефон: только http(s). Для песен YouTube берём миниатюру по id видео. */
    fun forSong(id: String, artwork: String?): String? {
        if (artwork != null && artwork.startsWith("http")) return artwork
        val vid = videoId(id) ?: return null
        return "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
    }
}

/** Любимые песни профиля. Хранятся на телефоне, не зависят от лайков. */
object ProfileSongs {
    private const val PREFS = "amorx_profile_songs"
    private const val KEY = "songs"
    private const val MAX = 50

    val songs = MutableStateFlow<List<CloudSong>>(emptyList())
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var playing = false

    fun normId(id: String): String =
        if (id.startsWith("offline_")) "online_" + id.removePrefix("offline_") else id

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        songs.value = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                CloudSong(
                    o.getString("id"),
                    o.optString("title"),
                    o.optString("artist"),
                    if (o.isNull("artwork")) null else o.optString("artwork"),
                    o.optLong("dur", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val ctx = appContext ?: return
        val a = JSONArray()
        songs.value.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("artist", it.artist)
                    .put("artwork", it.artwork ?: JSONObject.NULL)
                    .put("dur", it.dur)
            )
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun add(song: Song) {
        val id = normId(song.id)
        if (songs.value.any { it.id == id }) return
        val entry = CloudSong(
            id, song.title, song.artist,
            ArtworkUtil.forSong(song.id, song.artworkUri?.toString()),
            song.duration
        )
        songs.value = (listOf(entry) + songs.value).take(MAX)
        save()
    }

    fun remove(id: String) {
        val n = normId(id)
        songs.value = songs.value.filter { it.id != n }
        save()
    }

    /** Один раз переносит прежние лайки в список профиля. */
    fun migrateFromLikes(liked: List<Song>) {
        val ctx = appContext ?: return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("migrated", false) || liked.isEmpty()) return
        liked.reversed().forEach { add(it) }
        p.edit().putBoolean("migrated", true).apply()
    }

    /** Скачанная песня играет с телефона, остальные берутся из YouTube Music. */
    fun play(context: Context, vm: MusicViewModel, song: CloudSong) {
        if (playing) return
        OnlineDownloads.init(context)
        val vid = ArtworkUtil.videoId(song.id)
        val local = vid?.let { v -> OnlineDownloads.items.value.firstOrNull { it.id == v } }
        if (local != null) {
            vm.playSong(local.toSong())
            return
        }
        playing = true
        scope.launch {
            val resolved = runCatching { CloudProfiles.resolveSong(song) }
            playing = false
            resolved.onSuccess { vm.playSong(it) }
                .onFailure {
                    Toast.makeText(context.applicationContext, "Не удалось найти песню", Toast.LENGTH_SHORT).show()
                }
        }
    }
}

/** Песня, которой хотят поделиться из плеера. Диалог выбора рисуется в ShareToChatHost. */
object ChatShare {
    val pending = MutableStateFlow<CloudSong?>(null)

    fun request(song: Song) {
        pending.value = CloudSong(
            ProfileSongs.normId(song.id), song.title, song.artist,
            ArtworkUtil.forSong(song.id, song.artworkUri?.toString()),
            song.duration
        )
    }
}
