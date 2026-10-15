package com.chiti.phonemic.audio

import android.media.*
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/** Blocking platform I/O on a dedicated audio worker; no UI or Flow updates in the PCM loop. */
class JavaAudioEngine : AudioEngine {
    private val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState = state.asStateFlow()
    private val rms = MutableStateFlow(-96f)
    override val currentRmsDb = rms.asStateFlow()
    private val peak = MutableStateFlow(-96f)
    override val currentPeakDb = peak.asStateFlow()
    @Volatile private var running = false
    @Volatile private var gain = 1f
    @Volatile private var preset = 0
    @Volatile private var rmsValue = -96f
    @Volatile private var peakValue = -96f
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var meter: Job? = null

    override suspend fun start(outputDeviceName: String, initialGainDb: Float): Boolean {
        if (running) return true
        state.value = AudioState.Connecting
        return try {
            val rate = 48000
            val inputBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputBytes > 0 && outputBytes > 0)
            val format = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(inputBytes, 1920)).build().also { record = it }
            val output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(outputBytes, 1920)).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build().also { track = it }
            check(input.state == AudioRecord.STATE_INITIALIZED && output.state == AudioTrack.STATE_INITIALIZED)
            val samples = ShortArray(240)
            val dsp = SpeechDsp()
            setInputGain(initialGainDb)
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            output.play()
            running = true
            state.value = AudioState.Active(outputDeviceName, 0, initialGainDb, preset)
            worker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                try {
                    while (running) {
                        val n = input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (n <= 0) { if (running) error("Audio capture failed: $n"); break }
                        dsp.process(samples, n, gain, preset)
                        rmsValue = dsp.rmsDb; peakValue = dsp.peakDb
                        var offset = 0
                        while (running && offset < n) {
                            val written = output.write(samples, offset, n - offset, AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) { if (running) error("Audio playback failed: $written"); break }
                            offset += written
                        }
                    }
                } catch (e: Exception) {
                    if (running) state.value = AudioState.Error("Platform audio stopped", e)
                } finally { running = false }
            }, "PhoneMic-PCM").also { it.start() }
            meter = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) { rms.value = rmsValue; peak.value = peakValue; delay(33) }
            }
            true
        } catch (e: Exception) {
            stop(); state.value = AudioState.Error("Unable to open microphone or speaker", e); false
        }
    }

    override fun stop() {
        running = false
        runCatching { track?.pause(); track?.flush() }
        runCatching { record?.stop() }
        worker?.join()
        worker = null
        record?.release(); record = null
        track?.release(); track = null
        meter?.cancel(); meter = null
        rms.value = -96f; peak.value = -96f
        state.value = AudioState.Idle
    }
    // Session manager stops capture entirely on mute and requires an explicit new start.
    override fun setMuted(muted: Boolean, reason: MuteReason) { if (muted) stop() }
    override fun setInputGain(gainDb: Float) { gain = 10f.pow(gainDb.coerceIn(-12f, 12f) / 20f) }
    override fun setVoicePreset(presetId: Int) { preset = presetId.coerceIn(0, 5) }
    override fun setFeedbackSuppression(enabled: Boolean) = Unit // No adaptive notch in fallback.
    override fun getUnderrunCount(): Long = track?.underrunCount?.toLong() ?: 0L
}
