package com.chiti.phonemic.audio

import kotlin.math.*

/** Single render-thread voice, retrigger replaces old voice. No buffers or objects created by next(). */
class SoundboardPlayer(private val sampleRate: Int = 48000) {
    @Volatile private var request = 0
    private var consumed = 0
    private var kind = 0
    private var frame = Int.MAX_VALUE
    private var noise = 1234567

    @Volatile private var bgmTrack = 0
    @Volatile private var bgmVolume = 0.35f
    private var bgmFrame = 0L

    fun trigger(effect: Int) { request = ((request + 4) and -4) or effect.coerceIn(1, 3) }

    fun setBgmTrack(trackId: Int) {
        bgmTrack = trackId
        if (trackId == 0) bgmFrame = 0L
    }

    fun setBgmVolume(volume: Float) {
        bgmVolume = volume.coerceIn(0f, 1f)
    }

    fun currentBgmTrack(): Int = bgmTrack
    fun currentBgmVolume(): Float = bgmVolume

    fun nextSfx(): Float {
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

    fun nextBgm(): Float {
        val track = bgmTrack
        if (track <= 0) return 0f
        val vol = bgmVolume
        val t = bgmFrame++.toDouble() / sampleRate
        val sample: Double = when (track) {
            1 -> {
                val loopT = t % 12.0
                val chordIdx = (loopT / 3.0).toInt()
                val chordPhase = loopT % 3.0
                val chordEnv = exp(-1.1 * chordPhase) + 0.18 * exp(-1.5 * (chordPhase % 1.5))
                val (f0, f1, f2, f3) = when (chordIdx) {
                    1 -> listOf(87.31, 110.0, 130.81, 164.81) // Fmaj7
                    2 -> listOf(130.81, 164.81, 196.0, 246.94) // Cmaj
                    3 -> listOf(98.0, 123.47, 146.83, 174.61) // G7
                    else -> listOf(110.0, 130.81, 164.81, 196.0) // Am7
                }
                val chord = sin(2 * PI * f0 * t) * 0.4 + sin(2 * PI * f1 * t) * 0.3 + sin(2 * PI * f2 * t) * 0.25 + sin(2 * PI * f3 * t) * 0.2
                val beatT = chordPhase % 0.75
                val kick = sin(2 * PI * (75.0 - 40.0 * beatT) * beatT) * exp(-14.0 * beatT)
                chord * chordEnv * 0.35 + kick * 0.12
            }
            2 -> {
                val loopT = t % 8.72
                val chordIdx = (loopT / 2.18).toInt()
                val chordPhase = loopT % 2.18
                val strumPhase = chordPhase % 0.2727
                val strumEnv = exp(-6.0 * strumPhase)
                val (f0, f1, f2) = when (chordIdx) {
                    1 -> listOf(98.0, 123.47, 146.83) // G
                    2 -> listOf(110.0, 130.81, 164.81) // Am
                    3 -> listOf(87.31, 110.0, 130.81) // F
                    else -> listOf(130.81, 164.81, 196.0) // C
                }
                val strum = (sin(2 * PI * f0 * t) + sin(2 * PI * f1 * t) + sin(2 * PI * f2 * t)) * 0.33
                val beatT = chordPhase % 0.545
                val kick = sin(2 * PI * 65.0 * beatT) * exp(-18.0 * beatT)
                strum * strumEnv * 0.40 + kick * 0.15
            }
            3 -> {
                val sweep = 1.0 + 0.008 * sin(0.5 * t)
                val pad = sin(2 * PI * 130.81 * sweep * t) * 0.35 +
                          sin(2 * PI * 196.00 * t) * 0.30 +
                          sin(2 * PI * 329.63 * t) * 0.25 +
                          sin(2 * PI * 392.00 * sweep * t) * 0.18
                pad * 0.45
            }
            4 -> {
                val beatT = t % 0.666
                val kick = sin(2 * PI * (80.0 - 45.0 * beatT) * beatT) * exp(-12.0 * beatT)
                val hatT = t % 0.166
                noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                val hat = (noise.toDouble() / Int.MAX_VALUE) * exp(-45.0 * hatT) * 0.08
                kick * 0.28 + hat
            }
            else -> 0.0
        }
        return (sample * vol).toFloat()
    }

    fun next(): Float {
        return nextSfx() + nextBgm()
    }
}
