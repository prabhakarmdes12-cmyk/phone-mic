package com.chiti.phonemic.model

/**
 * Real-time latency evaluation and quality classification.
 */
data class LatencyMetrics(
    val estimatedDelayMs: Int,
    val codec: String,
    val bufferSizeFrames: Int,
    val underrunCount: Long
) {
    val quality: LatencyQuality
        get() = when {
            estimatedDelayMs < 40 -> LatencyQuality.EXCELLENT
            estimatedDelayMs < 80 -> LatencyQuality.GOOD
            estimatedDelayMs < 150 -> LatencyQuality.USABLE
            estimatedDelayMs < 250 -> LatencyQuality.NOTICEABLE
            else -> LatencyQuality.POOR
        }
}

enum class LatencyQuality(val label: String, val colorHex: String) {
    EXCELLENT("EXCELLENT", "#00E676"),
    GOOD("GOOD", "#00E5FF"),
    USABLE("USABLE", "#FFD600"),
    NOTICEABLE("NOTICEABLE", "#FF9100"),
    POOR("HIGH DELAY", "#FF5252")
}
