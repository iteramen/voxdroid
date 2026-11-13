package io.github.iteramen.voxdroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ConfigBackupTest {
    private val password = "synthetic passphrase only".toCharArray()
    private val snapshot = ConfigSnapshot(mapOf("stt_base_url" to "https://example.invalid/v1",
        "stt_timeout_s" to 30, "no_speech_threshold" to 0.3f, "polish_enabled" to true,
        "chip_languages" to "he,en", "lane_inherit_token_he" to false,
        "lane_base_url_he" to "https://lane.invalid/v1", "explicit_he" to true,
        "stt_prompt" to "שלום\nfixture", "no_field_mode" to "clipboard"),
        mapOf("stt_token" to "synthetic-stt", "polish_token" to "synthetic-cleanup", "lane_token_he" to "synthetic-lane"))

    @Test fun portableRoundTripPreservesTypesUnicodeAndEveryToken() {
        val encrypted = ConfigBackup.encrypt(snapshot, password)
        assertEquals(snapshot, ConfigBackup.decrypt(encrypted, password))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("synthetic-stt"))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("example.invalid"))
    }

    @Test fun floatingCornerSurvivesPortableBackupWithLegacyPositionsStillAccepted() {
        val corner = mapOf<String, Any>("read_anchor_right" to true, "read_anchor_bottom" to false,
            "read_anchor_horizontal_dp" to 12f, "read_anchor_vertical_dp" to 24f)
        val source = ConfigSnapshot(corner, emptyMap())
        assertEquals(source, ConfigBackup.snapshot(corner, emptyMap<String, Any>()))
        assertEquals(source, ConfigBackup.decrypt(ConfigBackup.encrypt(source, password), password))
        ConfigBackup.validate(ConfigSnapshot(mapOf("read_glyph_x" to 10, "read_glyph_y" to 20), emptyMap()))
    }

    @Test fun invalidOrIncompleteCornerBackupIsRefused() {
        val corner = mapOf<String, Any>("read_anchor_right" to true, "read_anchor_bottom" to false,
            "read_anchor_horizontal_dp" to 12f, "read_anchor_vertical_dp" to 24f)
        for (value in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, "12", 12)) {
            reject { ConfigBackup.validate(ConfigSnapshot(corner + ("read_anchor_horizontal_dp" to value), emptyMap())) }
        }
        reject { ConfigBackup.validate(ConfigSnapshot(corner - "read_anchor_right", emptyMap())) }
    }

    @Test fun identicalExportsUseDifferentSaltAndNonce() {
        assertFalse(ConfigBackup.encrypt(snapshot, password).contentEquals(ConfigBackup.encrypt(snapshot, password)))
    }

    @Test fun wrongPasswordDoesNotReturnConfiguration() {
        reject { ConfigBackup.decrypt(ConfigBackup.encrypt(snapshot, password), "wrong passphrase".toCharArray()) }
    }

    @Test fun changedHeaderCiphertextAndTagAreRejected() {
        val bytes = ConfigBackup.encrypt(snapshot, password)
        for (position in listOf(0, 7, 11, 12, 28, 40, bytes.lastIndex)) {
            val changed = bytes.clone()
            changed[position] = (changed[position].toInt() xor 1).toByte()
            reject { ConfigBackup.decrypt(changed, password) }
        }
    }

    @Test fun truncatedAndOversizedFilesAreRejected() {
        val bytes = ConfigBackup.encrypt(snapshot, password)
        for (length in listOf(0, 39, 55, bytes.size - 1)) reject { ConfigBackup.decrypt(bytes.copyOf(length), password) }
        reject { ConfigBackup.readBounded(ByteArrayInputStream(ByteArray(ConfigBackup.MAX_BYTES + 1))) }
        assertArrayEquals(bytes, ConfigBackup.readBounded(ByteArrayInputStream(bytes)))
    }

    @Test fun invalidTypesRangesAndUnknownKeysCannotBeImported() {
        for ((key, value) in listOf("stt_timeout_s" to "30", "stt_token" to "token-in-wrong-file",
            "voice_captions" to true, "remember_voice_messages" to true, "unknown" to "value",
            "volume_hold_ms" to 100, "cue_volume" to 101, "no_speech_threshold" to Float.NaN,
            "no_field_mode" to "send", "lane_base_url_../../file" to "bad")) {
            reject { ConfigBackup.encrypt(ConfigSnapshot(mapOf(key to value), emptyMap()), password) }
        }
        reject { ConfigBackup.encrypt(ConfigSnapshot(emptyMap(), mapOf("stt_base_url" to "bad")), password) }
    }

    @Test fun snapshotNeverExportsConsentOrUnrelatedFilesAndKeepsAllConfigurationKeys() {
        val selected = ConfigBackup.snapshot(snapshot.settings + mapOf("voice_captions" to true, "unrelated" to "private"),
            snapshot.secrets + mapOf("unknown" to "private"))
        assertEquals(snapshot, selected)
        for (key in listOf("trigger_volume", "trigger_button", "allow_during_call", "audio_cues", "haptic_cues",
            "capitalize_first_word", "strip_terminal_period", "allow_insecure_http")) assertTrue(ConfigBackup.allowed(key, false))
    }

    @Test fun restoreRemovesAbsentConfigurationAndChangesTokensOnlyAfterEndpoints() {
        var current = ConfigSnapshot(mapOf("stt_base_url" to "https://old.invalid/v1", "stt_prompt" to "old"),
            mapOf("stt_token" to "old-synthetic-token"))
        val stages = mutableListOf<ConfigSnapshot>()
        restoreConfig(snapshot, { current }, { current = current.copy(settings = it); stages.add(current); true },
            { current = current.copy(secrets = it); stages.add(current); true })
        assertEquals(snapshot, current)
        assertEquals(emptyMap<String, Any>(), stages[0].secrets)
        assertEquals(emptyMap<String, Any>(), stages[1].secrets)
        assertEquals(snapshot.settings, stages[1].settings)
    }

    @Test fun writeFailureRollsBackOrLeavesTokensEmptyAndCanBeRetried() {
        val before = ConfigSnapshot(mapOf("stt_base_url" to "https://old.invalid/v1"), mapOf("stt_token" to "old"))
        var current = before
        var fail = true
        reject { restoreConfig(snapshot, { current }, { current = current.copy(settings = it); true }, {
            current = current.copy(secrets = it)
            if (it.isNotEmpty() && fail) { fail = false; false } else true
        }) }
        assertEquals(before, current)
        restoreConfig(snapshot, { current }, { current = current.copy(settings = it); true },
            { current = current.copy(secrets = it); true })
        assertEquals(snapshot, current)
    }

    @Test fun validationCompletesBeforeFirstPreferenceMutation() {
        var writes = 0
        reject { restoreConfig(ConfigSnapshot(mapOf("stt_timeout_s" to "bad"), emptyMap()), { snapshot },
            { writes++; true }, { writes++; true }) }
        assertEquals(0, writes)
    }

    @Test fun passwordLengthIsBounded() {
        reject { ConfigBackup.encrypt(snapshot, "short".toCharArray()) }
        reject { ConfigBackup.encrypt(snapshot, CharArray(1025) { 'x' }) }
    }

    private fun reject(action: () -> Any?) {
        try { action(); fail("Expected a refused backup operation") } catch (expected: Exception) { /* No private exception text. */ }
    }
}
