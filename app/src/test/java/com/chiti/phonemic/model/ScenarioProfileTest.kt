package com.chiti.phonemic.model

import org.junit.Assert.*
import org.junit.Test

class ScenarioProfileTest {

    @Test
    fun allScenarioProfilesHaveValidPropertiesAndBounds() {
        assertTrue("ScenarioProfile.ALL must contain profiles", ScenarioProfile.ALL.isNotEmpty())
        assertEquals("Expected 5 core scenario profiles", 5, ScenarioProfile.ALL.size)

        for (profile in ScenarioProfile.ALL) {
            assertTrue("ID must not be empty for ${profile.name}", profile.id.isNotBlank())
            assertTrue("Title must not be empty for ${profile.name}", profile.title.isNotBlank())
            assertTrue("Emoji must not be empty for ${profile.name}", profile.iconEmoji.isNotBlank())
            assertTrue("Description must not be empty for ${profile.name}", profile.description.isNotBlank())
            assertTrue("Distance tip must not be empty for ${profile.name}", profile.distanceTip.isNotBlank())

            assertTrue(
                "Gain must be within -12dB..+12dB for ${profile.name}",
                profile.defaultGainDb in -12.0f..12.0f
            )

            assertTrue(
                "Recommended preset must exist in VoicePreset.ALL for ${profile.name}",
                VoicePreset.ALL.contains(profile.recommendedPreset)
            )
        }
    }

    @Test
    fun scenarioProfileFromIdLookup() {
        assertEquals(ScenarioProfile.CLASSROOM, ScenarioProfile.fromId("classroom"))
        assertEquals(ScenarioProfile.FITNESS, ScenarioProfile.fromId("fitness"))
        assertEquals(ScenarioProfile.TOUR_GUIDE, ScenarioProfile.fromId("tour_guide"))
        assertEquals(ScenarioProfile.KARAOKE_BUSKER, ScenarioProfile.fromId("karaoke"))
        assertEquals(ScenarioProfile.WAREHOUSE, ScenarioProfile.fromId("warehouse"))

        // Fallback for unknown IDs
        assertEquals(ScenarioProfile.CLASSROOM, ScenarioProfile.fromId("unknown_id"))
        assertEquals(ScenarioProfile.CLASSROOM, ScenarioProfile.fromId(""))
    }

    @Test
    fun voicePresetFromIdLookup() {
        for (preset in VoicePreset.ALL) {
            assertEquals(preset, VoicePreset.fromId(preset.id))
        }

        // Fallback for unknown IDs
        assertEquals(VoicePreset.NATURAL, VoicePreset.fromId(-1))
        assertEquals(VoicePreset.NATURAL, VoicePreset.fromId(999))
    }

    @Test
    fun warehouseScenarioPrefersPushToTalk() {
        assertTrue(ScenarioProfile.WAREHOUSE.preferPtt)
        assertFalse(ScenarioProfile.CLASSROOM.preferPtt)
        assertFalse(ScenarioProfile.FITNESS.preferPtt)
        assertFalse(ScenarioProfile.TOUR_GUIDE.preferPtt)
        assertFalse(ScenarioProfile.KARAOKE_BUSKER.preferPtt)
    }
}
