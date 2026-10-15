package com.chiti.phonemic.audio

/**
 * Deterministic audio engine lifecycle states.
 */
sealed interface AudioState {
    /** Engine is idle; no audio capture or playback. */
    data object Idle : AudioState

    /** Preparing audio focus, native streams, and permissions. */
    data object Connecting : AudioState

    /** Live microphone audio is being actively captured, processed, and transmitted. */
    data class Active(
        val outputDeviceName: String,
        val estimatedLatencyMs: Int,
        val gainDb: Float,
        val presetId: Int,
        val isPushToTalk: Boolean = false
    ) : AudioState

    /**
     * Processing is suspended (e.g. user toggled mute, Bluetooth disconnected,
     * or incoming call received). Does NOT capture or broadcast.
     */
    data class Muted(
        val reason: MuteReason,
        val previousState: Active? = null
    ) : AudioState

    /** Non-recoverable error condition. */
    data class Error(val message: String, val throwable: Throwable? = null) : AudioState
}

enum class MuteReason {
    USER_MANUAL,
    BLUETOOTH_DISCONNECTED,
    NETWORK_DISCONNECTED,
    INCOMING_PHONE_CALL,
    AUDIO_FOCUS_LOST,
    PUSH_TO_TALK_RELEASED
}
