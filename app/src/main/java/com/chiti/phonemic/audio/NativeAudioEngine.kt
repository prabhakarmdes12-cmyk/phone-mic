package com.chiti.phonemic.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native Oboe/AAudio implementation of [AudioEngine].
 */
class NativeAudioEngine : AudioEngine {

    companion object {
        init {
            System.loadLibrary("phonemic")
        }
    }

    private var nativeHandle: Long = 0
    private val scope = CoroutineScope(Dispatchers.Default)
    private var meterJob: Job? = null

    private val _audioState = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _currentRmsDb = MutableStateFlow(-96.0f)
    override val currentRmsDb: StateFlow<Float> = _currentRmsDb.asStateFlow()

    private val _currentPeakDb = MutableStateFlow(-96.0f)
    override val currentPeakDb: StateFlow<Float> = _currentPeakDb.asStateFlow()

    init {
        nativeHandle = nativeCreate()
    }

    override suspend fun start(outputDeviceName: String, initialGainDb: Float): Boolean {
        if (_audioState.value is AudioState.Active) return true

        _audioState.value = AudioState.Connecting
        setInputGain(initialGainDb)

        val success = nativeStart(nativeHandle)
        if (success) {
            _audioState.value = AudioState.Active(
                outputDeviceName = outputDeviceName,
                estimatedLatencyMs = 126,
                gainDb = initialGainDb,
                presetId = 0
            )
            startMeteringLoop()
            return true
        } else {
            _audioState.value = AudioState.Error("Failed to initialize low-latency audio stream.")
            return false
        }
    }

    override fun stop() {
        meterJob?.cancel()
        meterJob = null
        nativeStop(nativeHandle)
        _audioState.value = AudioState.Idle
        _currentRmsDb.value = -96.0f
        _currentPeakDb.value = -96.0f
    }

    override fun setInputGain(gainDb: Float) {
        nativeSetInputGain(nativeHandle, gainDb)
        val current = _audioState.value
        if (current is AudioState.Active) {
            _audioState.value = current.copy(gainDb = gainDb)
        }
    }

    override fun setMuted(muted: Boolean, reason: MuteReason) {
        nativeSetMuted(nativeHandle, muted)
        val current = _audioState.value
        if (muted && current is AudioState.Active) {
            _audioState.value = AudioState.Muted(reason = reason, previousState = current)
        } else if (!muted && current is AudioState.Muted && current.previousState != null) {
            _audioState.value = current.previousState
        }
    }

    override fun setVoicePreset(presetId: Int) {
        nativeSetVoicePreset(nativeHandle, presetId)
        val current = _audioState.value
        if (current is AudioState.Active) {
            _audioState.value = current.copy(presetId = presetId)
        }
    }

    override fun setFeedbackSuppression(enabled: Boolean) {
        nativeSetFeedbackSuppression(nativeHandle, enabled)
    }

    override fun getUnderrunCount(): Long {
        return nativeGetUnderruns(nativeHandle)
    }

    private fun startMeteringLoop() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive && _audioState.value is AudioState.Active) {
                _currentRmsDb.value = nativeGetRmsDb(nativeHandle)
                _currentPeakDb.value = nativeGetPeakDb(nativeHandle)
                delay(33) // ~30 FPS UI metering update
            }
        }
    }

    protected fun finalize() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // JNI External Declarations
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetInputGain(handle: Long, gainDb: Float)
    private external fun nativeSetMuted(handle: Long, muted: Boolean)
    private external fun nativeSetVoicePreset(handle: Long, presetId: Int)
    private external fun nativeSetFeedbackSuppression(handle: Long, enabled: Boolean)
    private external fun nativeGetRmsDb(handle: Long): Float
    private external fun nativeGetPeakDb(handle: Long): Float
    private external fun nativeGetUnderruns(handle: Long): Long
}
