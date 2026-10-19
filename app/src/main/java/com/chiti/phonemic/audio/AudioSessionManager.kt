package com.chiti.phonemic.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import com.chiti.phonemic.model.NetworkRole
import com.chiti.phonemic.model.ScenarioProfile
import com.chiti.phonemic.network.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.chiti.phonemic.bluetooth.BluetoothAudioRouter
import com.chiti.phonemic.service.MicForegroundService
import java.io.File
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
    private val feedback = MutableStateFlow(false)
    override val isFeedbackActive = feedback.asStateFlow()
    val recorder = WavAudioRecorder(context)
    val discovery = AudioServiceDiscovery(context)
    private val selectedRole = MutableStateFlow(NetworkRole.STANDALONE)
    val role = selectedRole.asStateFlow()
    private val networkMessage = MutableStateFlow("Single phone")
    val connectionStatus = networkMessage.asStateFlow()
    private val selectedReceiverFlow = MutableStateFlow<AudioReceiver?>(null)
    val selectedReceiver = selectedReceiverFlow.asStateFlow()
    private var network: UdpAudioStreamer? = null
    private var preview: MediaPlayer? = null
    private val playingFile = MutableStateFlow<File?>(null)
    val currentlyPlaying = playingFile.asStateFlow()

    private val recordingsList = MutableStateFlow<List<File>>(emptyList())
    val recordings = recordingsList.asStateFlow()

    private val selectedBgmTrack = MutableStateFlow(0)
    val bgmTrack = selectedBgmTrack.asStateFlow()
    private val selectedBgmVolume = MutableStateFlow(0.35f)
    val bgmVolume = selectedBgmVolume.asStateFlow()

    private val selectedAutoDucking = MutableStateFlow(true)
    val autoDucking = selectedAutoDucking.asStateFlow()

    private val selectedScenario = MutableStateFlow<ScenarioProfile?>(null)
    val scenarioProfile = selectedScenario.asStateFlow()

    fun refreshRecordings() {
        recordingsList.value = recorder.getRecordings()
    }

    fun setRole(value: NetworkRole) {
        if (state.value is AudioState.Active || state.value == AudioState.Connecting) return
        stop(); discovery.stop(); selectedRole.value = value; selectedReceiverFlow.value = null
        networkMessage.value = if (value == NetworkRole.STANDALONE) "Single phone" else "Trusted Wi-Fi only • unencrypted audio"
        if (value == NetworkRole.TRANSMITTER) discovery.discoverServices()
    }

    fun selectReceiver(receiver: AudioReceiver) {
        if (state.value is AudioState.Active || state.value == AudioState.Connecting) return
        selectedReceiverFlow.value = receiver
        networkMessage.value = "Selected ${receiver.name} (${receiver.address.hostAddress})"
    }

    fun triggerSound(effect: Int) {
        if (state.value !is AudioState.Active) return
        when (val current = engine) {
            is NativeAudioEngine -> current.triggerSound(effect)
            is JavaAudioEngine -> current.soundboard.trigger(effect)
        }
    }

    override fun setBgmTrack(trackId: Int) {
        selectedBgmTrack.value = trackId
        engine?.setBgmTrack(trackId)
    }

    override fun setBgmVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        selectedBgmVolume.value = clamped
        engine?.setBgmVolume(clamped)
    }

    fun toggleRecording() {
        if (recorder.state.value.recording) {
            recorder.stop()
        } else if (state.value is AudioState.Active) {
            recorder.start()
        } else {
            // Auto-start broadcasting and start recording immediately
            scope.launch {
                val started = start(deviceName, gain)
                if (started) {
                    delay(150)
                    recorder.start()
                }
            }
        }
    }

    fun playRecordingFile(file: File) {
        if (state.value is AudioState.Active || state.value == AudioState.Connecting) return
        stopPlayback()
        runCatching {
            preview = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (preview === it) preview = null
                    playingFile.value = null
                }
                setOnPreparedListener {
                    it.start()
                    playingFile.value = file
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (preview === player) preview = null
                    playingFile.value = null
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            preview?.release()
            preview = null
            playingFile.value = null
            networkMessage.value = "Playback error"
        }
    }

    fun stopPlayback() {
        preview?.stop()
        preview?.release()
        preview = null
        playingFile.value = null
    }

    fun deleteRecordingFile(file: File): Boolean {
        if (playingFile.value?.absolutePath == file.absolutePath) {
            stopPlayback()
        }
        val deleted = recorder.deleteRecording(file)
        if (deleted) refreshRecordings()
        return deleted
    }

    fun playPreview() {
        val file = recorder.state.value.file ?: recordingsList.value.firstOrNull() ?: return
        playRecordingFile(file)
    }

    private var engine: AudioEngine? = null
    val activeEngineName: String
        get() = when (engine) {
            is NativeAudioEngine -> "Native C++ (Oboe / AAudio)"
            is JavaAudioEngine -> "Platform Fallback (AudioRecord)"
            else -> "Ready (Auto)"
        }
    private var meters: Job? = null
    private var gain = 0f
    private var preset = 0
    private var feedbackSuppressionEnabled = true
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
        refreshRecordings()
        scope.launch {
            recorder.state.collect { recState ->
                if (!recState.recording) refreshRecordings()
            }
        }
    }
    override suspend fun start(outputDeviceName: String, initialGainDb: Float): Boolean {
        if (state.value is AudioState.Active || state.value == AudioState.Connecting) return true
        if (selectedRole.value != NetworkRole.RECEIVER && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.value = AudioState.Error("Microphone permission is required"); return false
        }
        if (selectedRole.value == NetworkRole.TRANSMITTER && selectedReceiverFlow.value == null) {
            state.value = AudioState.Error("Select a discovered receiver first"); return false
        }
        stopPlayback()
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
            when (selectedRole.value) {
                NetworkRole.TRANSMITTER -> {
                    network = UdpAudioStreamer().also { it.startTransmitter(checkNotNull(selectedReceiverFlow.value)) }
                }
                NetworkRole.RECEIVER -> {
                    network = UdpAudioStreamer().also { it.startReceiver() }
                    discovery.registerService()
                }
                else -> Unit
            }
        } catch (e: Exception) {
            closeEngine(); state.value = AudioState.Error("Cannot start Wi-Fi audio: ${e.message}"); return false
        }
        try {
            if (selectedRole.value == NetworkRole.STANDALONE) candidate = NativeAudioEngine(recorder)
            candidate?.setVoicePreset(preset)
            candidate?.setFeedbackSuppression(feedbackSuppressionEnabled)
            candidate?.setBgmTrack(selectedBgmTrack.value)
            candidate?.setBgmVolume(selectedBgmVolume.value)
            candidate?.setAutoDucking(selectedAutoDucking.value)
            if (candidate != null && !candidate.start(deviceName, gain)) { candidate.stop(); candidate = null }
        } catch (_: LinkageError) { candidate?.stop(); candidate = null }
          catch (_: Exception) { candidate?.stop(); candidate = null }
        if (candidate == null) {
            val javaEng = JavaAudioEngine(context, selectedRole.value, network, recorder)
            javaEng.setVoicePreset(preset)
            javaEng.setFeedbackSuppression(feedbackSuppressionEnabled)
            javaEng.soundboard.setBgmTrack(selectedBgmTrack.value)
            javaEng.soundboard.setBgmVolume(selectedBgmVolume.value)
            javaEng.setAutoDucking(selectedAutoDucking.value)
            candidate = javaEng
            if (!candidate.start(deviceName, gain)) {
                candidate.stop(); closeEngine()
                state.value = AudioState.Error("Neither audio engine could start"); return false
            }
        }
        engine = candidate
        state.value = AudioState.Active(deviceName, 0, gain, preset)
        val active = checkNotNull(candidate)
        meters = scope.launch {
            while (isActive) {
                network?.let { networkMessage.value = it.status.value }
                if (network?.failed == true) {
                    setMuted(true, MuteReason.NETWORK_DISCONNECTED); break
                }
                rms.value = active.currentRmsDb.value; peak.value = active.currentPeakDb.value
                feedback.value = active.isFeedbackActive.value
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
        recorder.stop()
        stopPlayback()
        network?.stop(); network = null
        if (selectedRole.value == NetworkRole.RECEIVER) discovery.stop()
        audioManager.abandonAudioFocusRequest(focus)
        rms.value = -96f; peak.value = -96f
        feedback.value = false
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
    override fun setVoicePreset(presetId: Int) { preset = presetId.coerceIn(0, 8); engine?.setVoicePreset(preset) }
    override fun setFeedbackSuppression(enabled: Boolean) {
        feedbackSuppressionEnabled = enabled
        engine?.setFeedbackSuppression(enabled)
    }
    override fun getUnderrunCount(): Long = engine?.getUnderrunCount() ?: 0

    override fun setAutoDucking(enabled: Boolean) {
        selectedAutoDucking.value = enabled
        engine?.setAutoDucking(enabled)
    }

    fun applyScenarioProfile(profile: ScenarioProfile) {
        runCatching {
            selectedScenario.value = profile
            setVoicePreset(profile.recommendedPreset.id)
            setInputGain(profile.defaultGainDb)
            setAutoDucking(profile.autoDucking)
            setFeedbackSuppression(profile.feedbackSuppression)
        }.onFailure { e ->
            android.util.Log.e("AudioSessionManager", "Error applying scenario profile ${profile.id}", e)
        }
    }

    fun clearScenarioProfile() {
        selectedScenario.value = null
    }
}
