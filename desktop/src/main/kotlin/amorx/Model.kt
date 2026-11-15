package amorx

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

val demoTracks = listOf(
    Track("d1", "Ночной город", "Исполнитель один", 208, Color(0xFF7E57C2)),
    Track("d2", "Волна", "Исполнитель два", 185, Color(0xFFD81B60)),
    Track("d3", "Дорога домой", "Исполнитель три", 232, Color(0xFF1E88E5))
)

class PlayerState {
    var current by mutableStateOf<Track?>(null)
    var playing by mutableStateOf(false)
    var position by mutableStateOf(0f)
    var duration by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    @Volatile var queue: List<Track> = demoTracks

    private var waveFeed: WaveFeed? = null
    private var topping = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    init {
        Engine.onFinished = { scope.launch { next() } }
    }

    fun play(t: Track, list: List<Track>? = null, wave: WaveFeed? = null) {
        if (list != null) {
            queue = list
            waveFeed = wave
        }
        if (current?.id == t.id && !loading && error == null) {
            playing = true
            if (t.isReal) Engine.setPause(false)
            return
        }
        current = t
        position = 0f
        duration = t.seconds
        playing = true
        error = null
        job?.cancel()
        val local = t.localPath ?: Downloads.find(t.id)?.audioPath
        val url = t.videoUrl
        if (local == null && url == null) return
        job = scope.launch {
            loading = true
            try {
                Engine.play(local ?: Repo.resolveAudio(url!!))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                playing = false
            } finally {
                loading = false
            }
        }
    }

    fun toggle() {
        val t = current ?: return
        playing = !playing
        if (t.isReal && !loading) Engine.setPause(!playing)
    }

    fun seek(sec: Float) {
        position = sec
        val t = current ?: return
        if (t.isReal && !loading) Engine.seek((sec * 1000).toLong())
    }

    fun tick() {
        val t = current ?: return
        if (!playing || loading) return
        if (!t.isReal) {
            position += 0.25f
            if (position >= t.seconds) next()
        } else {
            val ms = Engine.timeMs()
            if (ms >= 0) position = ms / 1000f
            val length = Engine.lengthMs()
            if (length >= 1000) duration = (length / 1000).toInt()
        }
    }

    private fun index(): Int = queue.indexOfFirst { it.id == current?.id }

    /** Волна бесконечная: когда в очереди остаётся мало песен, подгружаем следующие. */
    private fun topUp() {
        val f = waveFeed ?: return
        if (topping) return
        topping = true
        scope.launch {
            try {
                val more = f.next(8)
                if (more.isNotEmpty()) queue = queue + more
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
            } finally {
                topping = false
            }
        }
    }

    fun next() {
        val q = queue
        if (q.isEmpty()) return
        val i = index()
        if (waveFeed != null && i >= q.size - 4) topUp()
        val target = q[(i + 1).mod(q.size)]
        current = null
        play(target)
    }

    fun prev() {
        val q = queue
        if (q.isEmpty()) return
        val i = index()
        val target = q[(i - 1).mod(q.size)]
        current = null
        play(target)
    }
}

fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
