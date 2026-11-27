package chromahub.rhythm.app.util

import chromahub.rhythm.app.infrastructure.audio.DesignerSpectrumAnalysis
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DesignerSpectrumAnalysisTest {
    private fun tone(hz: Double, amplitude: Float = .1f) = FloatArray(1024) {
        (sin(2*PI*hz*it/48000)*amplitude).toFloat()
    }

    @Test fun notesBetweenTheOldFixedFrequenciesStillDriveTheSpectrum() {
        val frame = DesignerSpectrumAnalysis.analyze(tone(1450.0),48000)
        assertEquals(3,frame.bands.indices.maxBy { frame.bands[it] })
        assertTrue(frame.bands[3]>.4f)
        assertTrue(frame.bands[3]>frame.bands[0]+.2f)
    }

    @Test fun bassAndHighNotesAffectDifferentBands() {
        val low = DesignerSpectrumAnalysis.analyze(tone(160.0),48000)
        val high = DesignerSpectrumAnalysis.analyze(tone(6500.0),48000)
        assertTrue(low.bass>.3f)
        assertTrue(high.bass<.1f)
        assertEquals(6,high.bands.indices.maxBy { high.bands[it] })
    }

    @Test fun quietMusicIsVisibleAndLouderMusicHasMoreEnergy() {
        val quiet = DesignerSpectrumAnalysis.analyze(tone(1000.0,.004f),48000)
        val loud = DesignerSpectrumAnalysis.analyze(tone(1000.0,.25f),48000)
        assertTrue(quiet.energy>0f)
        assertTrue(quiet.bands.max()>.1f)
        assertTrue(loud.energy>quiet.energy)
        assertTrue(loud.bands.max()>quiet.bands.max())
    }

    @Test fun silenceDcAndInvalidInputCannotMakeAFakeBeat() {
        for(samples in listOf(FloatArray(1024),FloatArray(1024){.3f},FloatArray(1024){Float.NaN},FloatArray(0))) {
            val frame = DesignerSpectrumAnalysis.analyze(samples,48000)
            assertEquals(0f,frame.energy,0f)
            assertTrue(frame.bands.all { it==0f })
        }
        assertEquals(0f,DesignerSpectrumAnalysis.analyze(tone(1000.0),0).energy,0f)
    }

    @Test fun analyzingAudioDoesNotModifyTheInputSamples() {
        val samples = tone(1000.0)
        val copy = samples.copyOf()
        DesignerSpectrumAnalysis.analyze(samples,48000)
        assertArrayEquals(copy,samples,0f)
    }
}
