package chromahub.rhythm.app.util

import chromahub.rhythm.app.infrastructure.audio.DesignerAudioFrame
import chromahub.rhythm.app.infrastructure.audio.DesignerBlobMotion
import chromahub.rhythm.app.infrastructure.audio.DesignerSpectrumAnalysis
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DesignerBlobMotionTest {
    private val music = DesignerAudioFrame(FloatArray(7){.8f},.7f,.6f)

    @Test fun silentAudioNeverAdvancesTheShapeEvenWhilePlaybackContinues() {
        val silence=DesignerSpectrumAnalysis.analyze(FloatArray(1024),48000)
        var phase=1.25f
        repeat(600) { phase+=DesignerBlobMotion.phaseStep(silence,1f/60,true) }
        assertEquals(1.25f,phase,0f)
        assertTrue(DesignerBlobMotion.phaseStep(music,1f/60,true)>0f)
    }

    @Test fun pauseBothMuteControlsAndExpiredBuffersRejectTheAudioFrame() {
        assertNotNull(DesignerBlobMotion.audibleFrame(music,true,true,true,0))
        assertNull(DesignerBlobMotion.audibleFrame(music,false,true,true,0))
        assertNull(DesignerBlobMotion.audibleFrame(music,true,false,true,0))
        assertNull(DesignerBlobMotion.audibleFrame(music,true,true,false,0))
        assertNull(DesignerBlobMotion.audibleFrame(music,true,true,true,250_000_000))
        assertEquals(0f,DesignerBlobMotion.phaseStep(null,.016f,true),0f)
    }

    @Test fun aMissingSignalSettlesToExactlyZeroInsteadOfMovingForever() {
        var level=1f
        var phase=2f
        repeat(120) {
            level=DesignerBlobMotion.settle(level,0f,1f/60,28f,8f)
            phase+=DesignerBlobMotion.phaseStep(null,1f/60,true)
        }
        assertEquals(0f,level,0f)
        assertEquals(2f,phase,0f)
    }

    @Test fun digitalNoiseIsSilentButQuietMusicStillMoves() {
        fun tone(amplitude:Float)=FloatArray(1024){ (sin(2*PI*1000*it/48000)*amplitude).toFloat() }
        val noise=DesignerSpectrumAnalysis.analyze(tone(.00005f),48000)
        val quiet=DesignerSpectrumAnalysis.analyze(tone(.004f),48000)
        assertEquals(0f,DesignerBlobMotion.phaseStep(noise,.016f,true),0f)
        assertTrue(DesignerBlobMotion.phaseStep(quiet,.016f,true)>0f)
        assertEquals(0f,DesignerBlobMotion.phaseStep(quiet,.016f,false),0f)
    }
}
