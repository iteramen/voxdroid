package io.github.iteramen.voxdroid

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** One section page: the `res/xml/section_*.xml` named by [ARG_XML] over [SettingsStore]; each binder skips a row the page lacks, so one class serves every page. */
class SectionFragment : PreferenceFragmentCompat() {

    private val urlSummary = Preference.SummaryProvider<EditTextPreference> { preference ->
        urlSummary(
            preference.text.orEmpty(), Settings(requireContext()).allowInsecureHttp, getString(R.string.settings_not_set),
            getString(R.string.settings_cleartext), getString(R.string.settings_url_blocked),
        )
    }

    private val tokenSummary = Preference.SummaryProvider<EditTextPreference> { preference ->
        getString(if (preference.text.isNullOrBlank()) R.string.settings_not_set else R.string.settings_token_stored)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Before the resource: the manager hands its store to every preference as that preference
        // inflates, and a box inflated without one reads and writes the default file untyped.
        preferenceManager.preferenceDataStore = SettingsStore(requireContext())
        setPreferencesFromResource(requireArguments().getInt(ARG_XML), rootKey)

        baseUrl(KEY_STT_BASE_URL, EndpointKind.Stt)
        insecureSwitch()
        token(KEY_STT_TOKEN)
        multiLine(KEY_STT_PROMPT)
        number(KEY_STT_TIMEOUT)
        number(KEY_NO_SPEECH_THRESHOLD)

        languageRows(Settings(requireContext()).chipLanguages)
        findPreference<EditTextPreference>(KEY_CHIP_LANGUAGES)?.setOnPreferenceChangeListener { _, value ->
            // A code that leaves loses its row but keeps what its page stored; a non-ISO code is dropped as Settings drops it.
            languageRows(languageCodes(value as String) { })
            true
        }
        requireArguments().getString(ARG_CODE)?.let(::languagePage)

        baseUrl(KEY_POLISH_BASE_URL, EndpointKind.Polish)
        token(KEY_POLISH_TOKEN)
        number(KEY_POLISH_MIN_WORDS)
        number(KEY_POLISH_TIMEOUT)
        number(KEY_VOLUME_HOLD)
        noFieldRows()
    }

    /** Non-persistent rows, so the store is written on a tap only; the checked state is set after inflation because attaching resets it. */
    private fun noFieldRows() {
        val rows = NoFieldMode.entries.mapNotNull { mode ->
            findPreference<CheckBoxPreference>(NO_FIELD_ROW_PREFIX + mode.stored)?.let { mode to it }
        }
        val store = preferenceManager.preferenceDataStore ?: return
        fun stored() = store.getString(KEY_NO_FIELD_MODE, NoFieldMode.Refuse.stored).orEmpty()
        for ((mode, row) in rows) {
            row.isChecked = noFieldChecked(mode, stored())
            row.setOnPreferenceChangeListener { _, _ ->
                val write = noFieldTap(mode, stored()) ?: return@setOnPreferenceChangeListener false
                store.putString(KEY_NO_FIELD_MODE, write)
                rows.forEach { (other, otherRow) -> if (other != mode) otherRow.isChecked = false }
                true
            }
        }
    }

    private fun baseUrl(key: String, kind: EndpointKind) {
        bindBaseUrl(findPreference(key) ?: return, kind)
    }

    private fun bindBaseUrl(preference: EditTextPreference, kind: EndpointKind) {
        preference.setSummaryProvider(urlSummary)
        preference.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        preference.setOnPreferenceChangeListener { _, value ->
            accept(
                when (validateBaseUrl(value as String, Settings(requireContext()).allowInsecureHttp, kind)) {
                    null -> null
                    BaseUrlProblem.NotAnHttpUrl -> getString(R.string.settings_url_not_http)
                    BaseUrlProblem.PathAlreadyThere -> getString(R.string.settings_url_has_path, kind.path)
                    BaseUrlProblem.PortOutOfRange -> getString(R.string.settings_url_port)
                    BaseUrlProblem.InsecureHttp -> getString(R.string.settings_url_insecure)
                },
            )
        }
    }

    /** Turning it on asks first and only Turn on stores it; both paths write the switch here so the STT URL summary redraws. */
    private fun insecureSwitch() {
        val switch = findPreference<SwitchPreferenceCompat>(KEY_ALLOW_INSECURE_HTTP) ?: return
        fun set(on: Boolean) {
            switch.isChecked = on
            findPreference<EditTextPreference>(KEY_STT_BASE_URL)?.setSummaryProvider(urlSummary)
        }
        switch.setOnPreferenceChangeListener { _, value ->
            if (value == true) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_insecure_confirm_title)
                    .setMessage(R.string.settings_insecure_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.settings_insecure_turn_on) { _, _ -> set(true) }
                    .show()
            } else {
                set(false)
            }
            false
        }
    }

    private fun token(key: String) {
        bindToken(findPreference(key) ?: return)
    }

    private fun bindToken(preference: EditTextPreference) {
        preference.setSummaryProvider(tokenSummary)
        preference.dialogLayoutResource = R.layout.preference_dialog_token
    }

    private fun multiLine(key: String) {
        findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }

    private fun number(key: String) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        val fraction = storedAs(key) == StoredAs.Fraction
        preference.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER or
                if (fraction) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
        }
        preference.setOnPreferenceChangeListener { _, value ->
            // An entry that is not a number (`0,5` from a comma-decimal keyboard) is told about the dot; one out of range is told the range.
            accept(
                when (validateNumber(key, value as String)) {
                    null -> null
                    NumberProblem.NotANumber -> getString(R.string.settings_number_not_a_number)
                    NumberProblem.BelowMinimum -> getString(R.string.settings_hold_minimum, MIN_HOLD_MS)
                    NumberProblem.Negative, NumberProblem.NotAFraction ->
                        getString(if (fraction) R.string.settings_number_fraction else R.string.settings_number_whole)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a language's page, whose edits are in the store and not in these rows.
        languageSummaries()
    }

    private fun languageRows(codes: List<String>) {
        val category = findPreference<PreferenceCategory>(KEY_CATEGORY_LANGUAGES) ?: return
        category.removeAll()
        codes.forEach { code ->
            category.addPreference(Preference(requireContext()).apply {
                key = LANGUAGE_ROW_PREFIX + code
                title = languageRowTitle(code)
                isPersistent = false
                setOnPreferenceClickListener {
                    startActivity(SectionActivity.intent(requireContext(), R.xml.section_language, languageRowTitle(code), code))
                    true
                }
            })
        }
        languageSummaries()
    }

    private fun languageSummaries() {
        val category = findPreference<PreferenceCategory>(KEY_CATEGORY_LANGUAGES) ?: return
        val settings = Settings(requireContext())
        for (index in 0 until category.preferenceCount) {
            val row = category.getPreference(index)
            val code = row.key.removePrefix(LANGUAGE_ROW_PREFIX)
            row.summary = languageRowSummary(
                explicit = settings.isExplicit(code),
                laneUrl = settings.laneBaseUrl(code),
                sentAs = getString(R.string.settings_explicit_on, code),
                autoDetect = getString(R.string.settings_explicit_off),
                notSet = getString(R.string.settings_not_set),
            )
        }
    }

    private fun languagePage(code: String) {
        preferenceScreen.addPreference(SwitchPreferenceCompat(requireContext()).apply {
            key = "explicit_$code"
            title = getString(R.string.settings_use_keyboard_language)
            // Set before the row is attached, because attaching is what reads the stored value.
            setDefaultValue(code == "he")
            summaryOn = getString(R.string.settings_explicit_on, code)
            summaryOff = getString(R.string.settings_explicit_off)
        })
        laneRows(code).forEach(preferenceScreen::addPreference)
    }

    private fun laneRows(code: String): List<Preference> {
        val settings = Settings(requireContext())
        val url = laneRow(code)
        val own = laneTokenRow(code)
        // From the store, not from the rows above: a row's `text` is null until it is attached.
        val shown = laneSwitch(
            settings.sttBaseUrl, settings.sttToken, settings.laneBaseUrl(code),
            settings.laneToken(code), settings.laneInheritsToken(code),
        )
        val inherit = SwitchPreferenceCompat(requireContext()).apply {
            key = LANE_INHERIT_PREFIX + code
            title = getString(R.string.settings_lane_inherit, code)
            isSingleLineTitle = false
            // Not persistent: the state shown while the key is unset is computed and must not become a stored choice.
            // Attaching a switch sets it to its default value, so it opens on setDefaultValue, not on an earlier isChecked.
            isPersistent = false
            setDefaultValue(shown.checked)
            isEnabled = shown.enabled
            setSummaryProvider(Preference.SummaryProvider<SwitchPreferenceCompat> {
                getString(
                    when {
                        !isEnabled -> R.string.settings_lane_inherit_own
                        isChecked -> R.string.settings_lane_inherit_on
                        else -> R.string.settings_lane_inherit_off
                    },
                )
            })
            setOnPreferenceChangeListener { _, value ->
                preferenceManager.preferenceDataStore?.putBoolean(key, value as Boolean)
                true
            }
        }
        // For an edit once the rows are attached, when `isChecked` and `isEnabled` stick.
        fun refresh(laneUrl: String, ownToken: String) {
            val now = laneSwitch(
                settings.sttBaseUrl, settings.sttToken, laneUrl, ownToken, settings.laneInheritsToken(code),
            )
            inherit.isChecked = now.checked
            inherit.isEnabled = now.enabled
        }
        chain(url) { refresh(it, own.text.orEmpty()) }
        chain(own) { refresh(url.text.orEmpty(), it) }
        return listOf(url, inherit, own)
    }

    private fun chain(row: EditTextPreference, after: (String) -> Unit) {
        val accepts = row.onPreferenceChangeListener
        row.setOnPreferenceChangeListener { preference, value ->
            (accepts?.onPreferenceChange(preference, value) ?: true).also { if (it) after(value as String) }
        }
    }

    private fun laneTokenRow(code: String): EditTextPreference {
        val row = EditTextPreference(requireContext())
        row.key = LANE_TOKEN_PREFIX + code
        row.title = getString(R.string.settings_lane_token, code)
        row.dialogTitle = row.title
        row.dialogMessage = getString(R.string.settings_lane_token_hint, code)
        row.setDefaultValue("")
        bindToken(row)
        return row
    }

    private fun laneRow(code: String): EditTextPreference {
        val row = EditTextPreference(requireContext())
        row.key = LANE_PREFIX + code
        row.title = getString(R.string.settings_lane_url, code)
        // A programmatic row has no XML to fall back on for either of these.
        row.dialogTitle = row.title
        row.dialogMessage = getString(R.string.settings_lane_url_hint, code)
        // Set before the row is attached, because attaching is what reads the stored value.
        row.setDefaultValue("")
        bindBaseUrl(row, EndpointKind.Stt)
        return row
    }

    private fun accept(message: String?): Boolean {
        if (message == null) return true
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        return false
    }

    companion object {
        const val ARG_XML = "xml"
        const val ARG_CODE = "code"

        fun of(xml: Int, code: String?) = SectionFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_XML, xml)
                putString(ARG_CODE, code)
            }
        }

        private const val KEY_STT_BASE_URL = "stt_base_url"
        private const val KEY_ALLOW_INSECURE_HTTP = "allow_insecure_http"
        private const val KEY_STT_TOKEN = "stt_token"
        private const val KEY_STT_PROMPT = "stt_prompt"
        private const val KEY_STT_TIMEOUT = "stt_timeout_s"
        private const val KEY_NO_SPEECH_THRESHOLD = "no_speech_threshold"
        private const val KEY_CHIP_LANGUAGES = "chip_languages"
        private const val KEY_POLISH_BASE_URL = "polish_base_url"
        private const val KEY_POLISH_TOKEN = "polish_token"
        private const val KEY_POLISH_MIN_WORDS = "polish_min_words"
        private const val KEY_POLISH_TIMEOUT = "polish_timeout_s"
        private const val KEY_VOLUME_HOLD = "volume_hold_ms"
        private const val KEY_NO_FIELD_MODE = "no_field_mode"
        private const val NO_FIELD_ROW_PREFIX = "no_field_"
        private const val KEY_CATEGORY_LANGUAGES = "category_languages"
        private const val LANGUAGE_ROW_PREFIX = "language_"
        private const val LANE_PREFIX = "lane_base_url_"
    }
}
