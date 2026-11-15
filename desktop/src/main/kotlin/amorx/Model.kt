package amorx

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
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

class PlayerState(private val scope: CoroutineScope) {
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

    private var waveFeed: WaveFeed? = null
    private var topping: WaveFeed? = null
    private var job: Job? = null
    private var request = 0
    private val audioCommands = Mutex()
    private val shuffleBack = ArrayDeque<String>()

    init {
        Engine.onFinished = {
            scope.launch {
                if (playing && !loading) {
                    if (repeat) current?.let { start(it) }
                    else next()
                }
            }
        }
    }

    fun play(t: Track, list: List<Track>? = null, wave: WaveFeed? = null) {
        if (list != null || queue.isEmpty()) {
            queue = list ?: listOf(t)
            waveFeed = wave
            shuffleBack.clear()
        }
        if (current?.id == t.id && loading) return
        if (current?.id == t.id && error == null) {
            if (!playing) toggle()
            return
        }
        start(t)
    }

    private fun start(track: Track) {
        val loadingRequest = ++request
        job?.cancel()
        current = track
        position = 0f
        duration = track.seconds
        error = null
        playing = true
        loading = true
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { audioCommands.withLock { Engine.stop() } }
                val local = track.localPath ?: Downloads.find(track.id)?.audioPath
                val source = local ?: track.videoUrl?.let { Repo.resolveAudio(it) }
                    ?: error("Аудиофайл не найден")
                withContext(Dispatchers.IO) {
                    audioCommands.withLock {
                        ensureActive()
                        Engine.play(source)
                    }
                }
                if (!playing) Engine.setPause(true)
                if (track.isReal) launch(Dispatchers.IO) { PlaybackHistory.record(track) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                playing = false
            } finally {
                if (request == loadingRequest) loading = false
            }
        }
    }

    fun toggle() {
        val track = current ?: return
        if (error != null) {
            start(track)
            return
        }
        playing = !playing
        if (!loading) Engine.setPause(!playing)
    }

    fun seek(sec: Float) {
        if (current == null || loading || error != null) return
        position = sec.coerceIn(0f, duration.toFloat())
        Engine.seek((position * 1000).toLong())
    }

    fun tick() {
        if (current == null || !playing || loading) return
        val ms = Engine.timeMs()
        if (ms >= 0) position = ms / 1000f
        val length = Engine.lengthMs()
        if (length >= 1000) duration = (length / 1000).toInt()
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        shuffleBack.clear()
    }

    private fun topUp() {
        val feed = waveFeed ?: return
        if (topping === feed) return
        topping = feed
        scope.launch {
            try {
                val more = withContext(Dispatchers.IO) { feed.next(8) }
                if (waveFeed === feed && more.isNotEmpty()) queue = (queue + more).distinctBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the current queue if recommendations are temporarily unavailable.
            } finally {
                if (topping === feed) topping = null
            }
        }
    }

    fun next() {
        val tracks = queue
        if (tracks.isEmpty()) return
        val id = current?.id
        val index = tracks.indexOfFirst { it.id == id }
        if (index >= tracks.size - 4) topUp()
        val ordered = tracks[(index + 1).mod(tracks.size)]
        if (!shuffle || tracks.size == 1 || id == null) {
            start(ordered)
            return
        }
        val choices = tracks.filter { it.id != id && it.id !in shuffleBack }
        val pick = choices.randomOrNull() ?: tracks.filter { it.id != id }.random()
        shuffleBack.addLast(id)
        if (shuffleBack.size >= tracks.size) shuffleBack.removeFirst()
        start(pick)
    }

    fun prev() {
        val tracks = queue
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == current?.id }
        val previous = if (shuffle) shuffleBack.removeLastOrNull() else null
        start(tracks.firstOrNull { it.id == previous } ?: tracks[(index - 1).mod(tracks.size)])
    }
}

fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
