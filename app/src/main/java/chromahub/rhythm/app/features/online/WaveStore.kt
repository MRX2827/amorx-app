package chromahub.rhythm.app.features.online

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class WaveArtist(val name: String, val avatarUrl: String?)

/** Артисты, выбранные для «Моей волны». Хранятся в SharedPreferences. */
object WaveStore {
    private const val PREFS = "online_wave"
    private const val KEY = "artists"
    private const val SEP_ITEM = "\u001E"
    private const val SEP_FIELD = "\u001F"

    val artists = MutableStateFlow<List<WaveArtist>>(emptyList())
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = appContext!!
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        artists.value = raw.split(SEP_ITEM)
            .filter { it.isNotBlank() }
            .map { entry ->
                val parts = entry.split(SEP_FIELD)
                WaveArtist(parts[0], parts.getOrNull(1)?.takeIf { it.isNotBlank() })
            }
    }

    private fun save() {
        val ctx = appContext ?: return
        val raw = artists.value.joinToString(SEP_ITEM) { it.name + SEP_FIELD + (it.avatarUrl ?: "") }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    fun add(artist: WaveArtist) {
        if (artists.value.any { it.name.equals(artist.name, ignoreCase = true) }) return
        artists.value = artists.value + artist
        save()
    }

    fun remove(name: String) {
        artists.value = artists.value.filter { !it.name.equals(name, ignoreCase = true) }
        save()
    }
}
