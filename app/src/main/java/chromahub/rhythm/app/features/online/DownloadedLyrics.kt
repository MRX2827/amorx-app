package chromahub.rhythm.app.features.online

import android.content.Context
import chromahub.rhythm.app.network.NetworkClient
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.LyricsData
import chromahub.rhythm.app.util.LyricsCacheIo
import chromahub.rhythm.app.util.PlayerLyricsLookup
import chromahub.rhythm.app.core.utils.NetworkUtils
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Audio download completion never waits for a lyrics service. One worker prepares offline lyrics. */
internal object DownloadedLyrics {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Semaphore(1)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    fun prepare(context: Context, track: OnlineTrack) {
        if(!queued.add(track.id)) return
        val app = context.applicationContext
        scope.launch {
            try {
                worker.withPermit {
                    val songId = "online_${track.id}"
                    val file = File(app.filesDir, "lyrics/" + PlayerLyricsLookup.cacheFileName(track.artist, track.title, songId))
                    val gson = Gson()
                    val legacyName = "${track.artist}_${track.title}.json".replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val compatible = PlayerLyricsLookup.cacheFileNames(track.artist, track.title, songId) + legacyName
                    for(name in compatible) {
                        val cached = runCatching { gson.fromJson(File(app.filesDir,"lyrics/$name").readText(),LyricsData::class.java) }.getOrNull()
                        if(cached != null && cached.hasLyrics() && !cached.isErrorMessage()) {
                            val downloaded = cached.source?.let { source -> listOf("API","LRCLib","lyrics.ovh","Lyrically","Better Lyrics").any { source.startsWith(it) } } == true
                            if(name == legacyName && downloaded && NetworkUtils.isNetworkAvailable(app)) continue
                            LyricsCacheIo.write(file,gson.toJson(cached),onlyIfMissing=true)
                            return@withPermit
                        }
                    }
                    delay(3000) // Bulk audio downloads must not flood lyrics providers.
                    if(!NetworkUtils.isNetworkAvailable(app)) return@withPermit
                    val correction = AppSettings.getInstance(app).getSongLyricsSearch(songId)
                    val artist = correction?.first ?: track.artist
                    val title = correction?.second ?: track.title
                    val lyrics = PlayerLyricsLookup.find(NetworkClient.playerLyricsApiService, artist, title, correction == null, track.durationMs)
                        ?: PlayerLyricsLookup.findPlain(NetworkClient.playerPlainLyricsApiService, artist, title, correction == null)
                    if(lyrics != null && lyrics.hasLyrics() && !lyrics.isErrorMessage())
                        LyricsCacheIo.write(file, gson.toJson(lyrics), onlyIfMissing = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { /* Playback and audio downloads remain available if a provider fails. */ }
            finally { queued.remove(track.id) }
        }
    }
}
