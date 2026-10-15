package com.chiti.phonemic.audio

/** Bounded SPSC queue. Volatile indices publish samples; producer never advances reader.
 * One producer and one consumer only. Overflow rejects the entire write (no stale overwrite).
 * Construct before audio starts. Never reset while either endpoint is running.
 */
class PcmRingBuffer(capacity: Int = 65536) {
    private val data = ShortArray(capacity)
    private val mask = capacity - 1
    @Volatile private var written = 0L
    @Volatile private var read = 0L
    @Volatile var droppedSamples = 0L; private set
    init { require(capacity > 0 && capacity and mask == 0) }
    fun offer(source: ShortArray, count: Int): Boolean {
        val w = written
        if (count > data.size - (w - read)) { droppedSamples += count; return false }
        for (i in 0 until count) data[((w + i).toInt()) and mask] = source[i]
        written = w + count
        return true
    }
    fun poll(target: ShortArray, count: Int = target.size): Int {
        val r = read
        val n = minOf(count, (written - r).toInt())
        for (i in 0 until n) target[i] = data[((r + i).toInt()) and mask]
        read = r + n
        return n
    }
    fun available(): Int = (written - read).toInt()
}
