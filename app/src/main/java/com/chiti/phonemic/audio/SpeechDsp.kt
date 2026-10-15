package com.chiti.phonemic.audio

import kotlin.math.*

/** Allocation-free speech shaping with smoothed gain/tone and a permanent -0.5 dBFS ceiling. */
class SpeechDsp {
    private var previous = 0f
    private var highPass = 0f
    private var low = 0f
    private var gain = 0f
    private var presence = 0f
    var rmsDb = -96f; private set
    var peakDb = -96f; private set
    fun process(samples: ShortArray, count: Int, targetGain: Float, preset: Int) {
        var sum = 0f
        var peak = 0f
        val targetPresence = when (preset) { 1 -> .45f; 2 -> .7f; 3 -> -.25f; 4 -> .55f; 5 -> .2f; else -> 0f }
        val pole = if (preset == 4 || preset == 2) .982f else .990f
        for (i in 0 until count) {
            gain += .002f * (targetGain - gain)
            presence += .002f * (targetPresence - presence)
            val x = samples[i] / 32768f
            highPass = x - previous + pole * highPass; previous = x
            low += .28f * (highPass - low)
            var y = (highPass + presence * (highPass - low)) * gain
            if (preset == 2) y = tanh(y * 1.6f)
            y = y.coerceIn(-.944f, .944f)
            samples[i] = (y * 32767f).toInt().toShort()
            sum += y * y; peak = max(peak, abs(y))
        }
        rmsDb = if (sum > 1e-10f) 10f * log10(sum / count) else -96f
        peakDb = if (peak > 1e-5f) 20f * log10(peak) else -96f
    }
}
