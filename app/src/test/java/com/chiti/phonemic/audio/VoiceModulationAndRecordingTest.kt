package com.chiti.phonemic.audio

import com.chiti.phonemic.model.ScenarioProfile
import com.chiti.phonemic.model.VoicePreset
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class VoiceModulationAndRecordingTest {

    @Test
    fun testKaraokeSingingReverbAndDualTapEcho() {
        val dsp = SpeechDsp()
        // Warm up gain smoother
        dsp.process(ShortArray(1500), 1500, 1.0f, VoicePreset.KARAOKE.id)

        // 48000 Hz: 12000 samples
        val samples = ShortArray(12000)
        samples[0] = 30000 // Impulse at t = 0 (gain is now ~0.95+)

        dsp.process(samples, samples.size, 1.0f, VoicePreset.KARAOKE.id)

        // Tap 2 early reflection is at 60ms (2880 samples)
        val tap2Region = samples.sliceArray(2870..2890)
        assertTrue("Karaoke early reflection at 60ms should be non-zero", tap2Region.any { abs(it.toInt()) > 1000 })

        // Tap 1 slapback echo is at 160ms (7680 samples)
        val tap1Region = samples.sliceArray(7670..7690)
        assertTrue("Karaoke slapback echo at 160ms should be non-zero", tap1Region.any { abs(it.toInt()) > 1000 })

        // Reverb decay tail should persist after tap 1
        val reverbTail = samples.sliceArray(8000..10000)
        assertTrue("Reverb tail should persist smoothly", reverbTail.any { abs(it.toInt()) > 100 })
    }

    @Test
    fun testStudioVocalRoomAmbience() {
        val dsp = SpeechDsp()
        // Warm up gain smoother
        dsp.process(ShortArray(1500), 1500, 1.0f, VoicePreset.STUDIO_VOCAL.id)

        val samples = ShortArray(6000)
        samples[0] = 30000 // Impulse at t = 0 (gain is now ~0.95+)

        dsp.process(samples, samples.size, 1.0f, VoicePreset.STUDIO_VOCAL.id)

        // Studio vocal reflection is at 45ms (2160 samples)
        val roomReflection = samples.sliceArray(2150..2170)
        assertTrue("Studio vocal room reflection at 45ms should be present", roomReflection.any { abs(it.toInt()) > 1000 })

        // Studio vocal should have subtle decay, shorter than Karaoke
        val postReflection = samples.sliceArray(5000..5500)
        val maxPostReflection = postReflection.maxOf { abs(it.toInt()) }
        assertTrue("Studio vocal decay should taper off gently", maxPostReflection < 500)
    }

    @Test
    fun testVocalChorusModulationPhaseSweeps() {
        val dsp = SpeechDsp()
        // Feed 500 Hz tone for 0.5s (24000 samples)
        val samples = ShortArray(24000) { i ->
            (sin(2.0 * PI * 500.0 * i / 48000.0) * 15000.0).toInt().toShort()
        }
        val originalCopy = samples.clone()

        dsp.process(samples, samples.size, 1.0f, VoicePreset.CHORUS_MOD.id)

        // After initial delay buffer fill (first ~25ms = 1200 samples), chorus should modulate amplitude & phase
        var differenceCount = 0
        for (i in 2000 until samples.size) {
            if (abs(samples[i] - originalCopy[i]) > 100) {
                differenceCount++
            }
        }
        assertTrue("Chorus modulation must alter waveform across time", differenceCount > 20000)
    }

    @Test
    fun testRobotRingModulationTimbre() {
        val dsp = SpeechDsp()
        // Feed steady 440 Hz A4 tone
        val samples = ShortArray(4800) { i ->
            (sin(2.0 * PI * 440.0 * i / 48000.0) * 12000.0).toInt().toShort()
        }

        dsp.process(samples, samples.size, 1.0f, VoicePreset.ROBOT_MOD.id)

        // Ring modulation creates 140 Hz sidebands (440 - 140 = 300 Hz, 440 + 140 = 580 Hz)
        // Check that output is active, finite, and strictly bounded
        assertTrue("Robot mod output must be active", samples.any { abs(it.toInt()) > 2000 })
        assertTrue("Robot mod output must not exceed ceiling", samples.all { abs(it.toInt()) <= 30932 })
    }

    @Test
    fun testAllSingingAndModulationPresetsRespectLimiterCeiling() {
        val presets = listOf(
            VoicePreset.NATURAL,
            VoicePreset.CLEAR_SPEECH,
            VoicePreset.LOUD_SPEECH,
            VoicePreset.WARM,
            VoicePreset.OUTDOOR,
            VoicePreset.KARAOKE,
            VoicePreset.STUDIO_VOCAL,
            VoicePreset.CHORUS_MOD,
            VoicePreset.ROBOT_MOD
        )

        for (preset in presets) {
            val dsp = SpeechDsp()
            // Feed full-scale square wave with +12 dB digital boost
            val samples = ShortArray(4800) { if (it % 2 == 0) 32767 else -32767 }
            dsp.process(samples, samples.size, 4.0f, preset.id)

            // Strict -0.5 dBFS ceiling: 0.944 * 32767 = 30932
            for (i in samples.indices) {
                val s = abs(samples[i].toInt())
                assertTrue("Preset ${preset.title} exceeded ceiling at sample $i: $s > 30932", s <= 30932)
            }
            assertTrue("RMS for ${preset.title} must be finite", dsp.rmsDb.isFinite() && dsp.rmsDb <= 0f)
            assertTrue("Peak for ${preset.title} must be finite", dsp.peakDb.isFinite() && dsp.peakDb <= 0f)
        }
    }

    @Test
    fun testSingingScenarioProfileDefaults() {
        val karaokeProfile = ScenarioProfile.KARAOKE_BUSKER
        assertEquals("Singing & Karaoke", karaokeProfile.title)
        assertEquals(VoicePreset.KARAOKE, karaokeProfile.recommendedPreset)
        assertEquals(0.0f, karaokeProfile.defaultGainDb, 0.01f)
        assertFalse("Singing profile keeps BGM uninterrupted by default", karaokeProfile.autoDucking)
        assertTrue("Feedback suppression enabled for live singing", karaokeProfile.feedbackSuppression)
    }

    @Test
    fun testWavHeaderFormatAndByteCalculation() {
        val oneSecondPcmBytes = 96000L // 48000 samples * 2 bytes/sample
        val headerBytes = WavHeader.bytes(oneSecondPcmBytes)
        assertEquals(44, headerBytes.size)

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        // Check RIFF chunk
        val riff = ByteArray(4).also { buffer.get(it) }
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))
        assertEquals(96000 + 36, buffer.getInt()) // file size - 8

        // Check WAVE fmt
        val wave = ByteArray(4).also { buffer.get(it) }
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))
        val fmt = ByteArray(4).also { buffer.get(it) }
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))
        assertEquals(16, buffer.getInt()) // PCM subchunk size
        assertEquals(1.toShort(), buffer.getShort()) // AudioFormat = 1 (PCM)
        assertEquals(1.toShort(), buffer.getShort()) // Channels = 1 (Mono)
        assertEquals(48000, buffer.getInt()) // Sample rate = 48000
        assertEquals(96000, buffer.getInt()) // Byte rate = 48000 * 1 * 2 = 96000
        assertEquals(2.toShort(), buffer.getShort()) // Block align = 1 * 2 = 2
        assertEquals(16.toShort(), buffer.getShort()) // Bits per sample = 16

        // Check data subchunk
        val data = ByteArray(4).also { buffer.get(it) }
        assertEquals("data", String(data, Charsets.US_ASCII))
        assertEquals(96000, buffer.getInt()) // data size
    }
}
