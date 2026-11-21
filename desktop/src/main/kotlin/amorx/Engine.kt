package amorx

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.State
import java.io.File

enum class AudioPhase { Starting, Playing, Paused, Ended, Stopped, Failed }
data class AudioSnapshot(val phase: AudioPhase, val timeMs: Long = 0, val lengthMs: Long = 0)

interface AudioOutput {
    fun play(url: String)
    fun stop()
    fun setPause(pause: Boolean)
    fun seek(ms: Long)
    fun snapshot(): AudioSnapshot
}

/** All playback commands and status reads are issued by PlayerState on its audio dispatcher. */
object Engine : AudioOutput {
    private val factoryDelegate = lazy { newFactory(audioOnly = true) }
    private val videoDelegate = lazy { newFactory(audioOnly = false) }

    private fun newFactory(audioOnly: Boolean): MediaPlayerFactory {
        val resources = checkNotNull(System.getProperty("compose.application.resources.dir")) {
            "Папка аудиодвижка AmorX не найдена."
        }
        val vlc = File(resources, "vlc")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val libraries = if (windows) vlc else File(vlc, "lib")
        val binary = File(libraries, if (windows) "libvlc.dll" else "libvlc.dylib")
        check(binary.isFile) { "В сборке AmorX отсутствует аудиодвижок." }
        NativeLibrary.getInstance(binary.absolutePath)
        NativeLibrary.addSearchPath("vlc", libraries.absolutePath)
        if (!windows) {
            val setenv = NativeLibrary.getProcess().getFunction("setenv")
            check(setenv.invokeInt(arrayOf("VLC_PLUGIN_PATH", File(vlc, "plugins").absolutePath, 1)) == 0)
        }
        val options = mutableListOf("--ignore-config", "--no-media-library", "--no-plugins-cache")
        if (audioOnly) options.add("--no-video")
        if (System.getenv("AMORX_SMOKE_AUDIO_DUMMY") == "1") options.add("--aout=dummy")
        return MediaPlayerFactory(null as NativeDiscovery?, options)
    }
    private val playerDelegate = lazy { factory.mediaPlayers().newMediaPlayer() }

    private val factory by factoryDelegate
    private val player by playerDelegate

    override fun play(url: String) {
        player.audio().setVolume(SettingsStore.current.value.volume)
        val file = File(url)
        val media = if (file.isFile) file.toPath().toUri().toASCIIString() else url
        check(player.media().play(media)) { "Не удалось открыть аудио." }
    }

    override fun stop() {
        if (playerDelegate.isInitialized()) player.controls().stop()
    }

    override fun setPause(pause: Boolean) { player.controls().setPause(pause) }
    override fun seek(ms: Long) { player.controls().setTime(ms) }
    fun timeMs(): Long = player.status().time()
    internal fun videoFactory(): MediaPlayerFactory = videoDelegate.value

    internal fun newAudioPlayer(): MediaPlayer = factory.mediaPlayers().newMediaPlayer()

    private val volumeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { action ->
        Thread(action, "amorx-volume").apply { isDaemon = true }
    }
    @Volatile private var requestedVolume = 75
    fun setVolume(volume: Int) {
        requestedVolume = volume.coerceIn(0, 100)
        volumeExecutor.execute {
            if (playerDelegate.isInitialized()) runCatching { player.audio().setVolume(requestedVolume) }
        }
    }
    fun lengthMs(): Long = player.status().length()
    override fun snapshot(): AudioSnapshot {
        val status = player.status()
        val phase = when (status.state()) {
            State.PLAYING -> AudioPhase.Playing
            State.PAUSED -> AudioPhase.Paused
            State.ENDED -> AudioPhase.Ended
            State.STOPPED -> AudioPhase.Stopped
            State.ERROR -> AudioPhase.Failed
            else -> AudioPhase.Starting
        }
        return AudioSnapshot(phase, status.time(), status.length())
    }

    fun release() {
        if (playerDelegate.isInitialized()) runCatching { player.release() }
        if (factoryDelegate.isInitialized()) runCatching { factory.release() }
        if (videoDelegate.isInitialized()) runCatching { videoDelegate.value.release() }
    }
}
