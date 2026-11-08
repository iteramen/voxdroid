package io.github.iteramen.voxdroid

import android.content.Context

internal class ReadAloudSettings(context: Context) {
    private val prefs = context.getSharedPreferences("voxdroid", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    // Consent shares the backup-excluded file, but is excluded from portable settings export too.
    val enabled: Boolean get() = secrets.getBoolean("tts_enabled", false)
    private fun text(key: String, default: String = "") = typed(prefs.getString(key, default))
    private fun token(code: String) = typed(secrets.getString("tts_token_$code", ""))
    fun snapshot() = ReadAloudConfig(enabled,
        TtsEndpoint(text("tts_url_en"), text("tts_model_en", "kokoro"), text("tts_voice_en", "af_bella"), token("en")),
        listOf("he", "ru").associateWith { code -> TtsLane(text("tts_url_$code"), text("tts_model_$code"),
            text("tts_voice_$code"), token(code), prefs.getBoolean("tts_inherit_token_$code", false)) },
        prefs.getFloat("tts_speed", 1f), prefs.getInt("tts_timeout_s", 30),
        prefs.getBoolean("allow_insecure_http", false))
    val x: Int? get() = if (prefs.contains("read_glyph_x")) prefs.getInt("read_glyph_x", 0) else null
    val y: Int? get() = if (prefs.contains("read_glyph_y")) prefs.getInt("read_glyph_y", 0) else null
    fun position(x: Int, y: Int) { prefs.edit().putInt("read_glyph_x", x).putInt("read_glyph_y", y).apply() }
}
