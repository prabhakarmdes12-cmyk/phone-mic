package com.chiti.phonemic.model

/**
 * Purpose-built scenario profiles tailored for specific acoustics and operational environments.
 */
enum class ScenarioProfile(
    val id: String,
    val title: String,
    val iconEmoji: String,
    val description: String,
    val recommendedPreset: VoicePreset,
    val defaultGainDb: Float,
    val autoDucking: Boolean,
    val feedbackSuppression: Boolean,
    val distanceTip: String,
    val preferPtt: Boolean = false
) {
    CLASSROOM(
        id = "classroom",
        title = "Classroom",
        iconEmoji = "🎓",
        description = "Enhanced 3kHz presence for clear student comprehension across large rooms.",
        recommendedPreset = VoicePreset.CLEAR_SPEECH,
        defaultGainDb = 3.0f,
        autoDucking = true,
        feedbackSuppression = true,
        distanceTip = "Hold 8–12 cm from mouth for crisp lectures without popping."
    ),
    FITNESS(
        id = "fitness",
        title = "Fitness & Gym",
        iconEmoji = "⚡",
        description = "Punchy, compressed projection that cuts through loud workout music.",
        recommendedPreset = VoicePreset.LOUD_SPEECH,
        defaultGainDb = 6.0f,
        autoDucking = true,
        feedbackSuppression = true,
        distanceTip = "Chest-level or collar mount (12–18 cm) against loud gym beats."
    ),
    TOUR_GUIDE(
        id = "tour_guide",
        title = "Tour Guide",
        iconEmoji = "🏛️",
        description = "Wind-filter cutoff (140Hz) and ambient noise rejection for open-air groups.",
        recommendedPreset = VoicePreset.OUTDOOR,
        defaultGainDb = 4.0f,
        autoDucking = true,
        feedbackSuppression = true,
        distanceTip = "Handheld 5–10 cm, point microphone away from direct wind."
    ),
    KARAOKE_BUSKER(
        id = "karaoke",
        title = "Singing & Karaoke",
        iconEmoji = "🎤",
        description = "Concert plate reverb, slapback vocal delay, and warm backing balance.",
        recommendedPreset = VoicePreset.KARAOKE,
        defaultGainDb = 0.0f,
        autoDucking = false,
        feedbackSuppression = true,
        distanceTip = "Sing 3–5 cm from microphone with consistent breath control."
    ),
    WAREHOUSE(
        id = "warehouse",
        title = "Warehouse / Dispatch",
        iconEmoji = "🏭",
        description = "Industrial peak limiter with high gain and instant Push-To-Talk dispatch.",
        recommendedPreset = VoicePreset.LOUD_SPEECH,
        defaultGainDb = 8.0f,
        autoDucking = true,
        feedbackSuppression = true,
        distanceTip = "Hold close (2–5 cm) and speak firmly over industrial noise.",
        preferPtt = true
    );

    companion object {
        val ALL: List<ScenarioProfile> = values().toList()
        fun fromId(id: String): ScenarioProfile = ALL.firstOrNull { it.id == id } ?: CLASSROOM
    }
}
