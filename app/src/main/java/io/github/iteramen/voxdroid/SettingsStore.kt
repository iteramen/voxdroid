package io.github.iteramen.voxdroid

import android.content.Context
import androidx.preference.PreferenceDataStore
import java.util.Locale

internal enum class StoredAs {
    Text,
    /** In the `secrets` file, the one kept off a backup. */
    Secret,
    Whole,
    Fraction,
}

internal const val LANE_TOKEN_PREFIX = "lane_token_"

internal const val LANE_INHERIT_PREFIX = "lane_inherit_token_"

internal fun storedAs(key: String): StoredAs = when (key) {
    "stt_token", "polish_token" -> StoredAs.Secret
    "stt_timeout_s", "polish_min_words", "polish_timeout_s", "volume_hold_ms" -> StoredAs.Whole
    "no_speech_threshold" -> StoredAs.Fraction
    else -> if (key.startsWith(LANE_TOKEN_PREFIX)) StoredAs.Secret else StoredAs.Text
}

internal enum class NumberProblem {
    NotANumber,
    Negative,
    NotAFraction,
    BelowMinimum,
}

internal const val MIN_HOLD_MS = 300

/** [NumberProblem.BelowMinimum] comes before [NumberProblem.Negative], so a negative hold is told the floor. */
internal fun validateNumber(key: String, raw: String): NumberProblem? = when (storedAs(key)) {
    StoredAs.Whole -> when (val value = wholeOrNull(raw)) {
        null -> NumberProblem.NotANumber
        else -> when {
            key == "volume_hold_ms" && value < MIN_HOLD_MS -> NumberProblem.BelowMinimum
            value < 0 -> NumberProblem.Negative
            else -> null
        }
    }
    StoredAs.Fraction -> when (val value = fractionOrNull(raw)) {
        null -> NumberProblem.NotANumber
        else -> when {
            value < 0f -> NumberProblem.Negative
            value > 1f -> NumberProblem.NotAFraction
            else -> null
        }
    }
    StoredAs.Text, StoredAs.Secret -> null
}

internal fun shownWhole(key: String, value: Int): Int =
    if (key == "volume_hold_ms") value.coerceAtLeast(MIN_HOLD_MS) else value

internal fun wholeOrNull(raw: String): Int? = raw.trim().toIntOrNull()

internal fun fractionOrNull(raw: String): Float? = raw.trim().toFloatOrNull()?.takeIf { !it.isNaN() }

/** A stock `EditTextPreference` stores a `String`, which throws `ClassCastException` under an `Int` key; this keeps the typed values and routes tokens to `secrets`. */
internal class SettingsStore(context: Context) : PreferenceDataStore() {

    private val prefs = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    override fun putString(key: String, value: String?) {
        val text = value.orEmpty()
        when (storedAs(key)) {
            StoredAs.Secret -> secrets.edit().putString(key, text).apply()
            StoredAs.Text -> prefs.edit().putString(key, text).apply()
            // The screen refuses unparsable entries; dropping here keeps a String from landing under a numeric key.
            StoredAs.Whole -> wholeOrNull(text)?.let { prefs.edit().putInt(key, it).apply() }
            StoredAs.Fraction -> fractionOrNull(text)?.let { prefs.edit().putFloat(key, it).apply() }
        }
    }

    override fun getString(key: String, defValue: String?): String = when (storedAs(key)) {
        StoredAs.Secret -> secrets.getString(key, defValue).orEmpty()
        StoredAs.Text -> prefs.getString(key, defValue ?: unsetDefault(key)).orEmpty()
        StoredAs.Whole -> typed(defValue) { shownWhole(key, prefs.getInt(key, wholeOrNull(defValue.orEmpty()) ?: 0)).toString() }
        StoredAs.Fraction -> typed(defValue) { prefs.getFloat(key, fractionOrNull(defValue.orEmpty()) ?: 0f).toString() }
    }

    private fun unsetDefault(key: String): String? =
        if (key == "chip_languages") defaultLanguageCodes(Locale.getDefault()) else null

    /** A hand-written `String` under a numeric key throws on the typed read; show the default so the screen can repair it. */
    private inline fun typed(defValue: String?, read: () -> String): String =
        try {
            read()
        } catch (wrongType: ClassCastException) {
            defValue.orEmpty()
        }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    private companion object {
        const val SETTINGS_FILE = "voxdroid"
        const val SECRETS_FILE = "secrets"
    }
}
