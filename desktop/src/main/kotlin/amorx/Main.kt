package amorx

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size == 2 && args.first() == "--verify-audio") {
        try {
            Engine.play(args[1])
            repeat(60) {
                if (Engine.timeMs() >= 250L) {
                    val elapsed = Engine.timeMs()
                    Engine.release()
                    println("Audio playback verified at ${elapsed} ms")
                    exitProcess(0)
                }
                Thread.sleep(100)
            }
            error("Packaged audio engine did not begin playback")
        } catch (error: Throwable) {
            error.printStackTrace()
            exitProcess(1)
        }
    }
    application {
        val state = rememberWindowState(placement = WindowPlacement.Maximized, size = DpSize(1100.dp, 720.dp))
        fun toggleFullscreen() {
            state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Maximized
                else WindowPlacement.Fullscreen
        }
        Window(
            onCloseRequest = { Engine.release(); exitApplication() },
            state = state, title = "AmorX",
            onPreviewKeyEvent = {
                if (it.type != KeyEventType.KeyUp) false
                else when (it.key) {
                    Key.F11 -> { toggleFullscreen(); true }
                    Key.Escape -> if (state.placement == WindowPlacement.Fullscreen) {
                        toggleFullscreen()
                        true
                    } else false
                    else -> false
                }
            }
        ) {
            window.minimumSize = Dimension(480, 380)
            App({ chooseAudio(window) }, ::toggleFullscreen)
        }
    }
}

fun chooseAudio(owner: Frame? = null): File? {
    val dialog = FileDialog(owner, "Открыть аудиофайл", FileDialog.LOAD)
    return try {
        dialog.isVisible = true
        dialog.files.firstOrNull()
    } finally {
        dialog.dispose()
    }
}
