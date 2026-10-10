package io.github.iteramen.voxdroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Secure
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The launcher entry: a status card, then the five sections; the card is re-read while visible and nothing runs in the background. */
class SettingsActivity : AppCompatActivity() {

    private val settings by lazy { Settings(this) }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var latest: Probe = Probe.Unknown
    private var askedMic = false

    private val subtypeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = render()
    }

    /** One probe at a time: a slow endpoint must not stack requests behind the five-second tick. */
    private val probing = AtomicBoolean(false)

    private val reprobe = object : Runnable {
        override fun run() {
            if (probing.compareAndSet(false, true)) {
                val url = settings.sttBaseUrl
                val allowInsecureHttp = settings.allowInsecureHttp
                worker.execute {
                    val result = try {
                        probe(url, allowInsecureHttp)
                    } finally {
                        probing.set(false)
                    }
                    main.post { latest = result; render() }
                }
            }
            main.postDelayed(this, PROBE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        findViewById<TextView>(R.id.version).text = versionLine(BuildConfig.VERSION_NAME, BuildConfig.COMMIT)
        askedMic = savedInstanceState?.getBoolean(STATE_ASKED_MIC) ?: false
        row(R.id.row_speech, R.xml.section_speech, R.string.section_speech)
        row(R.id.row_triggers, R.xml.section_triggers, R.string.section_triggers)
        row(R.id.row_delivery, R.xml.section_delivery, R.string.section_delivery)
        row(R.id.row_cleanup, R.xml.section_cleanup, R.string.section_cleanup)
        row(R.id.row_languages, R.xml.section_languages, R.string.section_languages)
        privacyRow()
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Secure.getUriFor(SELECTED_SUBTYPE), false, subtypeObserver)
        main.post(reprobe)
        render()
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(subtypeObserver)
        main.removeCallbacks(reprobe)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ASKED_MIC, askedMic)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun row(id: Int, xml: Int, title: Int) {
        findViewById<View>(id).setOnClickListener { startActivity(SectionActivity.intent(this, xml, title)) }
    }

    /** Hidden while the URL is blank; with no browser, a message names the URL. */
    private fun privacyRow() {
        val url = getString(R.string.privacy_policy_url)
        val row = findViewById<View>(R.id.row_privacy)
        row.visibility = if (privacyPolicyShown(url)) View.VISIBLE else View.GONE
        row.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.privacy_policy_no_browser, url), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render() {
        val found = problems(
            serviceOn = serviceOn(),
            micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            endpointSet = settings.sttBaseUrl.isNotBlank(),
            probe = latest,
            endpointBlocked = insecureBlocked(settings.sttBaseUrl, settings.allowInsecureHttp),
        )
        val ready = found.isEmpty()
        val card: Int
        val dot: Int
        val onCard: Int
        if (ready) {
            val roles = readyCardRoles(themeColor(AppCompatR.attr.colorPrimary), themeFlag(AppCompatR.attr.isLightTheme))
            card = roles.accentContainer; dot = roles.accent; onCard = roles.onAccentContainer
        } else {
            card = themeColor(MaterialR.attr.colorErrorContainer); dot = themeColor(AppCompatR.attr.colorError)
            onCard = themeColor(MaterialR.attr.colorOnErrorContainer)
        }
        findViewById<MaterialCardView>(R.id.status_card).setCardBackgroundColor(card)
        findViewById<View>(R.id.status_dot).background?.mutate()?.setTint(dot)
        for (id in CARD_TEXTS) findViewById<TextView>(id).setTextColor(onCard)
        findViewById<TextView>(R.id.status_head).setText(if (ready) R.string.status_ready else R.string.status_not_ready)
        findViewById<TextView>(R.id.status_endpoint).text = endpointLine()
        findViewById<TextView>(R.id.status_keyboard).text = keyboardLine()
        findViewById<TextView>(R.id.status_triggers).text = triggersLine()
        findViewById<TextView>(R.id.status_problems).apply {
            text = found.joinToString("\n") { getString(problemText(it)) }
            visibility = if (ready) View.GONE else View.VISIBLE
        }
        val action = findViewById<MaterialButton>(R.id.status_action)
        when (found.firstOrNull()) {
            null -> { action.setText(R.string.status_try); action.setOnClickListener { startActivity(TryActivity.intent(this)) } }
            Problem.ServiceOff -> if (SelfEnable.available(this)) {
                action.setText(R.string.status_turn_on)
                action.setOnClickListener { SelfEnable.turnOn(this, serviceComponent(packageName)); render() }
            } else {
                action.setText(R.string.status_open_accessibility)
                action.setOnClickListener {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            Problem.MicrophoneMissing -> {
                // After a final denial the dialog answers at once, so open the app's page instead.
                val step = microphoneStep(
                    granted = false, askedHere = askedMic,
                    canAsk = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
                )
                if (step == MicrophoneStep.OpenSettings) {
                    action.setText(R.string.status_mic_settings)
                    action.setOnClickListener {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", packageName, null),
                            ),
                        )
                    }
                } else {
                    action.setText(R.string.status_grant_mic)
                    action.setOnClickListener {
                        askedMic = true
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                    }
                }
            }
            Problem.NoEndpoint, Problem.EndpointBlocked, Problem.EndpointUnreachable -> {
                action.setText(R.string.status_open_speech)
                action.setOnClickListener { startActivity(SectionActivity.intent(this, R.xml.section_speech, R.string.section_speech)) }
            }
        }
    }

    private fun problemText(problem: Problem): Int = when (problem) {
        Problem.ServiceOff -> R.string.status_problem_service
        Problem.MicrophoneMissing -> R.string.status_problem_microphone
        Problem.NoEndpoint -> R.string.status_problem_endpoint_unset
        Problem.EndpointBlocked -> R.string.status_problem_endpoint_blocked
        Problem.EndpointUnreachable -> R.string.status_problem_endpoint_down
    }

    private fun serviceOn(): Boolean {
        val enabled = Secure.getString(contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    /** `plain http blocked` while the switch refuses it, so a red card explains itself. */
    private fun endpointLine(): String {
        val url = settings.sttBaseUrl
        val state = when (val p = latest) {
            is Probe.Reachable -> getString(R.string.status_endpoint_ok, p.ms)
            Probe.Unreachable -> getString(R.string.status_endpoint_down)
            Probe.Unknown -> getString(if (url.isBlank()) R.string.status_endpoint_unset else R.string.status_endpoint_checking)
        }
        if (url.isBlank()) return state
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url
        val clear = when {
            insecureBlocked(url, settings.allowInsecureHttp) -> " · " + getString(R.string.status_plain_http_blocked)
            isCleartextToRemoteHost(url) -> " · " + getString(R.string.status_plain_http)
            else -> ""
        }
        return "$state\n$host$clear"
    }

    private fun keyboardLine(): String {
        val tag = FieldAccessTag.current(this)
        val language = normalizeTag(tag)?.let { java.util.Locale.forLanguageTag(it).let { l -> l.getDisplayLanguage(l) } }
            ?: getString(R.string.status_keyboard_unknown)
        val chips = settings.chipLanguages
        val target = route(tag, settings::isExplicit, chips, laneCodes(chips, settings::laneBaseUrl))
        val (kind, value) = target.split(':', limit = 2)
        return when (kind) {
            "lane" -> getString(
                R.string.status_route_lane, language,
                java.util.Locale.forLanguageTag(value).getDisplayLanguage(java.util.Locale.ENGLISH),
            )
            "explicit" -> getString(R.string.status_route_explicit, language, value)
            else -> getString(R.string.status_route_auto, language, value.replace(",", " / "))
        }
    }

    private fun triggersLine(): String = listOfNotNull(
        getString(R.string.settings_trigger_volume).takeIf { settings.triggerVolume },
        getString(R.string.settings_trigger_button).takeIf { settings.triggerButton },
    ).joinToString(" · ").ifEmpty { getString(R.string.status_no_triggers) }

    /** A boolean theme attribute, true when the theme does not set it (the Context overloads' default). */
    private fun themeFlag(attr: Int): Boolean {
        val value = android.util.TypedValue()
        return !theme.resolveAttribute(attr, value, true) || value.data != 0
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private companion object {
        const val SELECTED_SUBTYPE = "selected_input_method_subtype"
        const val PROBE_EVERY_MS = 5000L
        const val STATE_ASKED_MIC = "asked_microphone"

        val CARD_TEXTS = intArrayOf(
            R.id.status_head, R.id.status_label_endpoint, R.id.status_endpoint, R.id.status_label_keyboard,
            R.id.status_keyboard, R.id.status_label_triggers, R.id.status_triggers, R.id.status_problems,
        )
    }
}

fun privacyPolicyShown(url: String): Boolean = url.isNotBlank()
