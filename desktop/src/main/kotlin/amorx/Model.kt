package amorx

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import amorx.playback.NoRepeatShuffle
import amorx.playback.playbackIdentity
import amorx.playback.uniquePlaybackTracks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val seconds: Int,
    val color: Color,
    val artwork: String? = null,
    val videoUrl: String? = null,
    val localPath: String? = null
) {
    val isReal: Boolean get() = videoUrl != null || localPath != null
}

private val paletteM = listOf(
    Color(0xFF7E57C2), Color(0xFFD81B60), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFF4511E), Color(0xFF00897B), Color(0xFFFB8C00), Color(0xFF5E35B1)
)

fun colorFor(id: String): Color = paletteM[(id.hashCode() and 0x7fffffff) % paletteM.size]

class PlayerState internal constructor(
    private val scope: CoroutineScope,
    private val audio: AudioOutput = Engine,
    private val resolveAudio: suspend (String) -> String = Repo::resolveAudio,
    private val findLocal: (Track) -> String? = { it.localPath ?: Downloads.find(it.id)?.audioPath },
    private val record: (Track) -> Unit = PlaybackHistory::record,
    private val startupTimeoutMs: Long = 30_000
) {
    var current by mutableStateOf<Track?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableStateOf(0f)
        private set
    var duration by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var queue by mutableStateOf(emptyList<Track>())
        private set
    var repeat by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
        private set

    private var waveFeed: WaveFeed? = null
    private var topping: WaveFeed? = null
    private var toppingJob: Job? = null
    private var job: Job? = null
    private var watchdog: Job? = null
    @Volatile private var request = 0
    private var navigation = 0
    private var advancing = false
    private val audioCommands = Mutex()
    private val shuffleOrder = NoRepeatShuffle()

    fun play(t: Track, list: List<Track>? = null, wave: WaveFeed? = null) {
        navigation++
        advancing = false
        val nextQueue = uniquePlaybackTracks(list ?: queue.ifEmpty { listOf(t) }, Track::id, t.id)
        val changed = nextQueue.map { playbackIdentity(it.id) } != queue.map { playbackIdentity(it.id) } || waveFeed !== wave && list != null
        if (changed || queue.isEmpty()) {
            queue = nextQueue
            waveFeed = wave
            shuffleOrder.reset(queue.map(Track::id), t.id)
        } else shuffleOrder.select(t.id)
        if (current?.id == t.id && loading) return
        if (current?.id == t.id && error == null) {
            if (!playing) toggle()
            return
        }
        start(t)
    }

    fun playShuffled(tracks: List<Track>) {
        val unique = uniquePlaybackTracks(tracks, Track::id)
        if (unique.isEmpty()) return
        shuffle = true
        repeat = false
        // Starting a shuffle is a new listening cycle, even when its source is unchanged.
        queue = emptyList()
        play(unique.random(), unique)
    }

    private suspend fun <T> audioCall(generation: Int, action: () -> T): T = withContext(Dispatchers.IO) {
        audioCommands.withLock {
            ensureActive()
            if (request != generation) throw CancellationException("Superseded playback")
            action()
        }
    }

    private fun fail(generation: Int, message: String) {
        if (request != generation) return
        error = message
        loading = false
        playing = false
    }

    private fun start(track: Track) {
        val generation = ++request
        job?.cancel()
        watchdog?.cancel()
        current = track
        position = 0f
        duration = track.seconds
        error = null
        playing = true
        loading = true
        // This timeout is independent of a blocking network/native call, so UI controls recover.
        watchdog = scope.launch {
            delay(startupTimeoutMs)
            if (request == generation && loading) {
                fail(generation, "Трек не отвечает. Нажмите воспроизведение для повтора или выберите следующий")
                job?.cancel()
            }
        }
        job = scope.launch {
            try {
                audioCall(generation) { audio.stop() }
                val source = withContext(Dispatchers.IO) { findLocal(track) }
                    ?: track.videoUrl?.let { resolveAudio(it) } ?: error("Аудиофайл не найден")
                audioCall(generation) { audio.play(source) }
                while (true) {
                    val state = audioCall(generation) { audio.snapshot() }
                    if (state.phase == AudioPhase.Failed) error("Не удалось воспроизвести трек")
                    if (state.phase in setOf(AudioPhase.Playing, AudioPhase.Paused, AudioPhase.Ended)) break
                    delay(100)
                }
                audioCall(generation) { audio.setPause(!playing) }
                if (track.isReal) launch(Dispatchers.IO) { record(track) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(generation, failure.message ?: "Не удалось воспроизвести трек")
            } finally {
                if (request == generation) {
                    loading = false
                    watchdog?.cancel()
                }
            }
        }
    }

    private fun command(action: () -> Unit) {
        val generation = request
        scope.launch {
            try { audioCall(generation, action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(generation, failure.message ?: "Ошибка управления воспроизведением") }
        }
    }

    fun toggle() {
        val track = current ?: return
        if (error != null) { start(track); return }
        playing = !playing
        if (!loading) command { audio.setPause(!playing) }
    }

    fun seek(sec: Float) {
        if (current == null || loading || error != null) return
        position = sec.coerceIn(0f, duration.toFloat())
        val target = (position * 1000).toLong()
        command { audio.seek(target) }
    }

    suspend fun tick() {
        if (current == null || loading || error != null) return
        val generation = request
        val state = try { audioCall(generation) { audio.snapshot() } }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); return }
        catch (failure: Exception) { fail(generation, failure.message ?: "Аудиодвижок не отвечает"); return }
        if (request != generation || loading) return
        if (state.timeMs >= 0) position = state.timeMs / 1000f
        if (state.lengthMs >= 1000) duration = (state.lengthMs / 1000).toInt()
        when (state.phase) {
            AudioPhase.Failed, AudioPhase.Stopped -> fail(generation, "Воспроизведение прервано. Нажмите воспроизведение для повтора")
            AudioPhase.Ended -> if (playing && !advancing) {
                if (repeat) current?.let(::start) else next()
            }
            else -> Unit
        }
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        if (shuffle) repeat = false
    }

    private fun topUp(): Job? {
        val feed = waveFeed ?: return null
        if (topping === feed) return toppingJob
        topping = feed
        toppingJob = scope.launch {
            try {
                val more = withContext(Dispatchers.IO) { feed.next(8) }
                if (waveFeed === feed && more.isNotEmpty()) queue = uniquePlaybackTracks(queue + more, Track::id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A temporary recommendation failure leaves the current queue available. */ }
            finally { if (topping === feed) topping = null }
        }
        return toppingJob
    }

    fun next() {
        val token = ++navigation
        val tracks = queue
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == current?.id }
        val exhausted = if (shuffle) shuffleOrder.remainingCount == 0 else index == tracks.lastIndex
        val refill = if (waveFeed != null && (exhausted || if (shuffle) shuffleOrder.remainingCount <= 4 else index >= tracks.size - 4)) topUp() else null
        if (exhausted && refill != null) {
            advancing = true
            scope.launch {
                refill.join()
                if (navigation == token) { advancing = false; advance() }
            }
        } else { advancing = false; advance() }
    }

    private fun advance() {
        val tracks = queue
        if (tracks.isEmpty()) return
        val selected = if (shuffle) {
            val id = shuffleOrder.next(tracks.map(Track::id), current?.id)
            tracks.firstOrNull { playbackIdentity(it.id) == id }
        } else {
            val index = tracks.indexOfFirst { it.id == current?.id }
            tracks[(index + 1).mod(tracks.size)].also { shuffleOrder.select(it.id) }
        }
        selected?.let(::start)
    }

    fun prev() {
        navigation++
        advancing = false
        val tracks = queue
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == current?.id }
        val previous = if (shuffle) shuffleOrder.previous(tracks.map(Track::id)) else null
        // At the start of shuffle history, Previous restarts the current song instead of inventing a repeat.
        val selected = if (shuffle) tracks.firstOrNull { playbackIdentity(it.id) == previous } ?: current
            else tracks[(index - 1).mod(tracks.size)]
        selected?.let(::start)
    }
}

fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
