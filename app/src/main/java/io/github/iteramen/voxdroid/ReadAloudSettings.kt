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
    fun anchor(space: ControlSpace): ControlAnchor {
        if (prefs.contains("read_anchor_horizontal_dp") && prefs.contains("read_anchor_vertical_dp")) {
            val horizontal = prefs.getFloat("read_anchor_horizontal_dp", 0f)
            val vertical = prefs.getFloat("read_anchor_vertical_dp", 0f)
            if (horizontal.isFinite() && horizontal >= 0 && vertical.isFinite() && vertical >= 0) {
                return ControlAnchor(prefs.getBoolean("read_anchor_right", true),
                    prefs.getBoolean("read_anchor_bottom", false), horizontal, vertical)
            }
        }
        // One-time migration of the old raw coordinates in the first visible orientation.
        val legacy = ControlPosition(prefs.getInt("read_glyph_x", space.maxX),
            prefs.getInt("read_glyph_y", space.height / 3))
        return rememberControl(legacy, space).also(::position)
    }
    fun position(anchor: ControlAnchor) {
        prefs.edit().putBoolean("read_anchor_right", anchor.right).putBoolean("read_anchor_bottom", anchor.bottom)
            .putFloat("read_anchor_horizontal_dp", anchor.horizontalDp).putFloat("read_anchor_vertical_dp", anchor.verticalDp)
            .remove("read_glyph_x").remove("read_glyph_y").apply()
    }
}
