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
        for (preset in 0..8) {
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
    @Test fun singingPresetsProduceModulationAndReverbTail() {
        // Karaoke (preset 5) produces reverberation tail after an impulse
        val dsp = SpeechDsp()
        val impulse = ShortArray(4800)
        impulse[0] = 30000 // single impulse
        dsp.process(impulse, impulse.size, 1f, 5)
        // Verify non-zero samples exist later in buffer due to reverb delay line
        val tailSamples = impulse.sliceArray(800..4000)
        assertTrue(tailSamples.any { abs(it.toInt()) > 10 })
    }

    @Test
    fun testAdaptiveFeedbackNotchFilterAttenuatesHowlingTone() {
        val dsp = SpeechDsp()
        dsp.setFeedbackSuppression(true)

        // Generate high-amplitude pure resonant tone at 3000 Hz (>0.65 peak, sustained)
        val howl = ShortArray(4800) { (sin(2 * PI * 3000.0 * it / 48000) * 26000).toInt().toShort() }

        // Process first block to trigger detector
        dsp.process(howl, howl.size, 1f, 0)
        assertTrue("Feedback should be detected on sustained high-amplitude pure tone", dsp.isFeedbackActive)

        // Process second block through deployed notch filter
        val secondBlock = ShortArray(4800) { (sin(2 * PI * 3000.0 * it / 48000) * 26000).toInt().toShort() }
        dsp.process(secondBlock, secondBlock.size, 1f, 0)

        // Output amplitude should be heavily attenuated by Q=8 notch
        val maxOutput = secondBlock.maxOf { abs(it.toInt()) }
        assertTrue("Notch filter must attenuate resonant peak (was $maxOutput)", maxOutput < 18000)
    }

    @Test
    fun testFeedbackSuppressionToggleDisablesAndClearsState() {
        val dsp = SpeechDsp()
        dsp.setFeedbackSuppression(false)

        // Disabled suppression must never flag feedback, even on a sustained howl
        val howl = ShortArray(4800) { (sin(2 * PI * 3000.0 * it / 48000) * 26000).toInt().toShort() }
        dsp.process(howl, howl.size, 1f, 0)
        assertFalse("Disabled suppression must not activate", dsp.isFeedbackActive)

        // Enable, detect, then disable again: state flag must clear immediately
        dsp.setFeedbackSuppression(true)
        val second = ShortArray(4800) { (sin(2 * PI * 3000.0 * it / 48000) * 26000).toInt().toShort() }
        dsp.process(second, second.size, 1f, 0)
        assertTrue(dsp.isFeedbackActive)
        dsp.setFeedbackSuppression(false)
        assertFalse("Disabling suppression must clear the active flag", dsp.isFeedbackActive)
    }
}
