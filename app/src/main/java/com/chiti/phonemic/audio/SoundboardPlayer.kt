package com.chiti.phonemic.audio

import kotlin.math.*

/** Single render-thread voice, retrigger replaces old voice. No buffers or objects created by next(). */
class SoundboardPlayer(private val sampleRate: Int = 48000) {
    @Volatile private var request = 0
    private var consumed = 0
    private var kind = 0
    private var frame = Int.MAX_VALUE
    private var noise = 1234567
    fun trigger(effect: Int) { request = ((request + 4) and -4) or effect.coerceIn(1, 3) }
    fun next(): Float {
        val latest = request
        if (latest != consumed) { consumed = latest; kind = latest and 3; frame = 0 }
        if (frame >= sampleRate * 2) return 0f
        val t = frame++.toDouble() / sampleRate
        val edge = min(1.0, t * 200) * min(1.0, (2.0 - t) * 100)
        val y = when (kind) {
            1 -> if (t < .6) sin(2 * PI * 587 * t) * exp(-5 * t)
                 else sin(2 * PI * 880 * (t - .6)) * exp(-5 * (t - .6))
            2 -> {
                noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                (noise.toDouble() / Int.MAX_VALUE) * exp(-1.8 * t) * (.5 + .5 * abs(sin(t * 79)))
            }
            3 -> sin(2 * PI * (650 * t - 180 / (2 * PI * 1.5) * cos(2 * PI * 1.5 * t)))
            else -> 0.0
        }
        return (y * .23 * edge).toFloat()
    }
}
