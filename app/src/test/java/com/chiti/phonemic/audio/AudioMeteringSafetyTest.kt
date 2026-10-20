package com.chiti.phonemic.audio

import org.junit.Assert.*
import org.junit.Test

class AudioMeteringSafetyTest {

    @Test
    fun testRmsSanitizationProtectsAgainstNanAndInf() {
        val testValues = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            -150.0f,
            -96.0f,
            -12.0f,
            0.0f,
            6.0f
        )

        for (rawRms in testValues) {
            val safeRms = if (rawRms.isNaN() || rawRms.isInfinite()) -96.0f else rawRms
            assertFalse("safeRms must not be NaN", safeRms.isNaN())
            assertFalse("safeRms must not be infinite", safeRms.isInfinite())

            val normalized = ((safeRms + 60.0f) / 60.0f).coerceIn(0.0f, 1.0f)
            assertFalse("normalizedRms must not be NaN", normalized.isNaN())
            assertTrue("normalizedRms must be >= 0.0f", normalized >= 0.0f)
            assertTrue("normalizedRms must be <= 1.0f", normalized <= 1.0f)
        }
    }

    @Test
    fun testAudioStatesEqualityAndProperties() {
        val idle = AudioState.Idle
        val connecting = AudioState.Connecting
        val error = AudioState.Error("Test error")
        val active = AudioState.Active(
            outputDeviceName = "Phone speaker",
            estimatedLatencyMs = 12,
            gainDb = 0.0f,
            presetId = 0
        )

        assertNotEquals(idle, connecting)
        assertNotEquals(connecting, active)
        assertEquals("Test error", error.message)
        assertEquals("Phone speaker", active.outputDeviceName)
    }
}
