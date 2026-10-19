package com.chiti.phonemic.network

/** No ByteBuffer wrappers or temporary collections on packet encode/decode paths. */
object AudioPacketCodec {
    const val AUDIO_SIZE = 972
    const val HELLO_SIZE = 72
    fun intAt(data: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 0..3) result = (result shl 8) or (data[offset + i].toInt() and 255)
        return result
    }
    fun putInt(data: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) data[offset + i] = (value ushr (24 - i * 8)).toByte()
    }
    fun longAt(data: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) result = (result shl 8) or (data[offset + i].toLong() and 255)
        return result
    }
    fun encode(data: ByteArray, sequence: Int, timestamp: Long, samples: ShortArray) {
        putInt(data, 0, sequence)
        for (i in 0..7) data[4 + i] = (timestamp ushr (56 - 8 * i)).toByte()
        for (i in 0 until 480) {
            data[12 + i * 2] = samples[i].toByte(); data[13 + i * 2] = (samples[i].toInt() shr 8).toByte()
        }
    }
    fun decode(data: ByteArray, samples: ShortArray) {
        for (i in 0 until 480) samples[i] = ((data[12 + i * 2].toInt() and 255) or (data[13 + i * 2].toInt() shl 8)).toShort()
    }
    fun hello(name: String): ByteArray = ByteArray(HELLO_SIZE).also { data ->
        putInt(data, 0, 0x504d4943) // PMIC; optional metadata, audio stays backward compatible.
        name.take(32).toByteArray(Charsets.UTF_8).copyInto(data, 4, 0, minOf(64, name.take(32).toByteArray(Charsets.UTF_8).size))
    }
    fun deviceName(data: ByteArray): String = String(data, 4, 64, Charsets.UTF_8)
        .trimEnd('\u0000').filter { !it.isISOControl() }.take(48).ifBlank { "Wireless mic" }
}
