package io.github.iteramen.voxdroid

import android.Manifest
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Host of [Take]: records under a microphone foreground service, runs STT, lanes and polish off the main thread, and lands at the cursor of the field focused at the start. */
class VoxdroidA11yService : AccessibilityService(), TakeHost {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val settings by lazy { Settings(this) }
    private val fields by lazy { FieldAccess(this) }
    private val polishPrompt by lazy { readBuiltInPrompt(resources) }
    private val polishUnconfigured = AtomicBoolean(false)
    private val machine = Take(this)
    private val gesture = VolumeGesture()
    private lateinit var pill: Pill
    private var recorder: AudioRecorder? = null
    private var recordingSince = 0L
    private var startField: Field? = null
    private var takeKeyboardTag: String? = null
    private var polishNotice: Int? = null

    private val button = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (settings.triggerButton) dispatch(TakeEvent.Trigger)
        }
    }

    private val hold = Runnable { if (gesture.holdElapsed()) dispatch(TakeEvent.Trigger) }
    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, TICK_MS)
        }
    }
    private val cap = Runnable { dispatch(TakeEvent.CapReached) }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (machine.state != TakeState.Idle) dispatch(TakeEvent.Cancel)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        setCacheEnabled(false)
        pill = Pill(this, settings,
            onStop = { dispatch(TakeEvent.Trigger) },
            onRetry = { dispatch(TakeEvent.Retry) },
            onCancel = { dispatch(TakeEvent.Cancel) })
        accessibilityButtonController.registerAccessibilityButtonCallback(button)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN || !settings.triggerVolume) return false
        val recording = machine.state == TakeState.Recording
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when (gesture.down(event.repeatCount, recording)) {
                OnDown.Stop -> dispatch(TakeEvent.Trigger)
                OnDown.ArmHold -> main.postDelayed(hold, settings.volumeHoldMs.toLong())
                OnDown.Swallow -> Unit
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(hold)
                if (gesture.up() == OnUp.ReapplyVolume) {
                    getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
                    )
                }
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && machine.state != TakeState.Idle) pill.follow()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // onServiceConnected may never have run: then there is nothing to undo.
        if (::pill.isInitialized) {
            if (machine.state != TakeState.Idle) machine.on(TakeEvent.Cancel)
            unregisterReceiver(screenOff)
            pill.hide()
        }
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dispatch(event: TakeEvent) {
        if (event == TakeEvent.Trigger && machine.state !is TakeState.Recording && machine.state !is TakeState.Working) {
            val field = fields.focused()
            val inCall = isCallMode(getSystemService(AudioManager::class.java).mode) && !settings.allowDuringCall
            when (val admission = admit(field, settings.noFieldMode, inCall)) {
                is Admission.Refuse -> {
                    // Only from Idle: a flash would hide the Retry and cancel of an error pill that
                    // still holds the take's audio.
                    if (machine.state == TakeState.Idle) {
                        pill.flash(getString(pillString(refusalText(admission.notice))))
                    }
                    pill.cue(cueFor(admission))
                    return
                }
                Admission.InCall -> {
                    if (machine.state == TakeState.Idle) pill.flash(getString(pillString(PillText.InCallNotRecorded)))
                    pill.cue(cueFor(admission))
                    return
                }
                Admission.Record -> {
                    polishNotice = null
                    startField = field
                    takeKeyboardTag = fields.keyboardTag()
                }
            }
        }
        val before = machine.state
        val beforeTake = machine.currentTake
        machine.on(event)
        if (event == TakeEvent.Cancel) pill.hide()
        render()
        pill.cue(cueFor(before, machine.state, sameTake = beforeTake == machine.currentTake))
    }

    private fun render() {
        val state = machine.state
        if (state == TakeState.Idle) {
            main.removeCallbacks(tick)
            return
        }
        pill.show(state, (SystemClock.elapsedRealtime() - recordingSince) / MILLIS_PER_SECOND)
    }

    override fun startRecording(): String? {
        // A microphone foreground service without RECORD_AUDIO throws a SecurityException on Android 14+,
        // so the permission is checked first and named.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return getString(R.string.pill_mic_missing)
        }
        val rec = AudioRecorder(this)
        try {
            goForeground()
            rec.start()
        } catch (failed: RuntimeException) {
            // IllegalStateException from the recorder, SecurityException from the platform.
            stopForeground(STOP_FOREGROUND_REMOVE)
            return failed.message ?: "mic: unavailable"
        }
        recorder = rec
        recordingSince = SystemClock.elapsedRealtime()
        main.post(tick)
        return null
    }

    override fun stopRecording(): ByteArray {
        main.removeCallbacks(tick)
        val wav = recorder?.stop() ?: ByteArray(0)
        recorder = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        return wav
    }

    override fun armCap() {
        main.postDelayed(cap, CAP_MS)
    }

    override fun cancelCap() = main.removeCallbacks(cap)

    override fun transcribe(wav: ByteArray, take: Int) {
        val language = takeFor(takeKeyboardTag, settings::isExplicit, settings.chipLanguages, laneCodes(settings.chipLanguages, settings::laneBaseUrl))
        worker.execute {
            val outcome = dictate(transcriber { take == machine.currentTake }, wav, language, settings.sttPrompt, settings.noSpeechThreshold)
            // A blank transcript is silence: it never goes to the polish server.
            if (outcome !is SttOutcome.Text || outcome.text.isBlank()) {
                main.post { answer(outcome, take) }
                return@execute
            }
            val polisher = polisher()
            if (polisher == null) {
                main.post { answer(outcome, take) }
                return@execute
            }
            // The polish leg is decided on the main thread, where the take lives.
            main.post {
                if (take != machine.currentTake || worker.isShutdown) return@post
                dispatch(TakeEvent.Polishing(take))
                worker.execute {
                    if (take != machine.currentTake) return@execute
                    val polished = polish(polisher, outcome.text, settings.polishMinWords, !settings.capitalizeFirstWord)
                    main.post { answerPolished(polished, take) }
                }
            }
        }
    }

    override fun deliver(text: String) {
        // Every notice for this take on one line, so neither hides the other.
        val notes = mutableListOf<String>()
        polishNotice?.let { notes += getString(it) }
        val start = startField
        startField = null
        val result = finish(text, settings.stripTerminalPeriod)
        droppedNote(result.dropped)?.let { Log.i(TAG, it) }
        val finished = result.text
        var decision: Delivery? = null
        if (finished.isNotEmpty()) {
            val planned = delivery(start, fields.focused(), settings.noFieldMode)
            // No input connection is the same as no field.
            decision = if (planned != Delivery.Insert || fields.insert(finished)) planned else delivery(null, null, settings.noFieldMode)
            when (decision) {
                Delivery.Insert -> Unit
                is Delivery.Clipboard -> {
                    fields.copy(finished)
                    notes += getString(pillString(deliveryText(decision.notice, copied = true)))
                }
                is Delivery.Drop -> notes += getString(pillString(deliveryText(decision.notice, copied = false)))
            }
        }
        // The cue needs the take's pill still attached, so the pill hides only once the text has landed.
        pill.cue(cueFor(decision, polishNotice != null))
        polishNotice = null
        pill.hide()
        if (notes.isNotEmpty()) pill.flash(notes.joinToString(" · "))
    }

    override fun ended() {
        startField = null
    }

    private fun answer(outcome: SttOutcome, take: Int) {
        when (outcome) {
            is SttOutcome.Text -> if (outcome.text.isBlank()) {
                silent(take)
            } else {
                dispatch(TakeEvent.Transcript(outcome.text, take))
            }
            SttOutcome.Silence -> silent(take)
            is SttOutcome.Failure -> {
                Log.d(TAG, outcome.detail)
                dispatch(TakeEvent.Failure(outcome.bar, take))
            }
        }
    }

    private fun silent(take: Int) {
        if (take == machine.currentTake) pill.flash(getString(R.string.pill_silence))
        dispatch(TakeEvent.Transcript("", take))
    }

    private fun answerPolished(outcome: PolishOutcome, take: Int) {
        when (outcome) {
            is PolishOutcome.Text -> dispatch(TakeEvent.Transcript(outcome.text, take))
            is PolishOutcome.Fallback -> {
                Log.d(TAG, outcome.detail)
                // deliver() shows this beside its own notice.
                polishNotice = when {
                    take != machine.currentTake -> null
                    outcome.blocked -> R.string.pill_polish_blocked
                    else -> R.string.pill_polish_failed
                }
                dispatch(TakeEvent.Transcript(outcome.raw, take))
            }
        }
    }

    private fun transcriber(current: () -> Boolean): Transcriber {
        val threshold = settings.noSpeechThreshold
        val timeoutMs = clampTimeoutS(settings.sttTimeoutS) * MILLIS_PER_SECOND.toInt()
        val allowInsecureHttp = settings.allowInsecureHttp
        fun client(baseUrl: String, token: String) = SttClient(
            baseUrl = baseUrl, allowInsecureHttp = allowInsecureHttp, model = settings.sttModel, token = token,
            timeoutMs = timeoutMs, scored = threshold > 0f, current = current, note = { Log.i(TAG, it) },
        )
        val sttToken = settings.sttToken
        val tokens = mutableMapOf<String, String>()
        val lanes = settings.chipLanguages.mapNotNull { code ->
            settings.laneBaseUrl(code).ifBlank { null }?.let { url ->
                // A lane is sent a token only when asked: its own, or the STT token on its switch.
                val token = laneToken(
                    settings.sttBaseUrl, sttToken, url, settings.laneToken(code), settings.laneInheritsToken(code),
                )
                tokens[code] = token
                code to client(url, token)
            }
        }.toMap()
        val tokenless = tokens.filterValues { it.isBlank() }.keys
        return Routed(client(settings.sttBaseUrl, sttToken), lanes, tokenless, sttToken.isNotBlank(), current) { Log.i(TAG, it) }
    }

    private fun polisher(): Polisher? {
        if (!settings.polishEnabled) return null
        if (settings.polishBaseUrl.isBlank() || settings.polishModel.isBlank()) {
            if (polishUnconfigured.compareAndSet(false, true)) Log.i(TAG, POLISH_UNCONFIGURED)
            return null
        }
        return PolishClient(
            baseUrl = settings.polishBaseUrl, allowInsecureHttp = settings.allowInsecureHttp,
            model = settings.polishModel, token = settings.polishToken,
            timeoutMs = clampTimeoutS(settings.polishTimeoutS) * MILLIS_PER_SECOND.toInt(),
            systemPrompt = systemPrompt(builtInPrompt(polishPrompt, settings.capitalizeFirstWord), settings.sttPrompt),
        )
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_notification))
            .build()
        startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun pillString(text: PillText): Int = when (text) {
        PillText.NoFieldNotRecorded -> R.string.pill_no_field
        PillText.PasswordNotRecorded -> R.string.pill_password_field
        PillText.InCallNotRecorded -> R.string.pill_in_call
        PillText.NoFieldNotInserted -> R.string.pill_no_field_dropped
        PillText.FieldChangedNotInserted -> R.string.pill_field_changed_dropped
        PillText.PasswordNotInserted -> R.string.pill_password_field_dropped
        PillText.Copied -> R.string.pill_copied
        PillText.FieldChangedCopied -> R.string.pill_field_changed_copied
    }

    private companion object {
        const val TAG = "voxdroid"
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 1
        const val TICK_MS = 500L
        const val CAP_MS = 5 * 60 * 1000L
        const val MILLIS_PER_SECOND = 1000L
        const val POLISH_UNCONFIGURED = "polish: enabled with no base url or model, stage skipped"
    }
}
