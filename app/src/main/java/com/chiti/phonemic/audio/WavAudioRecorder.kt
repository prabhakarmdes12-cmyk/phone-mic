package com.chiti.phonemic.audio

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

object WavHeader {
    const val SAMPLE_RATE = 48000
    fun bytes(dataBytes: Long): ByteArray {
        require(dataBytes in 0..0xffff_ffffL - 36 && dataBytes % 2 == 0L)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt())
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt()).array()
    }
}

data class RecordingState(val recording: Boolean = false, val seconds: Long = 0,
    val file: File? = null, val error: String? = null, val droppedSamples: Long = 0)

/** Separate queue per recording. Closing detaches the producer before draining and patching header. */
class WavAudioRecorder(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    @Volatile private var sink: PcmRingBuffer? = null
    private var job: Job? = null
    private val inFlight = AtomicInteger(0)
    fun offer(pcm: ShortArray, count: Int) {
        inFlight.incrementAndGet()
        sink?.offer(pcm, count)
        inFlight.decrementAndGet()
    }
    fun start() {
        if (job?.isActive == true) return
        val queue = PcmRingBuffer(262144)
        sink = queue
        mutable.value = RecordingState(recording = true)
        job = scope.launch {
            var file: File? = null
            var count = 0L
            try {
                val directory = context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
                    ?: error("Recording storage unavailable")
                check(directory.exists() || directory.mkdirs())
                file = File.createTempFile("PhoneMic_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_", ".wav", directory)
                val pcm = ShortArray(4096)
                val bytes = ByteArray(pcm.size * 2)
                RandomAccessFile(file, "rw").use { output ->
                    output.write(WavHeader.bytes(0))
                    try {
                        while (sink === queue || inFlight.get() > 0 || queue.available() > 0) {
                            val n = queue.poll(pcm)
                            if (n == 0) { delay(5); continue }
                            check(count + n * 2 < 0xffff_ffffL - 36) { "WAV size limit reached" }
                            for (i in 0 until n) {
                                bytes[i * 2] = pcm[i].toByte()
                                bytes[i * 2 + 1] = (pcm[i].toInt() shr 8).toByte()
                            }
                            output.write(bytes, 0, n * 2); count += n * 2
                            mutable.value = RecordingState(true, count / 96000, file, droppedSamples = queue.droppedSamples)
                        }
                    } finally {
                        output.seek(0); output.write(WavHeader.bytes(count)); output.fd.sync()
                    }
                }
                mutable.value = RecordingState(false, count / 96000, file, droppedSamples = queue.droppedSamples)
            } catch (e: Exception) {
                mutable.value = RecordingState(false, count / 96000, file, e.message, queue.droppedSamples)
            } finally { if (sink === queue) sink = null }
        }
    }
    fun stop() { sink = null } // Writer drains asynchronously; no disk I/O on caller/audio thread.
}
