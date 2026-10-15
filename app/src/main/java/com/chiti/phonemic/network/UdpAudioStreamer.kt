package com.chiti.phonemic.network

import com.chiti.phonemic.audio.PcmRingBuffer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 mono 48k; 480 samples/datagram, big-endian seq + monotonic ns, little-endian PCM.
 * Raw LAN protocol is unencrypted: only enable on trusted Wi-Fi. No internet traversal.
 * All socket I/O lives on Dispatchers.IO. Audio only touches its dedicated SPSC queue.
 */
class UdpAudioStreamer {
    val outgoing = PcmRingBuffer()
    val incoming = PcmRingBuffer()
    private val mutable = MutableStateFlow("Offline")
    val status = mutable.asStateFlow()
    @Volatile var failed = false; private set
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun startTransmitter(receiver: AudioReceiver) {
        check(!running); failed = false
        val sock = DatagramSocket().also { socket = it; it.soTimeout = 1; it.connect(receiver.address, receiver.port) }
        running = true
        job = scope.launch {
            val samples = ShortArray(480)
            val bytes = ByteArray(972)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val packet = DatagramPacket(bytes, bytes.size)
            val ack = DatagramPacket(ByteArray(12), 12)
            var sequence = 0
            var lastAck = System.nanoTime()
            mutable.value = "Connecting to ${receiver.name} (${receiver.address.hostAddress})"
            try {
                while (running) {
                    if (outgoing.available() < 480) { delay(2); continue }
                    outgoing.poll(samples, 480)
                    header.putInt(0, sequence++); header.putLong(4, System.nanoTime())
                    for (i in samples.indices) { bytes[12 + i * 2] = samples[i].toByte(); bytes[13 + i * 2] = (samples[i].toInt() shr 8).toByte() }
                    sock.send(packet)
                    try {
                        ack.length = 12; sock.receive(ack)
                        if (ack.length == 12) {
                            val sent = ByteBuffer.wrap(ack.data).order(ByteOrder.BIG_ENDIAN).getLong(4)
                            val now = System.nanoTime()
                            if (sent in (now - 2_000_000_000L)..now) {
                                lastAck = now
                                mutable.value = "Connected to ${receiver.name} (${receiver.address.hostAddress}) • RTT ${(now - sent) / 1_000_000} ms"
                            }
                        }
                    } catch (_: SocketTimeoutException) { }
                    check(System.nanoTime() - lastAck < 2_000_000_000L) { "Receiver lost — tap to reconnect" }
                }
            } catch (e: Exception) { if (running) { failed = true; mutable.value = e.message ?: "Network failed" } }
            finally { running = false; sock.close() }
        }
    }
    fun startReceiver() {
        check(!running); failed = false
        val sock = DatagramSocket(AudioServiceDiscovery.PORT).also { socket = it; it.soTimeout = 2 }
        running = true
        job = scope.launch {
            val packet = DatagramPacket(ByteArray(973), 973)
            val samples = ShortArray(480)
            val playout = ShortArray(480)
            val jitter = PacketJitterBuffer()
            var peer: SocketAddress? = null
            var lastPacket = System.nanoTime()
            mutable.value = "Listening on UDP 50005 • trusted Wi-Fi only"
            try {
                while (running) {
                    try {
                        packet.length = 973; sock.receive(packet)
                        if (packet.length == 972 && (peer == null || peer == packet.socketAddress)) {
                            val bytes = packet.data
                            val sequence = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(0)
                            for (i in samples.indices) samples[i] = ((bytes[12 + i * 2].toInt() and 255) or (bytes[13 + i * 2].toInt() shl 8)).toShort()
                            val now = System.nanoTime()
                            if (jitter.offer(sequence, samples, now)) {
                                peer = packet.socketAddress; lastPacket = now
                                sock.send(DatagramPacket(bytes, 12, packet.socketAddress))
                                mutable.value = "Receiving ${packet.address.hostAddress} • buffer ${jitter.depth * 10} ms • lost ${jitter.lost}"
                            }
                        }
                    } catch (_: SocketTimeoutException) { }
                    val now = System.nanoTime()
                    if (jitter.poll(playout, now)) incoming.offer(playout, 480)
                    check(peer == null || now - lastPacket < 2_000_000_000L) { "Presenter lost — tap to reconnect" }
                }
            } catch (e: Exception) { if (running) { failed = true; mutable.value = e.message ?: "Receiver failed" } }
            finally { running = false; sock.close() }
        }
    }
    fun stop() { running = false; socket?.close(); socket = null; job?.cancel(); job = null }
}
