package com.chiti.phonemic.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Common abstraction contract for the PhoneMic audio engine.
 */
interface AudioEngine {
    val audioState: StateFlow<AudioState>
    val currentRmsDb: StateFlow<Float>
    val currentPeakDb: StateFlow<Float>
    val isFeedbackActive: StateFlow<Boolean>

    suspend fun start(outputDeviceName: String, initialGainDb: Float = 0.0f): Boolean
    fun stop()
    fun setInputGain(gainDb: Float)
    fun setMuted(muted: Boolean, reason: MuteReason = MuteReason.USER_MANUAL)
    fun setVoicePreset(presetId: Int)
    fun setFeedbackSuppression(enabled: Boolean)
    fun getUnderrunCount(): Long
    fun setBgmTrack(trackId: Int) {}
    fun setBgmVolume(volume: Float) {}
    fun setAutoDucking(enabled: Boolean) {}
}
