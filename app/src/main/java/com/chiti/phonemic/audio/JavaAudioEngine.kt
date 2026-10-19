package com.chiti.phonemic.audio

import android.media.*
import com.chiti.phonemic.model.NetworkRole
import com.chiti.phonemic.network.UdpAudioStreamer
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/** Blocking platform I/O on a dedicated audio worker; no UI or Flow updates in the PCM loop. */
class JavaAudioEngine(private val context: Context,
    private val role: NetworkRole = NetworkRole.STANDALONE,
    private val network: UdpAudioStreamer? = null,
    private val recorder: WavAudioRecorder? = null,
    val soundboard: SoundboardPlayer = SoundboardPlayer()) : AudioEngine {
    private val state = MutableStateFlow<AudioState>(AudioState.Idle)
    override val audioState = state.asStateFlow()
    private val rms = MutableStateFlow(-96f)
    override val currentRmsDb = rms.asStateFlow()
    private val peak = MutableStateFlow(-96f)
    override val currentPeakDb = peak.asStateFlow()
    private val feedback = MutableStateFlow(false)
    override val isFeedbackActive = feedback.asStateFlow()
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
        if ((role != NetworkRole.RECEIVER_MIXER && role != NetworkRole.WALKIE_TALKIE) && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state.value = AudioState.Error("Microphone permission is required")
            return false
        }
        return try {
            val rate = 48000
            val inputBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val outputBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputBytes > 0 && outputBytes > 0)
            val format = AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            val input = if ((role != NetworkRole.RECEIVER_MIXER && role != NetworkRole.WALKIE_TALKIE)) AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(max(inputBytes, 1920)).build().also { record = it } else null
            val output = if (role != NetworkRole.TRANSMITTER) AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(outputBytes, 1920)).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build().also { track = it } else null
            check((input == null || input.state == AudioRecord.STATE_INITIALIZED) && (output == null || output.state == AudioTrack.STATE_INITIALIZED))
            val samples = ShortArray(480)
            val dsp = SpeechDsp(soundboard)
            setInputGain(initialGainDb)
            input?.startRecording()
            check(input == null || input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            output?.play()
            running = true
            state.value = AudioState.Active(outputDeviceName, 0, initialGainDb, preset)
            worker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var masterGain = 1f
                try {
                    while (running) {
                        val n = if (input != null) input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        else {
                            // AudioTrack paces receiver playout; lack of data renders limited silence.
                            if ((network?.incoming?.available() ?: 0) >= 480) network?.incoming?.poll(samples, 480) ?: 0
                            else {
                                if (role == NetworkRole.WALKIE_TALKIE) {
                                    java.util.concurrent.locks.LockSupport.parkNanos(5_000_000)
                                    continue // Let the idle playback track enter standby; never open a microphone here.
                                }
                                samples.fill(0); 480
                            }
                        }
                        if (n <= 0) { if (running) error("Audio capture failed: $n"); break }
                        if ((role == NetworkRole.RECEIVER_MIXER || role == NetworkRole.WALKIE_TALKIE)) {
                            // Received PCM is already processed; only mix station chimes + master limiter.
                            for (i in 0 until n) {
                                masterGain += .002f * (gain - masterGain)
                                samples[i] = ((samples[i] / 32768f * masterGain + soundboard.next())
                                    .coerceIn(-.944f, .944f) * 32767).toInt().toShort()
                            }
                        } else dsp.process(samples, n, gain, preset)
                        recorder?.offer(samples, n)
                        if ((role == NetworkRole.RECEIVER_MIXER || role == NetworkRole.WALKIE_TALKIE)) {
                            var energy = 0f; var maximum = 0f
                            for (i in 0 until n) { val x = samples[i] / 32768f; energy += x * x; maximum = max(maximum, abs(x)) }
                            rmsValue = if (energy > 1e-10f) 10f * log10(energy / n) else -96f
                            peakValue = if (maximum > 1e-5f) 20f * log10(maximum) else -96f
                        } else { rmsValue = dsp.rmsDb; peakValue = dsp.peakDb }
                        if (role == NetworkRole.TRANSMITTER) { network?.outgoing?.offer(samples, n); continue }
                        var offset = 0
                        while (running && offset < n) {
                            val written = output?.write(samples, offset, n - offset, AudioTrack.WRITE_BLOCKING) ?: -1
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
    override fun setVoicePreset(presetId: Int) { preset = presetId.coerceIn(0, 8) }
    override fun setFeedbackSuppression(enabled: Boolean) = Unit // No adaptive notch in fallback.
    override fun getUnderrunCount(): Long = track?.underrunCount?.toLong() ?: 0L
    override fun setBgmTrack(trackId: Int) { soundboard.setBgmTrack(trackId) }
    override fun setBgmVolume(volume: Float) { soundboard.setBgmVolume(volume) }
    override fun setAutoDucking(enabled: Boolean) { soundboard.setAutoDucking(enabled) }
}
