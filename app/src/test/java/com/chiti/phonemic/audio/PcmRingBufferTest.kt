package com.chiti.phonemic.audio

import org.junit.Assert.*
import org.junit.Test

class PcmRingBufferTest {
    @Test fun wrapsWithoutOverwritingUnreadSamples() {
        val queue = PcmRingBuffer(8)
        val pcm = shortArrayOf(1,2,3,4,5,6)
        assertTrue(queue.offer(pcm, 6)); assertFalse(queue.offer(pcm, 6))
        val output = ShortArray(6)
        assertEquals(6, queue.poll(output)); assertArrayEquals(pcm, output)
        assertTrue(queue.offer(pcm, 6)); assertEquals(6, queue.poll(output)); assertArrayEquals(pcm, output)
        assertEquals(6L, queue.droppedSamples)
    }
    @Test fun concurrentProducerConsumerKeepsOrder() {
        val queue = PcmRingBuffer(1024)
        val producer = Thread {
            val sample = ShortArray(1)
            for (i in 0 until 100000) {
                sample[0] = i.toShort()
                while (!queue.offer(sample, 1)) Thread.yield()
            }
        }
        producer.start()
        val sample = ShortArray(1)
        for (i in 0 until 100000) {
            while (queue.poll(sample, 1) == 0) Thread.yield()
            assertEquals(i.toShort(), sample[0])
        }
        producer.join()
    }
}
