package com.chiti.phonemic.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.chiti.phonemic.audio.PcmRingBuffer
import kotlinx.coroutines.flow.*
import java.net.*
import java.util.concurrent.locks.LockSupport

/** Socket I/O is confined to a background worker, never an audio callback.
 * App-owned audio buffers, packets and mixer slots are allocated before the loop.
 * Android's socket API may allocate internally (addresses/timeouts); rendering itself does not.
 */
class UdpAudioStreamer(private val context: Context? = null) {
    companion object { const val GROUP = "239.255.42.99" }
    val outgoing = PcmRingBuffer(4096)
    val incoming = PcmRingBuffer(4096)
    val mixer = MultiChannelMixer()
    private val mutable = MutableStateFlow("Offline")
    val status = mutable.asStateFlow()
    @Volatile var failed = false; private set
    @Volatile private var transmissionEpoch = System.nanoTime().toInt()
    @Volatile var transmitting = false
        set(value) {
            if (value && !field) transmissionEpoch++
            field = value
            groupSender?.let { LockSupport.unpark(it) }
        }
    @Volatile private var running = false
    @Volatile private var rttMs = -1L
    @Volatile private var lastAckNs = 0L
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    @Volatile private var groupSender: Thread? = null
    @Volatile var playbackThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var description = "Offline"
    private var walkie = false
    private var sender = false
    private val name = runCatching { android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "PhoneMic" }.getOrDefault("PhoneMic")

    /** Called by UI telemetry at ~10 Hz, outside packet/render worker. */
    fun publishTelemetry() {
        if (failed) return
        mutable.value = when {
            walkie -> "LAN-1 • ${if (transmitting) "TRANSMITTING" else "Listening"} • ${mixer.channels.size} peers"
            sender -> "$description • ${if (rttMs >= 0) "RTT $rttMs ms" else "awaiting hub"}"
            else -> "Speaker Hub • ${mixer.channels.size}/8 microphones • UDP 50005"
        }
    }
    fun startTransmitter(receiver: AudioReceiver) {
        check(!running)
        val sock = DatagramSocket().also { socket = it; it.soTimeout = 1; it.connect(receiver.address, receiver.port) }
        sender = true; transmitting = true; description = "${receiver.name} (${receiver.address.hostAddress})"
        launch(sock, receiver.address, receiver.port, false, emptyArray())
    }
    fun startReceiver() {
        check(!running)
        val sock = DatagramSocket(AudioServiceDiscovery.PORT).also { socket = it; it.soTimeout = 2 }
        launch(sock, null, 0, false, emptyArray())
    }
    @Suppress("DEPRECATION")
    fun startWalkieTalkie(groupAddress: InetAddress, port: Int) {
        check(!running)
        require(groupAddress.isMulticastAddress && port == AudioServiceDiscovery.PORT)
        val app = checkNotNull(context) { "Android context required for multicast" }
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Find best network interface (supporting Wi-Fi or local Hotspot / AP)
        val activeNet = connectivity.activeNetwork
        val activeInterfaceName = activeNet?.let { connectivity.getLinkProperties(it)?.interfaceName }
        var nic = activeInterfaceName?.let { NetworkInterface.getByName(it) }

        if (nic == null || !nic.isUp || !nic.supportsMulticast()) {
            // Fallback: search all available network interfaces for Wi-Fi, Hotspot, or local LAN
            val allNics = runCatching {
                NetworkInterface.getNetworkInterfaces()?.let { java.util.Collections.list(it) }
            }.getOrNull() ?: emptyList()

            nic = allNics.firstOrNull { candidate ->
                try {
                    val addrs = java.util.Collections.list(candidate.inetAddresses)
                    candidate.isUp && !candidate.isLoopback && candidate.supportsMulticast() &&
                        (candidate.name.startsWith("wlan") || candidate.name.startsWith("ap") || 
                         candidate.name.startsWith("swlan") || candidate.name.startsWith("rndis") ||
                         addrs.any { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress })
                } catch (_: Exception) { false }
            } ?: allNics.firstOrNull { candidate ->
                try {
                    val addrs = java.util.Collections.list(candidate.inetAddresses)
                    candidate.isUp && !candidate.isLoopback &&
                        (candidate.name.startsWith("wlan") || candidate.name.startsWith("ap") || 
                         candidate.name.startsWith("swlan") || candidate.name.startsWith("rndis") ||
                         addrs.any { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress })
                } catch (_: Exception) { false }
            }
        }
        val targetNic = nic ?: error("Active Wi-Fi or Mobile Hotspot required for Walkie-Talkie")
        val localAddresses = java.util.Collections.list(targetNic.inetAddresses).toTypedArray()
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            multicastLock = runCatching {
                wifi?.createMulticastLock("PhoneMicMulticast")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            val sock = MulticastSocket(null).also { socket = it }
            sock.reuseAddress = true; sock.bind(InetSocketAddress(port)); sock.soTimeout = 2
            runCatching { sock.networkInterface = targetNic }
            sock.timeToLive = 1; sock.loopbackMode = true
            try {
                sock.joinGroup(InetSocketAddress(groupAddress, port), targetNic)
            } catch (_: Exception) {
                runCatching { sock.joinGroup(groupAddress) }
            }
            walkie = true
            launch(sock, groupAddress, port, true, localAddresses)
        } catch (e: Exception) { socket?.close(); releaseLock(); throw e }
    }
    private fun launch(sock: DatagramSocket, destination: InetAddress?, port: Int, isGroup: Boolean, locals: Array<InetAddress>) {
        failed = false; running = true
        worker = Thread({
            val receive = DatagramPacket(ByteArray(973), 973)
            val decoded = ShortArray(480)
            val mixed = ShortArray(480)
            val sentPcm = ShortArray(480)
            val audioBytes = ByteArray(972)
            val audio = DatagramPacket(audioBytes, 972)
            val helloBytes = AudioPacketCodec.hello(name)
            val hello = DatagramPacket(helloBytes, helloBytes.size)
            val reply = DatagramPacket(receive.data, 12)
            if (destination != null) { audio.address = destination; audio.port = port; hello.address = destination; hello.port = port }
            var sequence = 0
            var nextMix = System.nanoTime()
            var nextHello = 0L
            var epoch = transmissionEpoch - 1
            var receiveTimeout = 2
            if (isGroup) startGroupSender(sock, checkNotNull(destination), port)
            lastAckNs = nextMix
            try {
                while (running) {
                    var now = System.nanoTime()
                    if (destination != null && !isGroup) {
                        // Consumer-owned discard on PTT release; never reset a live SPSC queue.
                        if (!transmitting) {
                            while (outgoing.available() > 0) outgoing.poll(sentPcm)
                        } else if (outgoing.available() >= 480) {
                            if (epoch != transmissionEpoch) { epoch = transmissionEpoch; nextHello = 0 }
                            if (now >= nextHello) {
                                AudioPacketCodec.putInt(helloBytes, 68, epoch)
                                sock.send(hello); nextHello = now + 1_000_000_000L
                            }
                            outgoing.poll(sentPcm, 480)
                            AudioPacketCodec.encode(audioBytes, sequence++, now, sentPcm)
                            sock.send(audio)
                        }
                        if (!isGroup) check(now - lastAckNs < 3_000_000_000L) { "Hub lost — tap to reconnect" }
                    }
                    if (!sender || outgoing.available() < 480) {
                        try {
                            val timeout = if (isGroup && mixer.channels.isEmpty()) 1000 else 2
                            if (timeout != receiveTimeout) { sock.soTimeout = timeout; receiveTimeout = timeout }
                            receive.length = 973; sock.receive(receive)
                            var self = false
                            if (isGroup) for (i in locals.indices) if (receive.address == locals[i]) self = true
                            if (!self) {
                                now = System.nanoTime()
                                if (sender && receive.length == 12) {
                                    val timestamp = AudioPacketCodec.longAt(receive.data, 4)
                                    if (timestamp <= now && now - timestamp < 3_000_000_000L) {
                                        lastAckNs = now; rttMs = (now - timestamp) / 1_000_000
                                    }
                                } else if (!sender) {
                                    if (receive.length == 972) {
                                        var channel = mixer.find(receive.address, receive.port)
                                        if (channel == null) channel = mixer.admit(
                                            InetSocketAddress(receive.address, receive.port), "Mic ${receive.address.hostAddress}", now)
                                        if (channel != null) {
                                            AudioPacketCodec.decode(receive.data, decoded)
                                            if (channel.jitterBuffer.offer(AudioPacketCodec.intAt(receive.data, 0), decoded, now)) {
                                                channel.lastPacketTimeNs = now
                                                if (!isGroup) {
                                                    reply.address = receive.address; reply.port = receive.port; sock.send(reply)
                                                }
                                            }
                                        }
                                    } else if ((receive.length == AudioPacketCodec.HELLO_SIZE || receive.length == 68) && AudioPacketCodec.intAt(receive.data, 0) == 0x504d4943) {
                                        // Metadata/control path, not rendering: bounded UTF-8 name allocations permitted here.
                                        val channel = mixer.find(receive.address, receive.port) ?: mixer.admit(
                                            InetSocketAddress(receive.address, receive.port), "Wireless mic", now)
                                        if (channel != null) {
                                            channel.deviceName = AudioPacketCodec.deviceName(receive.data)
                                            if (receive.length == AudioPacketCodec.HELLO_SIZE) {
                                                val token = AudioPacketCodec.intAt(receive.data, 68)
                                                channel.beginSession(token, now)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: SocketTimeoutException) { /* Socket worker only; never the render/audio callback. */ }
                    }
                    now = System.nanoTime()
                    if (!sender && now >= nextMix) {
                        mixer.expire(now)
                        mixer.render(mixed, now)
                        // Walkie standby doesn't continuously enqueue silence or wake playback.
                        if (!isGroup || mixer.channels.isNotEmpty()) {
                            incoming.offer(mixed, 480)
                            playbackThread?.let { LockSupport.unpark(it) }
                        }
                        nextMix += 10_000_000L
                        if (now - nextMix > 20_000_000L) nextMix = now + 10_000_000L
                    }
                    if (sender && outgoing.available() < 480) LockSupport.parkNanos(500_000)
                }
            } catch (e: Exception) {
                if (running) { failed = true; mutable.value = e.message ?: "Network stopped" }
            } finally {
                running = false; groupSender?.let { LockSupport.unpark(it) }
                sock.close(); releaseLock()
            }
        }, "PhoneMic-UDP").also { it.start() }
    }
    /** Separate multicast transmit worker allows the receive socket to block in standby.
     * No busy polling or microphone capture while PTT is released.
     */
    private fun startGroupSender(sock: DatagramSocket, group: InetAddress, port: Int) {
        groupSender = Thread({
            val pcm = ShortArray(480)
            val bytes = ByteArray(972)
            val packet = DatagramPacket(bytes, bytes.size, group, port)
            val helloBytes = AudioPacketCodec.hello(name)
            val hello = DatagramPacket(helloBytes, helloBytes.size, group, port)
            var sequence = 0
            var epoch = transmissionEpoch - 1
            var nextHello = 0L
            try {
                while (running) {
                    if (!transmitting) {
                        while (outgoing.available() > 0) outgoing.poll(pcm)
                        // Recheck after draining to avoid sleeping through a racing key-down wakeup.
                        if (!transmitting && running) LockSupport.parkNanos(20_000_000L)
                        continue
                    }
                    if (epoch != transmissionEpoch) {
                        epoch = transmissionEpoch
                        // Discard a possible trailing old capture block before opening a new burst.
                        while (outgoing.available() > 0) outgoing.poll(pcm)
                        AudioPacketCodec.putInt(helloBytes, 68, epoch)
                        repeat(3) { sock.send(hello) } // Same token: duplicate HELLO never flushes valid buffered audio.
                        nextHello = System.nanoTime() + 1_000_000_000L
                    }
                    if (outgoing.available() < 480) { LockSupport.parkNanos(1_000_000); continue }
                    val now = System.nanoTime()
                    if (now >= nextHello) { sock.send(hello); nextHello = now + 1_000_000_000L }
                    outgoing.poll(pcm, 480)
                    AudioPacketCodec.encode(bytes, sequence++, now, pcm)
                    if (transmitting) sock.send(packet)
                }
            } catch (e: Exception) {
                if (running) { failed = true; mutable.value = e.message ?: "Multicast transmit failed"; running = false; sock.close() }
            }
        }, "PhoneMic-Multicast-TX").also { it.start() }
    }
    @Synchronized private fun releaseLock() {
        multicastLock?.let { if (it.isHeld) it.release() }; multicastLock = null
    }
    fun stop() {
        transmitting = false; running = false; socket?.close(); socket = null
        groupSender?.let { LockSupport.unpark(it) }
        worker?.join(500); worker = null
        groupSender?.join(500); groupSender = null; releaseLock()
    }
}
