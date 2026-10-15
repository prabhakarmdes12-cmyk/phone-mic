package com.chiti.phonemic.network

/** Socket-thread only, bounded sequence window with silence loss concealment.
 * 30 ms initial playout, increases to 60 ms on late arrival; decreases after 5 s stable.
 */
class PacketJitterBuffer {
    private val slots = Array(64) { ShortArray(480) }
    private val tags = IntArray(64)
    private val occupied = BooleanArray(64)
    private var initialized = false
    private var expected = 0
    private var startAt = 0L
    private var stable = 0
    var depth = 3; private set
    var lost = 0L; private set
    fun offer(sequence: Int, pcm: ShortArray, nowNanos: Long): Boolean {
        if (!initialized) { initialized = true; expected = sequence; startAt = nowNanos + depth * 10_000_000L }
        val delta = sequence - expected // Int overflow deliberately handles sequence wrap.
        if (delta < 0) { if (depth < 6) { depth++; startAt += 10_000_000L }; stable = 0; return false }
        if (delta >= 64) return false
        val slot = sequence and 63
        if (occupied[slot] && tags[slot] == sequence) return false
        pcm.copyInto(slots[slot]); tags[slot] = sequence; occupied[slot] = true
        return true
    }
    fun poll(output: ShortArray, nowNanos: Long): Boolean {
        if (!initialized || nowNanos < startAt) return false
        val slot = expected and 63
        if (occupied[slot] && tags[slot] == expected) {
            slots[slot].copyInto(output); occupied[slot] = false
            stable++
            if (stable >= 500 && depth > 3) { depth--; stable = 0; startAt -= 10_000_000L }
        } else { output.fill(0); lost++; stable = 0 }
        expected++; startAt += 10_000_000L
        // Never burst an arbitrarily old backlog after scheduler stalls.
        if (nowNanos - startAt > 60_000_000L) startAt = nowNanos + 10_000_000L
        return true
    }
}
