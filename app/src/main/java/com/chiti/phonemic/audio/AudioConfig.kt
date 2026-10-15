package com.chiti.phonemic.audio

/**
 * Low-latency audio configuration and buffer settings.
 */
data class AudioConfig(
    val sampleRate: Int = 48000,
    val channelCount: Int = 1, // Mono
    val bufferSizeFrames: Int = 192,
    val inputGainDb: Float = 0.0f,
    val isFeedbackProtectionEnabled: Boolean = true,
    val voicePresetId: Int = 0,
    val latencyMode: LatencyMode = LatencyMode.LOWEST_LATENCY
)

enum class LatencyMode {
    LOWEST_LATENCY,
    BALANCED,
    HIGH_STABILITY
}
