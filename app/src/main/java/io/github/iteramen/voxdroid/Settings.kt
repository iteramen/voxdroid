package io.github.iteramen.voxdroid

import android.content.Context
import android.util.Log
import java.util.Locale

/** A free-text value as typed on a phone keyboard, which may add a space after the last word. */
internal fun typed(raw: String?): String = raw.orEmpty().trim()

internal const val HAPTIC_CUES_DEFAULT = true

internal const val ALLOW_INSECURE_HTTP_DEFAULT = false

/** Two app-private files: `voxdroid`, and `secrets` for the tokens alone, the file `android:dataExtractionRules` keeps off a backup. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    val sttBaseUrl: String get() = prefs.getString("stt_base_url", "").orEmpty()

    val allowInsecureHttp: Boolean get() = prefs.getBoolean("allow_insecure_http", ALLOW_INSECURE_HTTP_DEFAULT)

    val sttModel: String get() = typed(prefs.getString("stt_model", "whisper-1"))

    val sttToken: String get() = typed(secrets.getString("stt_token", ""))

    val sttPrompt: String get() = typed(prefs.getString("stt_prompt", ""))

    val sttTimeoutS: Int get() = prefs.getInt("stt_timeout_s", DEFAULT_TIMEOUT_S)

    val noSpeechThreshold: Float get() = prefs.getFloat("no_speech_threshold", DEFAULT_THRESHOLD)

    val polishEnabled: Boolean get() = prefs.getBoolean("polish_enabled", true)

    /** Empty behaves as disabled. */
    val polishBaseUrl: String get() = prefs.getString("polish_base_url", "").orEmpty()

    val polishModel: String get() = typed(prefs.getString("polish_model", ""))

    val polishToken: String get() = typed(secrets.getString("polish_token", ""))

    val polishMinWords: Int get() = prefs.getInt("polish_min_words", 0)

    val polishTimeoutS: Int get() = prefs.getInt("polish_timeout_s", DEFAULT_POLISH_TIMEOUT_S)

    val capitalizeFirstWord: Boolean get() = prefs.getBoolean("capitalize_first_word", true)

    val stripTerminalPeriod: Boolean get() = prefs.getBoolean("strip_terminal_period", true)

    val triggerVolume: Boolean get() = prefs.getBoolean("trigger_volume", true)

    val triggerButton: Boolean get() = prefs.getBoolean("trigger_button", true)

    val allowDuringCall: Boolean get() = prefs.getBoolean("allow_during_call", false)

    val hapticCues: Boolean get() = prefs.getBoolean("haptic_cues", HAPTIC_CUES_DEFAULT)

    val volumeHoldMs: Int get() = prefs.getInt("volume_hold_ms", DEFAULT_HOLD_MS).coerceAtLeast(MIN_HOLD_MS)

    val noFieldMode: NoFieldMode get() = NoFieldMode.parse(prefs.getString("no_field_mode", "refuse").orEmpty())

    fun isExplicit(code: String): Boolean = prefs.getBoolean("explicit_$code", code == "he")

    var pillSaved: Saved?
        get() = if (prefs.contains("pill_bottom_above_keyboard")) Saved(prefs.getInt("pill_bottom_above_keyboard", 0)) else null
        set(value) {
            val edit = prefs.edit()
            edit.remove("pill_x").remove("pill_above_keyboard")
            if (value == null) edit.remove("pill_bottom_above_keyboard") else edit.putInt("pill_bottom_above_keyboard", value.aboveKeyboard)
            edit.apply()
        }

    val chipLanguages: List<String>
        get() = languageCodes(prefs.getString("chip_languages", defaultLanguageCodes(Locale.getDefault())).orEmpty(), ::warn)

    fun laneBaseUrl(code: String): String = prefs.getString("lane_base_url_$code", "").orEmpty()

    fun laneToken(code: String): String = typed(secrets.getString(LANE_TOKEN_PREFIX + code, ""))

    /** `null` while never chosen: [laneToken] in `Endpoints.kt` then decides by whether the lane shares the STT endpoint's server. */
    fun laneInheritsToken(code: String): Boolean? =
        prefs.takeIf { it.contains(LANE_INHERIT_PREFIX + code) }?.getBoolean(LANE_INHERIT_PREFIX + code, false)

    /** Only the dropped code is logged, never a setting value. */
    private fun warn(line: String) {
        Log.w(TAG, line)
    }

    private companion object {
        const val SETTINGS_FILE = "voxdroid"
        const val SECRETS_FILE = "secrets"
        const val DEFAULT_TIMEOUT_S = 30
        const val DEFAULT_POLISH_TIMEOUT_S = 20
        const val DEFAULT_THRESHOLD = 0.3f
        const val DEFAULT_HOLD_MS = 500
        const val TAG = "voxdroid"
    }
}
