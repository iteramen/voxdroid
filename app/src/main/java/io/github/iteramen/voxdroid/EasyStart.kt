package io.github.iteramen.voxdroid

/** An explicit preset, never an install/upgrade default. No account, token or network operation. */
internal object EasyStart {
    const val BASE_URL = "https://api.groq.com/openai/v1"
    const val SPEECH_MODEL = "whisper-large-v3-turbo"
    const val CLEANUP_MODEL = "openai/gpt-oss-20b"
    const val GUIDE_URL = "https://iteramen.github.io/voxdroid/easy-start"

    fun configuration(before: ConfigSnapshot): ConfigSnapshot {
        ConfigBackup.validate(before)
        val settings = before.settings.toMutableMap()
        settings.putAll(mapOf("stt_base_url" to BASE_URL, "stt_model" to SPEECH_MODEL,
            "polish_base_url" to BASE_URL, "polish_model" to CLEANUP_MODEL, "polish_enabled" to true))
        // A later pasted Groq credential must not flow to an older language server. Preserve its
        // URL and dedicated token, but require explicit consent again for default-token sharing.
        before.settings.keys.filter { it.startsWith("lane_base_url_") || it.startsWith("lane_inherit_token_") }
            .map { it.removePrefix("lane_base_url_").removePrefix("lane_inherit_token_") }
            .forEach { settings["lane_inherit_token_$it"] = false }
        return ConfigSnapshot(settings, before.secrets - setOf("stt_token", "polish_token")).also(ConfigBackup::validate)
    }
}
