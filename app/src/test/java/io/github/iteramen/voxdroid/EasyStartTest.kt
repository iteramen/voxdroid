package io.github.iteramen.voxdroid

import org.junit.Assert.*
import org.junit.Test

class EasyStartTest {
    @Test fun aNewSetupGetsBothEndpointsAndModelsButNoCredentials() {
        val after = EasyStart.configuration(ConfigSnapshot(emptyMap(), emptyMap()))
        assertEquals("https://api.groq.com/openai/v1", after.settings["stt_base_url"])
        assertEquals(after.settings["stt_base_url"], after.settings["polish_base_url"])
        assertEquals("whisper-large-v3-turbo", after.settings["stt_model"])
        assertEquals("openai/gpt-oss-20b", after.settings["polish_model"])
        assertEquals(true, after.settings["polish_enabled"])
        assertTrue(after.secrets.isEmpty())
    }

    @Test fun oldCredentialsCannotFollowTheNewEndpointsOrReachAnOldLane() {
        val before = ConfigSnapshot(mapOf("stt_base_url" to "https://old.example/v1",
            "polish_base_url" to "https://old.example/v1", "polish_enabled" to false,
            "lane_base_url_he" to "https://lane.example/v1", "lane_inherit_token_he" to true,
            "lane_base_url_ru" to "https://old.example/v1", "lane_inherit_token_en" to true),
            mapOf("stt_token" to "old-synthetic-speech", "polish_token" to "old-synthetic-cleanup",
                "lane_token_he" to "dedicated-synthetic-lane"))
        val after = EasyStart.configuration(before)
        assertFalse(after.secrets.containsKey("stt_token"))
        assertFalse(after.secrets.containsKey("polish_token"))
        for (code in listOf("he", "ru", "en")) assertEquals(false, after.settings["lane_inherit_token_$code"])
        assertEquals(before.settings["lane_base_url_he"], after.settings["lane_base_url_he"])
        assertEquals(before.secrets["lane_token_he"], after.secrets["lane_token_he"])
        assertEquals("old-synthetic-speech", before.secrets["stt_token"])
    }

    @Test fun unrelatedConfigurationAndNarrationCredentialsSurvive() {
        val before = ConfigSnapshot(mapOf("chip_languages" to "en,he,ru", "explicit_he" to true,
            "stt_prompt" to "Synthetic glossary", "stt_timeout_s" to 90, "polish_min_words" to 4,
            "tts_url_en" to "https://tts.example/v1", "tts_voice_en" to "sample", "trigger_volume" to true,
            "allow_insecure_http" to true, "no_field_mode" to "refuse"), mapOf("tts_token_en" to "synthetic-tts"))
        val after = EasyStart.configuration(before)
        before.settings.forEach { (key, value) -> assertEquals(key, value, after.settings[key]) }
        assertEquals(before.secrets, after.secrets)
    }

    @Test fun applyingAgainStillClearsPreviouslyEnteredDefaultTokens() {
        val once = EasyStart.configuration(ConfigSnapshot(emptyMap(), emptyMap()))
        val after = EasyStart.configuration(once.copy(secrets = mapOf("stt_token" to "synthetic", "polish_token" to "synthetic")))
        assertEquals(once, after)
    }
}
