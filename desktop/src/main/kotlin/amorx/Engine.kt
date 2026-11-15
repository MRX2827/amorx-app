package amorx

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File

/** Plays local files and streams through the engine shipped with AmorX. */
object Engine {
    private val factoryDelegate = lazy {
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
        val options = mutableListOf("--no-video", "--ignore-config", "--no-media-library", "--no-plugins-cache")
        if (System.getenv("AMORX_SMOKE_AUDIO_DUMMY") == "1") options.add("--aout=dummy")
        MediaPlayerFactory(null as NativeDiscovery?, options)
    }
    private val playerDelegate = lazy {
        factory.mediaPlayers().newMediaPlayer().also {
            it.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    onFinished?.invoke()
                }
            })
        }
    }

    private val factory by factoryDelegate
    private val player by playerDelegate

    @Volatile var onFinished: (() -> Unit)? = null

    fun play(url: String) {
        player.audio().setVolume(SettingsStore.current.value.volume)
        val file = File(url)
        val media = if (file.isFile) file.toPath().toUri().toASCIIString() else url
        check(player.media().play(media)) { "Не удалось открыть аудио." }
    }

    fun setPause(pause: Boolean) { player.controls().setPause(pause) }
    fun seek(ms: Long) { player.controls().setTime(ms) }
    fun timeMs(): Long = player.status().time()
    fun setVolume(volume: Int) {
        if (playerDelegate.isInitialized()) player.audio().setVolume(volume)
    }
    fun lengthMs(): Long = player.status().length()

    fun release() {
        if (playerDelegate.isInitialized()) runCatching { player.release() }
        if (factoryDelegate.isInitialized()) runCatching { factory.release() }
    }
}
