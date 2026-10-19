package com.chiti.phonemic.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.math.abs

class MultiChannelMixerTest {
    private fun address(index: Int) = InetSocketAddress("127.0.0.1", 10000 + index)
    private fun add(mixer: MultiChannelMixer, index: Int, sample: Short, seq: Int = 0): ChannelSession {
        val channel = checkNotNull(mixer.admit(address(index), "Phone $index", 0))
        channel.jitterBuffer.offer(seq, ShortArray(480) { sample }, 0)
        return channel
    }
    @Test fun mixesTwoFourAndEightConcurrentMicrophones() {
        for (count in intArrayOf(2, 4, 8)) {
            val mixer = MultiChannelMixer()
            repeat(count) { add(mixer, it, 2000) }
            val output = ShortArray(480)
            mixer.render(output, 30_000_000)
            val expected = MultiChannelMixer.limit(count * 2000f / 32768f) * 32767
            assertEquals(count, mixer.channels.size)
            for (sample in output) assertEquals(expected, sample.toFloat(), 1f)
        }
    }
    @Test fun independentJitterReordersEachPeerWithoutCrossTalk() {
        val mixer = MultiChannelMixer()
        val a = add(mixer, 1, 1000, 10)
        val b = add(mixer, 2, 2000, 50)
        a.jitterBuffer.offer(12, ShortArray(480) { 3000 }, 1)
        b.jitterBuffer.offer(52, ShortArray(480) { 6000 }, 1)
        a.jitterBuffer.offer(11, ShortArray(480) { 2000 }, 2)
        b.jitterBuffer.offer(51, ShortArray(480) { 4000 }, 2)
        val output = ShortArray(480)
        for (i in 0..2) {
            mixer.render(output, 30_000_000L + i * 10_000_000L)
            assertEquals(((i + 1) * 3000).toFloat(), output[0].toFloat(), 2f)
        }
        assertEquals(0L, a.jitterBuffer.lost); assertEquals(0L, b.jitterBuffer.lost)
    }
    @Test fun missingPeerConcealsSilenceWithoutBlockingOtherChannels() {
        val mixer = MultiChannelMixer()
        add(mixer, 0, 1000)
        val b = add(mixer, 1, 2000)
        b.jitterBuffer.offer(1, ShortArray(480) { 4000 }, 1)
        val output = ShortArray(480)
        mixer.render(output, 30_000_000); mixer.render(output, 40_000_000)
        assertEquals(4000f, output[0].toFloat(), 2f)
    }
    @Test fun softLimiterIsSymmetricMonotonicAndNeverWraps() {
        var previous = 0f
        for (i in 0..10000) {
            val input = i / 100f
            val limited = MultiChannelMixer.limit(input)
            assertTrue(limited.isFinite()); assertTrue(limited >= previous)
            assertTrue(limited <= .944f)
            assertEquals(-limited, MultiChannelMixer.limit(-input), .000001f)
            previous = limited
        }
        val mixer = MultiChannelMixer(); repeat(8) { add(mixer, it, 32767) }
        val output = ShortArray(480); mixer.render(output, 30_000_000)
        assertTrue(output.all { it.toInt() in 30000..30932 })
        assertEquals(0f, MultiChannelMixer.limit(Float.NaN), 0f)
    }
    @Test fun muteAndGainAreIndependentAndDoNotWrap() {
        val mixer = MultiChannelMixer()
        add(mixer, 0, 4000); add(mixer, 1, 4000)
        mixer.setMuted(address(0), true); mixer.setGain(address(1), .5f)
        val output = ShortArray(480); mixer.render(output, 30_000_000)
        assertEquals(2000f, output[479].toFloat(), 25f)
        assertTrue(output.all { abs(it.toInt()) < 4001 })
    }
    @Test fun capacityAndThreeSecondExpiryAreBoundedAndReusable() {
        val mixer = MultiChannelMixer(); repeat(8) { add(mixer, it, 100) }
        assertNull(mixer.admit(address(9), "Excess", 0))
        mixer.expire(2_999_999_999); assertEquals(8, mixer.channels.size)
        mixer.expire(3_000_000_000); assertTrue(mixer.channels.isEmpty())
        val channel = mixer.admit(address(9), "New", 3_000_000_001)
        assertNotNull(channel); assertEquals(1f, channel!!.gain, 0f); assertFalse(channel.isMuted)
        val output = ShortArray(480); mixer.render(output, 3_000_000_002)
        assertTrue(output.all { it == 0.toShort() })
    }
    @Test fun packetCodecKeepsSequenceTimestampAndSignedPcm() {
        val bytes = ByteArray(972); val input = ShortArray(480) { if (it % 2 == 0) -32768 else 32767 }
        val output = ShortArray(480)
        AudioPacketCodec.encode(bytes, Int.MIN_VALUE, 1234567890123L, input)
        assertEquals(Int.MIN_VALUE, AudioPacketCodec.intAt(bytes, 0))
        assertEquals(1234567890123L, AudioPacketCodec.longAt(bytes, 4))
        AudioPacketCodec.decode(bytes, output); assertArrayEquals(input, output)
    }
    @Test fun newPttBurstResetsOnlyJitterAndPreservesChannelControls() {
        val mixer = MultiChannelMixer(); val channel = add(mixer, 0, 1000)
        channel.beginSession(1, 0)
        channel.jitterBuffer.offer(20, ShortArray(480) { 1000 }, 0)
        val output = ShortArray(480)
        channel.jitterBuffer.poll(output, 30_000_000)
        channel.jitterBuffer.poll(output, 40_000_000)
        channel.gain = .5f; channel.isMuted = true
        channel.beginSession(2, 100_000_000)
        assertTrue(channel.jitterBuffer.offer(0, ShortArray(480) { 2000 }, 100_000_000))
        // Repeated HELLO for the same burst must not discard packets already buffered.
        channel.beginSession(2, 110_000_000)
        assertTrue(channel.jitterBuffer.poll(output, 130_000_000))
        assertEquals(2000, output[0].toInt())
        assertEquals(.5f, channel.gain, 0f); assertTrue(channel.isMuted)
    }
    @Test fun sequenceWrapPerPeerWorks() {
        val mixer = MultiChannelMixer(); val a = add(mixer, 1, 1000, Int.MAX_VALUE)
        a.jitterBuffer.offer(Int.MIN_VALUE, ShortArray(480) { 2000 }, 1)
        val output = ShortArray(480); mixer.render(output, 30_000_000); mixer.render(output, 40_000_000)
        assertEquals(2000f, output[0].toFloat(), 2f)
    }
}
