package com.chiti.phonemic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.chiti.phonemic.bluetooth.BluetoothAudioRouter
import com.chiti.phonemic.service.MicForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process-scoped coordinator. Only the foreground service opens audio streams. */
class AudioSessionManager private constructor(private val context: Context) : AudioEngine {
    companion object {
        @Volatile private var instance: AudioSessionManager? = null
        fun get(context: Context): AudioSessionManager = instance ?: synchronized(this) {
            instance ?: AudioSessionManager(context.applicationContext).also { instance = it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState = state.asStateFlow()
    private val rms = MutableStateFlow(-96f)
    override val currentRmsDb = rms.asStateFlow()
    private val peak = MutableStateFlow(-96f)
    override val currentPeakDb = peak.asStateFlow()
    private var engine: AudioEngine? = null
    private var meters: Job? = null
    private var gain = 0f
    private var preset = 0
    private var deviceName = "Phone speaker"
    val router = BluetoothAudioRouter(context)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) setMuted(true, MuteReason.AUDIO_FOCUS_LOST)
        }, Handler(Looper.getMainLooper())).build()
    init {
        router.onOutputRemoved = { setMuted(true, MuteReason.BLUETOOTH_DISCONNECTED) }
        router.startListening()
    }
    override suspend fun start(outputDeviceName: String, initialGainDb: Float): Boolean {
        if (state.value is AudioState.Active || state.value == AudioState.Connecting) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.value = AudioState.Error("Microphone permission is required"); return false
        }
        gain = initialGainDb; deviceName = outputDeviceName
        state.value = AudioState.Connecting
        return try { MicForegroundService.start(context, deviceName); true }
        catch (e: Exception) { state.value = AudioState.Error("Cannot start microphone service", e); false }
    }
    suspend fun startFromService(): Boolean {
        if (state.value != AudioState.Connecting) return state.value is AudioState.Active
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state.value = AudioState.Error("Audio focus is unavailable"); return false
        }
        var candidate: AudioEngine? = null
        try {
            candidate = NativeAudioEngine()
            candidate.setVoicePreset(preset)
            if (!candidate.start(deviceName, gain)) { candidate.stop(); candidate = null }
        } catch (_: LinkageError) { candidate?.stop(); candidate = null }
          catch (_: Exception) { candidate?.stop(); candidate = null }
        if (candidate == null) {
            candidate = JavaAudioEngine()
            candidate.setVoicePreset(preset)
            if (!candidate.start(deviceName, gain)) {
                candidate.stop(); audioManager.abandonAudioFocusRequest(focus)
                state.value = AudioState.Error("Neither audio engine could start"); return false
            }
        }
        engine = candidate
        state.value = AudioState.Active(deviceName, 0, gain, preset)
        val active = checkNotNull(candidate)
        meters = scope.launch {
            while (isActive) {
                rms.value = active.currentRmsDb.value; peak.value = active.currentPeakDb.value
                if (active.audioState.value is AudioState.Error) {
                    stop(); state.value = AudioState.Error("Audio device stopped. Tap to retry."); break
                }
                delay(33)
            }
        }
        return true
    }
    private fun closeEngine() {
        meters?.cancel(); meters = null
        engine?.stop(); engine = null
        audioManager.abandonAudioFocusRequest(focus)
        rms.value = -96f; peak.value = -96f
    }
    override fun stop() {
        closeEngine(); state.value = AudioState.Idle
        context.stopService(android.content.Intent(context, MicForegroundService::class.java))
    }
    fun serviceDestroyed() { closeEngine(); state.value = AudioState.Idle }
    override fun setMuted(muted: Boolean, reason: MuteReason) {
        if (muted) {
            val previous = state.value as? AudioState.Active ?: return
            closeEngine(); state.value = AudioState.Muted(reason, previous)
        } else if (state.value is AudioState.Muted) scope.launch {
            start(router.connectedOutput.value?.name ?: "Phone speaker", gain)
        }
    }
    override fun setInputGain(gainDb: Float) { gain = gainDb.coerceIn(-12f, 12f); engine?.setInputGain(gain) }
    override fun setVoicePreset(presetId: Int) { preset = presetId.coerceIn(0, 5); engine?.setVoicePreset(preset) }
    override fun setFeedbackSuppression(enabled: Boolean) { engine?.setFeedbackSuppression(enabled) }
    override fun getUnderrunCount(): Long = engine?.getUnderrunCount() ?: 0
}
