package chromahub.rhythm.app.infrastructure.audio

import kotlin.math.*

internal data class DesignerAudioFrame(val bands: FloatArray, val energy: Float, val bass: Float)

/** Wide frequency bands, rather than seven narrow tones which miss most musical notes. */
internal object DesignerSpectrumAnalysis {
    fun analyze(samples: FloatArray, rate: Int): DesignerAudioFrame {
        fun silence() = DesignerAudioFrame(FloatArray(7),0f,0f)
        val size = Integer.highestOneBit(min(samples.size,1024))
        if(size < 16 || rate <= 0) return silence()
        val real = FloatArray(size) { samples[it].takeIf { s -> s.isFinite() }?.coerceIn(-1f,1f) ?: 0f }
        val imaginary = FloatArray(size)
        val mean = real.sumOf { it.toDouble() } / size
        var power = 0.0
        var windowSum = 0.0
        for(i in real.indices) {
            val centered = real[i]-mean
            power += centered*centered
            val window = .5-.5*cos(2*PI*i/(size-1))
            windowSum += window
            real[i] = (centered*window).toFloat()
        }
        val rms = sqrt(power/size)
        // Ignore digital dither / a practically silent signal before applying visual gain.
        if(rms < 1e-4) return silence()

        var reversed = 0
        for(i in 1 until size) {
            var bit = size shr 1
            while(reversed and bit != 0) { reversed = reversed xor bit; bit = bit shr 1 }
            reversed = reversed xor bit
            if(i < reversed) {
                val temp = real[i]; real[i] = real[reversed]; real[reversed] = temp
            }
        }
        var length = 2
        while(length <= size) {
            val angle = -2*PI/length
            val stepReal = cos(angle).toFloat(); val stepImaginary = sin(angle).toFloat()
            for(start in 0 until size step length) {
                var rotationReal = 1f; var rotationImaginary = 0f
                for(offset in 0 until length/2) {
                    val first = start+offset; val second = first+length/2
                    val nextReal = real[second]*rotationReal-imaginary[second]*rotationImaginary
                    val nextImaginary = real[second]*rotationImaginary+imaginary[second]*rotationReal
                    real[second] = real[first]-nextReal; imaginary[second] = imaginary[first]-nextImaginary
                    real[first] += nextReal; imaginary[first] += nextImaginary
                    val rotated = rotationReal*stepReal-rotationImaginary*stepImaginary
                    rotationImaginary = rotationReal*stepImaginary+rotationImaginary*stepReal
                    rotationReal = rotated
                }
            }
            length = length shl 1
        }
        val edges = doubleArrayOf(40.0,180.0,400.0,800.0,1600.0,3200.0,6400.0,16000.0)
        val binHz = rate.toDouble()/size
        val bands = FloatArray(7) { band ->
            val first = ceil(edges[band]/binHz).toInt().coerceAtLeast(1)
            val last = (ceil(min(edges[band+1],rate*.48)/binHz).toInt()-1).coerceAtMost(size/2)
            var sum = 0.0
            if(first <= last) for(bin in first..last) sum += real[bin].toDouble()*real[bin]+imaginary[bin].toDouble()*imaginary[bin]
            val amplitude = sqrt(2*sum)/windowSum
            if(amplitude < 1e-7) 0f else ((20*log10(amplitude)+70)/60).toFloat().coerceIn(0f,1f)
        }
        return DesignerAudioFrame(bands,(sqrt(rms)*1.9).toFloat().coerceIn(0f,1f),
            (bands[0]*.65f+bands[1]*.35f).coerceIn(0f,1f))
    }
}
