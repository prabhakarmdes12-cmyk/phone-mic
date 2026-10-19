package com.chiti.phonemic.network

import java.net.SocketAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Immutable UI snapshot; constructed by telemetry, never by render(). */
data class ConnectedMicChannel(val address: SocketAddress, val deviceName: String,
    val rmsDb: Float, val gain: Float, val isMuted: Boolean, val lostPackets: Long)

class ChannelSession {
    @Volatile var address: SocketAddress? = null
    @Volatile var deviceName = "Wireless mic"
    var sessionToken: Int? = null // socket-thread metadata; changes restart the per-peer sequence timeline.
    val jitterBuffer = PacketJitterBuffer()
    @Volatile var lastPacketTimeNs = 0L
    @Volatile var gain = 1f
    @Volatile var isMuted = false
    @Volatile var rmsDb = -96f
    private var smoothGain = 1f
    private val frame = ShortArray(480)
    fun reset() {
        sessionToken = null; jitterBuffer.reset(); gain = 1f; smoothGain = 1f; isMuted = false; rmsDb = -96f
    }
    fun mixInto(bus: FloatArray, now: Long) {
        if (!jitterBuffer.poll(frame, now)) { rmsDb = -96f; return }
        var energy = 0f
        val target = if (isMuted) 0f else gain
        for (i in 0 until 480) {
            smoothGain += .01f * (target - smoothGain)
            val x = frame[i] / 32768f
            energy += x * x
            // Mute is immediate; unmute and gain changes ramp to avoid clicks.
            bus[i] += if (isMuted) 0f else x * smoothGain
        }
        rmsDb = if (energy > 1e-10f) 10f * log10(energy / 480) else -96f
    }
}

/** Single packet/render-thread owner. Fixed pool bounds both CPU and memory at eight devices.
 * Concurrent map supports UI control lookups, but rendering uses no map iterators/allocations.
 */
class MultiChannelMixer {
    companion object {
        const val MAX_CHANNELS = 8
        const val STALE_NS = 3_000_000_000L
        const val CEILING = .944f
        fun limit(x: Float): Float {
            if (!x.isFinite()) return 0f
            // Unity below -3 dB; continuous soft knee asymptotes to permanent -0.5 dBFS ceiling.
            val magnitude = abs(x)
            if (magnitude <= .7f) return x
            val shaped = .7f + .244f * tanh((magnitude - .7f) / .244f)
            return (if (x < 0) -shaped else shaped).coerceIn(-CEILING, CEILING)
        }
    }
    val channels = ConcurrentHashMap<SocketAddress, ChannelSession>()
    private val pool = Array(MAX_CHANNELS) { ChannelSession() }
    private val bus = FloatArray(480)
    /** Admission may allocate a map node; call only on the background socket worker, not rendering. */
    fun admit(address: SocketAddress, name: String, now: Long): ChannelSession? {
        channels[address]?.let { return it }
        for (i in pool.indices) {
            val channel = pool[i]
            if (channel.address == null) {
                channel.reset(); channel.deviceName = name.take(48); channel.lastPacketTimeNs = now
                channel.address = address; channels[address] = channel
                return channel
            }
        }
        return null
    }
    fun find(host: InetAddress, port: Int): ChannelSession? {
        for (i in pool.indices) {
            val channel = pool[i]
            val peer = channel.address as? InetSocketAddress ?: continue
            if (peer.port == port && peer.address == host) return channel
        }
        return null
    }
    fun expire(now: Long) {
        for (i in pool.indices) {
            val channel = pool[i]
            val address = channel.address ?: continue
            if (now - channel.lastPacketTimeNs >= STALE_NS) {
                channels.remove(address); channel.address = null
            }
        }
    }
    fun render(output: ShortArray, now: Long) {
        bus.fill(0f)
        for (i in pool.indices) if (pool[i].address != null) pool[i].mixInto(bus, now)
        for (i in 0 until 480) output[i] = (limit(bus[i]) * 32767).toInt().toShort()
    }
    fun snapshot(): List<ConnectedMicChannel> = channels.entries.map { (address, channel) ->
        ConnectedMicChannel(address, channel.deviceName, channel.rmsDb, channel.gain, channel.isMuted, channel.jitterBuffer.lost)
    }.sortedBy { it.deviceName }
    fun setGain(address: SocketAddress, gain: Float) {
        if (gain.isFinite()) channels[address]?.gain = gain.coerceIn(0f, 2f)
    }
    fun setMuted(address: SocketAddress, muted: Boolean) { channels[address]?.isMuted = muted }
}
