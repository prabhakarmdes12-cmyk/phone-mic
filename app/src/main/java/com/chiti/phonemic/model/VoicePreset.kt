package com.chiti.phonemic.model

/**
 * Standard voice equalization and dynamic processing presets.
 */
enum class VoicePreset(
    val id: Int,
    val title: String,
    val description: String,
    val isPro: Boolean = false
) {
    NATURAL(
        id = 0,
        title = "Natural",
        description = "Clean, uncolored transparency with low rumble cutoff."
    ),
    CLEAR_SPEECH(
        id = 1,
        title = "Clear Speech",
        description = "Enhanced presence (3 kHz) for high vocal intelligibility."
    ),
    LOUD_SPEECH(
        id = 2,
        title = "Loud Speech",
        description = "Heavy compression and peak limiting for noisy markets."
    ),
    WARM(
        id = 3,
        title = "Warm Voice",
        description = "Rich lower-mids for intimate speech, podcast, or radio."
    ),
    OUTDOOR(
        id = 4,
        title = "Outdoor",
        description = "Strong wind cutoff (140 Hz) and ambient rejection."
    ),
    KARAOKE(
        id = 5,
        title = "Karaoke",
        description = "Studio plate reverb and synced vocal echo.",
        isPro = true
    );

    companion object {
        fun fromId(id: Int): VoicePreset = entries.firstOrNull { it.id == id } ?: NATURAL
    }
}
