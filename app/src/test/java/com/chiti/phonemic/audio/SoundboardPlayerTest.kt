package com.chiti.phonemic.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SoundboardPlayerTest {
    @Test fun everyEffectIsFiniteBoundedAndEnds() {
        for (effect in 1..3) {
            val player = SoundboardPlayer(); player.trigger(effect)
            var peak = 0f
            repeat(96000) { val value = player.next(); assertTrue(value.isFinite()); peak = maxOf(peak, abs(value)) }
            assertTrue(peak > 0f && peak <= .24f)
            assertEquals(0f, player.next(), 0f)
        }
    }
    @Test fun mixedMicAndSoundNeverExceedLimiter() {
        val player = SoundboardPlayer(); val dsp = SpeechDsp(player)
        val samples = ShortArray(480) { 32767 }
        player.trigger(3)
        repeat(50) {
            samples.fill(32767); dsp.process(samples, samples.size, 4f, 2)
            assertTrue(samples.all { abs(it.toInt()) <= 30932 })
        }
    }
    @Test fun bgmTracksProduceContinuousSoundAndStopWhenTurnedOff() {
        for (track in 1..4) {
            val player = SoundboardPlayer()
            player.setBgmTrack(track)
            player.setBgmVolume(0.5f)
            assertEquals(track, player.currentBgmTrack())

            var peak = 0f
            repeat(48000) {
                val value = player.nextBgm()
                assertTrue(value.isFinite())
                peak = maxOf(peak, abs(value))
            }
            assertTrue("Track $track peak should be > 0", peak > 0.01f)

            // When turned off, nextBgm is immediately 0
            player.setBgmTrack(0)
            assertEquals(0f, player.nextBgm(), 0f)
        }
    }
    @Test fun bgmPlusSfxPlusLoudMicNeverExceedsBrickwallLimiter() {
        val player = SoundboardPlayer()
        val dsp = SpeechDsp(player)
        player.setBgmTrack(2) // Pop Groove
        player.setBgmVolume(1.0f) // Max BGM
        player.trigger(2) // Applause SFX
        val samples = ShortArray(480) { 32767 } // Extreme mic input
        repeat(100) {
            samples.fill(32767)
            dsp.process(samples, samples.size, 4.0f, 5) // Karaoke singing preset with reverb
            assertTrue(samples.all { abs(it.toInt()) <= 30932 })
        }
    }
    @Test fun autoDuckingAttenuatesBgmWhenVoiceActive() {
        val player = SoundboardPlayer()
        player.setBgmTrack(1)
        player.setBgmVolume(1.0f)
        player.setAutoDucking(true)

        // Run silence for 500 samples
        repeat(500) { player.nextBgmWithVoice(0.0f) }
        val quietLevel = abs(player.nextBgmWithVoice(0.0f))

        // Run loud speech for 2000 samples to trigger ducking attack
        repeat(2000) { player.nextBgmWithVoice(0.5f) }
        val duckedLevel = abs(player.nextBgmWithVoice(0.5f))

        assertTrue("Ducked level ($duckedLevel) should be lower than quiet level ($quietLevel)", duckedLevel <= quietLevel)
    }
}
