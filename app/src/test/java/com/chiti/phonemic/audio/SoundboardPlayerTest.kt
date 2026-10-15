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
}
