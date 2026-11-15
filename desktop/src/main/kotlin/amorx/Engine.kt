package amorx

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
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
        val libraries = if (System.getProperty("os.name").startsWith("Windows")) vlc else File(vlc, "lib")
        check(libraries.isDirectory) { "В сборке AmorX отсутствует аудиодвижок." }
        NativeLibrary.addSearchPath("vlc", libraries.absolutePath)
        MediaPlayerFactory("--no-video", "--ignore-config", "--no-media-library", "--no-plugins-cache")
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
        check(player.media().play(url)) { "Не удалось открыть аудио." }
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
