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
class NativeAudioEngine(private val recorder: WavAudioRecorder? = null) : AudioEngine {

    companion object {
        init {
            System.loadLibrary("phonemic")
        }
    }

    private var nativeHandle: Long = 0
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var meterJob: Job? = null

    private val _audioState = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    private val _currentRmsDb = MutableStateFlow(-96.0f)
    override val currentRmsDb: StateFlow<Float> = _currentRmsDb.asStateFlow()

    private val _currentPeakDb = MutableStateFlow(-96.0f)
    override val currentPeakDb: StateFlow<Float> = _currentPeakDb.asStateFlow()

    private val _isFeedbackActive = MutableStateFlow(false)
    override val isFeedbackActive: StateFlow<Boolean> = _isFeedbackActive.asStateFlow()

    init {
        nativeHandle = nativeCreate()
    }

    override suspend fun start(outputDeviceName: String, initialGainDb: Float): Boolean {
        if (_audioState.value is AudioState.Active) return true

        _audioState.value = AudioState.Connecting
        if (nativeHandle == 0L) nativeHandle = nativeCreate()
        setInputGain(initialGainDb)

        val success = nativeStart(nativeHandle)
        if (success) {
            _audioState.value = AudioState.Active(
                outputDeviceName = outputDeviceName,
                estimatedLatencyMs = 0,
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
        val handle = nativeHandle
        if (handle != 0L) {
            runCatching { nativeStop(handle) }
            val tail = ShortArray(2048)
            repeat(32) {
                val n = runCatching { nativeReadProcessed(handle, tail) }.getOrDefault(0)
                if (n > 0) recorder?.offer(tail, n)
            }
            runCatching { nativeDestroy(handle) }
        }
        nativeHandle = 0L
        _audioState.value = AudioState.Idle
        _currentRmsDb.value = -96.0f
        _currentPeakDb.value = -96.0f
        _isFeedbackActive.value = false
    }

    override fun setInputGain(gainDb: Float) {
        if (nativeHandle != 0L) {
            nativeSetInputGain(nativeHandle, gainDb)
        }
        val current = _audioState.value
        if (current is AudioState.Active) {
            _audioState.value = current.copy(gainDb = gainDb)
        }
    }

    override fun setMuted(muted: Boolean, reason: MuteReason) {
        if (nativeHandle != 0L) {
            nativeSetMuted(nativeHandle, muted)
        }
        val current = _audioState.value
        if (muted && current is AudioState.Active) {
            _audioState.value = AudioState.Muted(reason = reason, previousState = current)
        } else if (!muted && current is AudioState.Muted && current.previousState != null) {
            _audioState.value = current.previousState
        }
    }

    override fun setVoicePreset(presetId: Int) {
        if (nativeHandle != 0L) {
            nativeSetVoicePreset(nativeHandle, presetId)
        }
        val current = _audioState.value
        if (current is AudioState.Active) {
            _audioState.value = current.copy(presetId = presetId)
        }
    }

    override fun setFeedbackSuppression(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetFeedbackSuppression(nativeHandle, enabled)
        }
    }

    override fun getUnderrunCount(): Long {
        return if (nativeHandle != 0L) nativeGetUnderruns(nativeHandle) else 0L
    }

    fun triggerSound(effect: Int) {
        if (nativeHandle != 0L) nativeTriggerSound(nativeHandle, effect)
    }
    override fun setBgmTrack(trackId: Int) {
        if (nativeHandle != 0L) nativeSetBgmTrack(nativeHandle, trackId)
    }
    override fun setBgmVolume(volume: Float) {
        if (nativeHandle != 0L) nativeSetBgmVolume(nativeHandle, volume)
    }
    override fun setAutoDucking(enabled: Boolean) {
        if (nativeHandle != 0L) nativeSetAutoDucking(nativeHandle, enabled)
    }
    private external fun nativeTriggerSound(handle: Long, effect: Int)
    private external fun nativeSetBgmTrack(handle: Long, trackId: Int)
    private external fun nativeSetBgmVolume(handle: Long, volume: Float)
    private external fun nativeSetAutoDucking(handle: Long, enabled: Boolean)
    private external fun nativeReadProcessed(handle: Long, target: ShortArray): Int

    private fun startMeteringLoop() {
        meterJob?.cancel()
        val pcm = ShortArray(2048)
        meterJob = scope.launch {
            while (isActive && _audioState.value is AudioState.Active) {
                val handle = nativeHandle
                if (handle != 0L) {
                    val rawRms = runCatching { nativeGetRmsDb(handle) }.getOrDefault(-96.0f)
                    val rawPeak = runCatching { nativeGetPeakDb(handle) }.getOrDefault(-96.0f)
                    _currentRmsDb.value = if (rawRms.isNaN() || rawRms.isInfinite()) -96.0f else rawRms
                    _currentPeakDb.value = if (rawPeak.isNaN() || rawPeak.isInfinite()) -96.0f else rawPeak
                    _isFeedbackActive.value = runCatching { nativeIsFeedbackActive(handle) }.getOrDefault(false)
                    val n = runCatching { nativeReadProcessed(handle, pcm) }.getOrDefault(0)
                    if (n > 0) recorder?.offer(pcm, n)
                }
                delay(15) // Drain processed audio off native callback and refresh metering
            }
        }
    }

    protected fun finalize() {
        val handle = nativeHandle
        if (handle != 0L) {
            runCatching { nativeDestroy(handle) }
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
    private external fun nativeIsFeedbackActive(handle: Long): Boolean
    private external fun nativeGetUnderruns(handle: Long): Long
}
