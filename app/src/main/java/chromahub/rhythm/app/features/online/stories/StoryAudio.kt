package chromahub.rhythm.app.features.online.stories

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import chromahub.rhythm.app.features.online.ArtworkUtil
import chromahub.rhythm.app.features.online.CloudProfiles
import chromahub.rhythm.app.features.online.CloudSong
import chromahub.rhythm.app.features.online.OnlineDownloads
import chromahub.rhythm.app.features.online.toSong
import chromahub.rhythm.app.infrastructure.service.player.AudioCacheManager
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
internal object StoryAudio {
    const val DEFAULT_CLIP_MS = 15_000L
    const val MIN_CLIP_MS = 5_000L
    const val MAX_CLIP_MS = 30_000L
    const val LOAD_TIMEOUT_MS = 9_000L

    private const val TTL_MS = 25 * 60_000L
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LinkedHashMap<CloudSong, Pair<Long, Song>>()
    private val pending = mutableMapOf<CloudSong, Deferred<Song?>>()

    suspend fun resolve(context: Context, song: CloudSong): Song? {
        // Старые истории из «Моего плейлиста» содержат ID видео без префикса.
        val videoId = ArtworkUtil.videoId(song.id)
            ?: song.id.takeIf { videoIdPattern.matches(it) && it.any { char -> !char.isDigit() } }
        if (videoId != null) {
            val downloaded = withContext(Dispatchers.IO) {
                OnlineDownloads.init(context)
                OnlineDownloads.items.value.firstOrNull { it.id == videoId && File(it.audioPath).isFile }
            }
            if (downloaded != null) return downloaded.toSong()
        }
        val request = synchronized(this) {
            val cached = cache[song]
            if (cached != null && SystemClock.elapsedRealtime() - cached.first < TTL_MS) {
                return cached.second
            }
            cache.remove(song)
            pending[song] ?: scope.async(start = CoroutineStart.LAZY) {
                val remote = if (videoId == null) song else song.copy(id = "online_$videoId")
                val found = resolveRemote(remote) ?: if (remote != song && ArtworkUtil.videoId(song.id) == null) {
                    resolveRemote(song)
                } else null
                if (found != null) synchronized(this@StoryAudio) {
                    cache[song] = SystemClock.elapsedRealtime() to found
                    if (cache.size > 40) cache.remove(cache.keys.first())
                }
                found
            }.also { deferred ->
                pending[song] = deferred
                deferred.invokeOnCompletion { synchronized(this) { pending.remove(song, deferred) } }
            }
        }
        return request.await()
    }

    private suspend fun resolveRemote(song: CloudSong): Song? = try {
        CloudProfiles.resolveSong(song)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun clip(song: Song, id: String, startMs: Long, endMs: Long): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(song.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

    fun dataSourceFactory(context: Context): DefaultDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
        val cache = CacheDataSource.Factory()
            .setCache(AudioCacheManager.getCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultDataSource.Factory(context, cache)
    }

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(5_000, 15_000, 350, 750)
        .build()
}

/** Готовит первые секунды выбранных фрагментов, пока открыта лента или соседняя история. */
@OptIn(UnstableApi::class)
internal class StoryAudioPreloader(private val context: Context) {
    private val builder = DefaultPreloadManager.Builder(context) {
        DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(2_000L)
    }
        .setDataSourceFactory(StoryAudio.dataSourceFactory(context))
        .setLoadControl(StoryAudio.loadControl())
    private val manager = builder.build()
    private val items = mutableMapOf<String, MediaItem>()
    private val resolveSlots = Semaphore(2)

    fun buildPlayer(): ExoPlayer = builder.buildExoPlayer()

    suspend fun preload(stories: List<Story>) = coroutineScope {
        val targets = stories.filter { !it.isVideo && it.song != null }.distinctBy { it.id }.take(6)
        val wanted = targets.mapTo(HashSet()) { it.id }
        val obsolete = items.keys.filterNot { it in wanted }
        obsolete.forEach { id -> items.remove(id)?.let { manager.remove(it) } }
        targets.forEachIndexed { index, story ->
            launch {
                val song = resolveSlots.withPermit { StoryAudio.resolve(context, checkNotNull(story.song)) }
                    ?: return@launch
                val item = StoryAudio.clip(song, story.id, story.clipStartMs, story.clipEndMs)
                val previous = items[story.id]
                if (previous == item) return@launch
                if (previous != null) manager.remove(previous)
                items[story.id] = item
                manager.add(item, index)
                manager.invalidate()
            }
        }
    }

    fun setClip(player: ExoPlayer, story: Story, song: Song) {
        val item = StoryAudio.clip(song, story.id, story.clipStartMs, story.clipEndMs)
        val source = manager.getMediaSource(item)
        if (source != null) player.setMediaSource(source) else player.setMediaItem(item)
    }

    fun release() = manager.release()
}
