package com.chiti.phonemic.audio

import kotlin.math.*

/** Allocation-free speech shaping with smoothed gain/tone and a permanent -0.5 dBFS ceiling. */
class SpeechDsp(val soundboard: SoundboardPlayer = SoundboardPlayer()) {
    private var previous = 0f
    private var highPass = 0f
    private var low = 0f
    private var gain = 0f
    private var presence = 0f
    private val delayBuffer = FloatArray(16384)
    private var delayWriteIndex = 0
    private var reverbFeedbackLp = 0f
    private var lfoPhase = 0f
    private var robotPhase = 0f

    var rmsDb = -96f; private set
    var peakDb = -96f; private set

    fun process(samples: ShortArray, count: Int, targetGain: Float, preset: Int) {
        var sum = 0f
        var peak = 0f
        val targetPresence = when (preset) {
            1 -> .45f // Clear
            2 -> .70f // Loud
            3 -> -.25f // Warm
            4 -> .55f // Outdoor
            5 -> .35f // Karaoke
            6 -> .30f // Studio Vocal
            7 -> .20f // Vocal Chorus
            8 -> .10f // Robot
            else -> 0f
        }
        val pole = when (preset) {
            4, 2 -> .982f
            5, 6 -> .993f // Open bottom-end for full vocal singing
            else -> .990f
        }
        val rate = 48000f

        for (i in 0 until count) {
            gain += .002f * (targetGain - gain)
            presence += .002f * (targetPresence - presence)
            val x = samples[i] / 32768f
            highPass = x - previous + pole * highPass; previous = x
            low += .28f * (highPass - low)
            var y = (highPass + presence * (highPass - low)) * gain
            if (preset == 2) y = tanh(y * 1.6f)

            // Singing & Voice Modulation
            var yProcessed = y
            val bufSize = delayBuffer.size
            if (preset == 5) { // Karaoke Reverb & Echo
                val delaySamples = (rate * 0.160f).toInt()
                val readIndex = (delayWriteIndex + bufSize - delaySamples) % bufSize
                val delayed = delayBuffer[readIndex]
                val tap2Index = (delayWriteIndex + bufSize - (rate * 0.060f).toInt()) % bufSize
                val tap2 = delayBuffer[tap2Index]

                reverbFeedbackLp = reverbFeedbackLp * 0.6f + delayed * 0.4f
                delayBuffer[delayWriteIndex] = y + reverbFeedbackLp * 0.38f
                yProcessed = y * 0.85f + (delayed * 0.35f + tap2 * 0.20f)
            } else if (preset == 6) { // Studio Vocal
                val delaySamples = (rate * 0.045f).toInt()
                val readIndex = (delayWriteIndex + bufSize - delaySamples) % bufSize
                val delayed = delayBuffer[readIndex]

                reverbFeedbackLp = reverbFeedbackLp * 0.7f + delayed * 0.3f
                delayBuffer[delayWriteIndex] = y + reverbFeedbackLp * 0.25f
                yProcessed = y * 0.92f + delayed * 0.28f
            } else if (preset == 7) { // Vocal Chorus Modulation
                lfoPhase += (2.0 * PI * 1.2 / rate).toFloat()
                if (lfoPhase > 2.0 * PI) lfoPhase -= (2.0 * PI).toFloat()
                val modMs = 0.019f + 0.005f * sin(lfoPhase)
                val delaySamples = (rate * modMs).toInt()
                val readIndex = (delayWriteIndex + bufSize - delaySamples) % bufSize
                val delayed = delayBuffer[readIndex]

                delayBuffer[delayWriteIndex] = y
                yProcessed = y * 0.65f + delayed * 0.55f
            } else if (preset == 8) { // Robot Mod
                robotPhase += (2.0 * PI * 140.0 / rate).toFloat()
                if (robotPhase > 2.0 * PI) robotPhase -= (2.0 * PI).toFloat()
                yProcessed = y * 0.35f + (y * sin(robotPhase)) * 0.65f
                delayBuffer[delayWriteIndex] = 0f
            } else {
                delayBuffer[delayWriteIndex] = 0f
            }
            delayWriteIndex = (delayWriteIndex + 1) % bufSize

            val total = yProcessed + soundboard.next()
            y = total.coerceIn(-.944f, .944f)
            samples[i] = (y * 32767f).toInt().toShort()
            sum += y * y; peak = max(peak, abs(y))
        }
        rmsDb = if (sum > 1e-10f) 10f * log10(sum / count) else -96f
        peakDb = if (peak > 1e-5f) 20f * log10(peak) else -96f
    }
}
