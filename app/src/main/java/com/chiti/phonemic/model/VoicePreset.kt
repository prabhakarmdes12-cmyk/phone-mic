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
        description = "Concert plate reverb and synced vocal slapback echo for singing.",
        isPro = false
    ),
    STUDIO_VOCAL(
        id = 6,
        title = "Studio Vocal",
        description = "Warm acoustic presence and silky room ambience for vocals.",
        isPro = false
    ),
    CHORUS_MOD(
        id = 7,
        title = "Vocal Chorus",
        description = "Lush voice modulation and stereo-doubling shimmer.",
        isPro = false
    ),
    ROBOT_MOD(
        id = 8,
        title = "Robot Mod",
        description = "Fun metallic ring modulation creative vocal effect.",
        isPro = false
    );

    companion object {
        fun fromId(id: Int): VoicePreset = entries.firstOrNull { it.id == id } ?: NATURAL
    }
}
