package chromahub.rhythm.app.infrastructure.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only tap of the active decoder's PCM. Never captures the microphone or alters samples. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object DesignerSpectrum {
    private val _visible = MutableStateFlow(false)
    val visible = _visible.asStateFlow()
    @Volatile var levels = FloatArray(7); private set
    @Volatile var updatedNanos = 0L; private set
    @Volatile var energy = 0f; private set
    @Volatile var bass = 0f; private set
    fun show(value: Boolean) { _visible.value = value; if (!value) { levels = FloatArray(7); energy = 0f; bass = 0f; updatedNanos = 0 } }
    fun feed(buffer: ByteBuffer, format: Format) {
        if (!_visible.value || format.channelCount < 1 || format.sampleRate < 1) return
        val now = System.nanoTime()
        if (now - updatedNanos < 33_000_000L) return
        val bytes = when(format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1; C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4; else -> return
        }
        val frames = min(1024, buffer.remaining() / (bytes * format.channelCount))
        if (frames < 128) return
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(frames)
        for (i in samples.indices) {
            samples[i] = when(format.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) / 128f
                C.ENCODING_PCM_16BIT -> input.short / 32768f
                C.ENCODING_PCM_24BIT -> ((input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or (input.get().toInt() shl 16)) / 8388608f
                C.ENCODING_PCM_32BIT -> input.int / 2147483648f
                else -> input.float.takeIf { it.isFinite() }?.coerceIn(-1f,1f) ?: 0f
            }
            input.position(input.position() + bytes * (format.channelCount - 1))
        }
        val frame = DesignerSpectrumAnalysis.analyze(samples, format.sampleRate)
        levels = frame.bands; energy = frame.energy; bass = frame.bass; updatedNanos = now
    }
    internal fun analyze(samples: FloatArray, rate: Int): FloatArray {
        return DesignerSpectrumAnalysis.analyze(samples,rate).bands
    }
}
