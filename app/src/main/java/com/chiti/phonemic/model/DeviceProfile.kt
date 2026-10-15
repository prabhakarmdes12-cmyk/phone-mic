package com.chiti.phonemic.model

/**
 * Persisted profile storing calibrated latency and settings for a specific speaker.
 */
data class DeviceProfile(
    val deviceAddress: String,
    val deviceName: String,
    val audioType: AudioDeviceType,
    val measuredLatencyMs: Int,
    val preferredGainDb: Float = 0.0f,
    val preferredPresetId: Int = 0,
    val optimalBufferFrames: Int = 192,
    val lastConnectedTimestamp: Long = System.currentTimeMillis()
)

enum class AudioDeviceType {
    BLUETOOTH_A2DP,
    BLUETOOTH_LE,
    WIRED_HEADSET,
    USB_AUDIO,
    BUILTIN_SPEAKER
}
