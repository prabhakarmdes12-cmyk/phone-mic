package com.chiti.phonemic.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpeechDspTest {
    @Test fun silenceIsFiniteAndSilent() {
        val dsp = SpeechDsp()
        val pcm = ShortArray(240)
        dsp.process(pcm, pcm.size, 1f, 0)
        assertTrue(pcm.all { it == 0.toShort() })
        assertEquals(-96f, dsp.rmsDb, 0f)
    }
    @Test fun everyPresetAlwaysLimitsOutput() {
        for (preset in 0..5) {
            val dsp = SpeechDsp()
            val pcm = ShortArray(4800) { if (it % 2 == 0) 32767 else -32767 }
            dsp.process(pcm, pcm.size, 4f, preset)
            assertTrue(pcm.all { abs(it.toInt()) <= 30932 })
            assertTrue(dsp.rmsDb.isFinite())
        }
    }
    @Test fun clearSpeechChangesFrequencyResponse() {
        fun render(preset: Int): ShortArray {
            val pcm = ShortArray(4800) { (sin(2 * PI * 3000 * it / 48000) * 4000).toInt().toShort() }
            SpeechDsp().process(pcm, pcm.size, 1f, preset)
            return pcm
        }
        assertFalse(render(0).contentEquals(render(1)))
    }
}
