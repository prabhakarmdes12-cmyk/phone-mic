package com.chiti.phonemic.audio

import kotlin.math.*

/**
 * Allocation-free speech shaping with smoothed gain/tone, adaptive howling
 * suppression and a permanent -0.5 dBFS ceiling.
 */
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

    // ── Adaptive feedback suppression (QA-02): zero-crossing howl detector + notch ──
    private var feedbackSuppressionEnabled = true
    var isFeedbackActive = false
        private set
    private var feedbackHoldFrames = 0
    private var prevSample = 0f

    // Biquad notch filter coefficients & delay registers (transposed direct form II)
    private var notchB0 = 1f
    private var notchB1 = 0f
    private var notchB2 = 0f
    private var notchA1 = 0f
    private var notchA2 = 0f
    private var notchZ1 = 0f
    private var notchZ2 = 0f

    // Bumpless-deployment history: settles the notch state on recent signal so
    // engaging the filter never emits a start-up transient into the output.
    private val notchHistory = FloatArray(256)
    private var notchHistoryWrite = 0
    private var notchHistoryCount = 0

    fun setFeedbackSuppression(enabled: Boolean) {
        feedbackSuppressionEnabled = enabled
        if (!enabled) {
            isFeedbackActive = false
            feedbackHoldFrames = 0
            resetNotch()
        }
    }

    private fun resetNotch() {
        notchZ1 = 0f
        notchZ2 = 0f
        notchB0 = 1f; notchB1 = 0f; notchB2 = 0f
        notchA1 = 0f; notchA2 = 0f
    }

    private fun configureNotch(sampleRate: Float, notchHz: Float, q: Float = 8.0f) {
        if (sampleRate <= 0f || notchHz <= 0f || q <= 0f) return
        val omega = 2.0 * PI * notchHz / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val cosw = cos(omega)
        val a0 = 1.0 + alpha
        if (abs(a0) < 1e-6) return

        notchB0 = (1.0 / a0).toFloat()
        notchB1 = (-2.0 * cosw / a0).toFloat()
        notchB2 = (1.0 / a0).toFloat()
        notchA1 = (-2.0 * cosw / a0).toFloat()
        notchA2 = ((1.0 - alpha) / a0).toFloat()
        notchZ1 = 0f
        notchZ2 = 0f

        // Prime the freshly configured notch over recent pre-notch samples so the
        // howl is cancelled from the first output sample (no filter warm-up burst).
        val n = notchHistoryCount
        if (n > 0) {
            val start = (notchHistoryWrite - n + notchHistory.size) % notchHistory.size
            for (k in 0 until n) {
                val x = notchHistory[(start + k) % notchHistory.size]
                val out = notchB0 * x + notchZ1
                notchZ1 = notchB1 * x - notchA1 * out + notchZ2
                notchZ2 = notchB2 * x - notchA2 * out
            }
        }
    }

    private fun processNotch(input: Float): Float {
        val out = notchB0 * input + notchZ1
        notchZ1 = notchB1 * input - notchA1 * out + notchZ2
        notchZ2 = notchB2 * input - notchA2 * out
        return out
    }

    fun process(samples: ShortArray, count: Int, targetGain: Float, preset: Int) {
        var sum = 0f
        var peak = 0f
        var zeroCrossings = 0
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

            // Keep a short history of the pre-notch signal for bumpless notch deployment.
            notchHistory[notchHistoryWrite] = yProcessed
            notchHistoryWrite = (notchHistoryWrite + 1) % notchHistory.size
            if (notchHistoryCount < notchHistory.size) notchHistoryCount++

            // Apply notch filter if feedback suppression is enabled and hold timer is active
            if (feedbackSuppressionEnabled && feedbackHoldFrames > 0) {
                yProcessed = processNotch(yProcessed)
            }

            val total = yProcessed + soundboard.nextSfx() + soundboard.nextBgmWithVoice(yProcessed)
            y = total.coerceIn(-.944f, .944f)
            samples[i] = (y * 32767f).toInt().toShort()
            // Zero-crossing counter for the howling frequency estimator
            if ((prevSample < 0f && y >= 0f) || (prevSample >= 0f && y < 0f)) {
                zeroCrossings++
            }
            prevSample = y
            sum += y * y; peak = max(peak, abs(y))
        }
        rmsDb = if (sum > 1e-10f) 10f * log10(sum / count) else -96f
        peakDb = if (peak > 1e-5f) 20f * log10(peak) else -96f

        // Adaptive howling detector (matches native-audio-engine.cpp processDsp)
        if (feedbackSuppressionEnabled) {
            if (feedbackHoldFrames > 0) {
                feedbackHoldFrames -= count
                if (feedbackHoldFrames <= 0) {
                    isFeedbackActive = false
                    resetNotch()
                }
            } else if (peak > 0.65f && rmsDb > -7.0f && count > 0) { // rms > 0.45 linear
                val estFreq = (zeroCrossings * rate) / (2.0f * count)
                if (estFreq in 500f..7500f) {
                    configureNotch(rate, estFreq, 8.0f)
                    feedbackHoldFrames = (rate * 1.5f).toInt() // 1.5s hold
                    isFeedbackActive = true
                }
            }
        }
    }
}
