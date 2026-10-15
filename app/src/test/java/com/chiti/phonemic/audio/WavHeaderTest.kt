package com.chiti.phonemic.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavHeaderTest {
    @Test fun canonicalHeaderHasCorrectSizesAndFormat() {
        val bytes = WavHeader.bytes(96000)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(96036, header.getInt(4)); assertEquals(96000, header.getInt(40))
        assertEquals(1, header.getShort(20).toInt()); assertEquals(1, header.getShort(22).toInt())
        assertEquals(48000, header.getInt(24)); assertEquals(96000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt()); assertEquals(16, header.getShort(34).toInt())
    }
    @Test fun emptyFileIsValid() { assertEquals(36, ByteBuffer.wrap(WavHeader.bytes(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(4)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOddLength() { WavHeader.bytes(1) }
    @Test(expected = IllegalArgumentException::class) fun rejectsRiffOverflow() { WavHeader.bytes(0x1_0000_0000) }
}
