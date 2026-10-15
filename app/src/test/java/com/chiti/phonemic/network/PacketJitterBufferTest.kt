package com.chiti.phonemic.network

import org.junit.Assert.*
import org.junit.Test

class PacketJitterBufferTest {
    @Test fun reordersAndWaitsThirtyMilliseconds() {
        val buffer = PacketJitterBuffer(); val out = ShortArray(480)
        buffer.offer(5, ShortArray(480) { 5 }, 0)
        buffer.offer(7, ShortArray(480) { 7 }, 1)
        buffer.offer(6, ShortArray(480) { 6 }, 2)
        assertFalse(buffer.poll(out, 29_999_999))
        assertTrue(buffer.poll(out, 30_000_000)); assertEquals(5, out[0].toInt())
        assertTrue(buffer.poll(out, 40_000_000)); assertEquals(6, out[0].toInt())
        assertTrue(buffer.poll(out, 50_000_000)); assertEquals(7, out[0].toInt())
        assertTrue(buffer.poll(out, 60_000_000)); assertTrue(out.all { it == 0.toShort() })
        assertEquals(1L, buffer.lost)
    }
    @Test fun sequenceWrapAndDuplicateHandling() {
        val buffer = PacketJitterBuffer(); val out = ShortArray(480)
        assertTrue(buffer.offer(Int.MAX_VALUE, ShortArray(480) { 1 }, 0))
        assertFalse(buffer.offer(Int.MAX_VALUE, ShortArray(480) { 2 }, 1))
        assertTrue(buffer.offer(Int.MIN_VALUE, ShortArray(480) { 3 }, 2))
        buffer.poll(out, 30_000_000); assertEquals(1, out[0].toInt())
        buffer.poll(out, 40_000_000); assertEquals(3, out[0].toInt())
    }
    @Test fun latePacketsIncreaseBoundedDelay() {
        val buffer = PacketJitterBuffer(); val out = ShortArray(480)
        buffer.offer(1, ShortArray(480), 0); buffer.poll(out, 30_000_000)
        repeat(10) { assertFalse(buffer.offer(1, out, 40_000_000)) }
        assertEquals(6, buffer.depth)
    }
}
