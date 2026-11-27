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
    fun show(value: Boolean) { _visible.value = value; if (!value) { levels = FloatArray(7); updatedNanos = 0 } }
    fun feed(buffer: ByteBuffer, format: Format) {
        if (!_visible.value || format.channelCount < 1 || format.sampleRate < 1) return
        val now = System.nanoTime()
        if (now - updatedNanos < 33_000_000L) return
        val bytes = when(format.pcmEncoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_FLOAT -> 4; else -> return }
        val frames = min(1024, buffer.remaining() / (bytes * format.channelCount))
        if (frames < 128) return
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(frames)
        for (i in samples.indices) {
            samples[i] = if(bytes == 2) input.short / 32768f else input.float.coerceIn(-1f, 1f)
            input.position(input.position() + bytes * (format.channelCount - 1))
        }
        levels = analyze(samples, format.sampleRate); updatedNanos = now
    }
    internal fun analyze(samples: FloatArray, rate: Int): FloatArray {
        val frequencies = doubleArrayOf(125.0,250.0,500.0,1000.0,2000.0,4000.0,8000.0)
        val windowed = FloatArray(samples.size) { i ->
            (samples[i] * (.5 - .5 * cos(2 * PI * i / max(1, samples.lastIndex)))).toFloat()
        }
        return FloatArray(7) { band ->
            val frequency = frequencies[band]
            if (frequency >= rate * .48 || samples.isEmpty()) 0f else {
                val coefficient = 2 * cos(2 * PI * frequency / rate)
                var a = 0.0; var b = 0.0
                for (sample in windowed) {
                    val next = sample + coefficient * a - b; b = a; a = next
                }
                val magnitude = sqrt(max(0.0, a*a + b*b - coefficient*a*b)) * 4 / samples.size
                ((20 * log10(max(1e-6, magnitude)) + 54) / 54).toFloat().coerceIn(0f, 1f)
            }
        }
    }
}
