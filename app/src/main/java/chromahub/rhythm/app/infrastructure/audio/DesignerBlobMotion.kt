package chromahub.rhythm.app.infrastructure.audio

import kotlin.math.abs
import kotlin.math.exp

/** No autonomous clock: silent, muted or missing audio cannot advance the shape. */
internal object DesignerBlobMotion {
    fun audibleFrame(frame: DesignerAudioFrame, playing: Boolean, playerAudible: Boolean,
                     systemAudible: Boolean, ageNanos: Long): DesignerAudioFrame? =
        frame.takeIf { playing && playerAudible && systemAudible && ageNanos in 0 until 250_000_000L }

    fun phaseStep(frame: DesignerAudioFrame?, seconds: Float, animate: Boolean): Float {
        if(!animate || frame == null) return 0f
        val energy = frame.energy.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
        val bass = frame.bass.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
        if(energy <= .005f) return 0f
        val elapsed = seconds.takeIf { it.isFinite() }?.coerceIn(0f,.05f) ?: 0f
        return elapsed*(energy*1.8f+bass*1.6f)
    }

    fun settle(previous: Float, target: Float, seconds: Float, attack: Float, release: Float): Float {
        val from = previous.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
        val to = target.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
        val elapsed = seconds.takeIf { it.isFinite() }?.coerceIn(0f,.05f) ?: 0f
        val rate = if(to>from) attack else release
        val value = from+(to-from)*(1-exp(-elapsed*rate))
        return when {
            to==0f && value<.001f -> 0f
            abs(value-to)<.0001f -> to
            else -> value
        }
    }
}
