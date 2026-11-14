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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Host of [Take]: records under a microphone foreground service, runs STT, lanes and polish off the main thread, and lands at the cursor of the field focused at the start. */
class VoxdroidA11yService : AccessibilityService(), TakeHost {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    /** Dictation log writes, kept off the main thread and out of the STT queue. */
    private val logWriter = Executors.newSingleThreadExecutor()
    private val dictationLog by lazy { DictationLog(dictationLogDir(filesDir)) }
    private val capture by lazy {
        DictationCapture(dictationLog, versionLine(BuildConfig.VERSION_NAME, BuildConfig.COMMIT),
            write = { task -> logWriter.execute { task() } }, unsaved = { Log.i(TAG, "dictation log: entry not saved ($it)") })
    }
    private var landed = ""
    private val settings by lazy { Settings(this) }
    private val fields by lazy { FieldAccess(this) }
    private val polishPrompt by lazy { readBuiltInPrompt(resources) }
    private val polishUnconfigured = AtomicBoolean(false)
    private val machine = Take(this)
    private val gesture = VolumeGesture()
    private lateinit var pill: Pill
    private lateinit var tones: TonePlayer
    private var recorder: AudioRecorder? = null
    private var recordingSince = 0L
    private var startField: Field? = null
    private var takeKeyboardTag: String? = null
    private var polishNotice: String? = null
    private var captions: CaptionController? = null
    private var readAloud: ReadAloudController? = null
    private var buttonClicks = 0
    private val captionPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "voice_captions") captions?.preferencesChanged()
        if (key?.startsWith("tts_") == true || key == "trigger_button") readAloud?.preferencesChanged()
    }

    private val button = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (BuildConfig.DEBUG) buttonClicks++
            buttonAction()
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
            captions?.cancel()
            readAloud?.screenOff()
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
        tones = TonePlayer(this, settings, main)
        captions = CaptionController(this, settings) { machine.state == TakeState.Idle && readAloud?.busy != true }.also { CaptionServiceBridge.controller = it }
        readAloud = ReadAloudController(this,
            { machine.state == TakeState.Idle && captions?.configurationBusy() != true },
            ::buttonAction, ::beginReadAloudMedia, { stopForeground(STOP_FOREGROUND_REMOVE) })
        // An earlier build kept the caption consent in this backed-up file. Nothing reads that copy; it should not travel in backups either.
        getSharedPreferences("voxdroid", MODE_PRIVATE).edit().remove("voice_captions").apply()
        getSharedPreferences("voxdroid", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(captionPrefs)
        getSharedPreferences("secrets", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(captionPrefs)
        accessibilityButtonController.registerAccessibilityButtonCallback(button)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        readAloud?.observe(null)
        logWriter.execute { dictationLog.prune() }
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
        readAloud?.observe(event)
        captions?.observe(event)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && machine.state != TakeState.Idle) pill.follow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        readAloud?.configurationChanged()
    }

    override fun onInterrupt() = Unit

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        if (BuildConfig.DEBUG && packageName.endsWith(".readaloudtest")) readAloud?.diagnostics(writer)
        if (BuildConfig.DEBUG && packageName.endsWith(".captiontest")) {
            writer.println("caption-test buttonClicks=$buttonClicks dictationIdle=${machine.state == TakeState.Idle}")
            writer.println(captions?.diagnostics() ?: "controller=not-connected")
            if (args.firstOrNull() == "controls") HandsetTestControls.dump(this, writer)
        }
    }

    override fun onDestroy() {
        readAloud?.destroy()
        CaptionServiceBridge.controller = null
        captions?.destroy()
        getSharedPreferences("voxdroid", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(captionPrefs)
        getSharedPreferences("secrets", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(captionPrefs)
        // onServiceConnected may never have run: then there is nothing to undo.
        if (::pill.isInitialized) {
            if (machine.state != TakeState.Idle) machine.on(TakeEvent.Cancel)
            unregisterReceiver(screenOff)
            pill.hide()
            tones.stop()
            capture.close()
        }
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        // Not shutdownNow: an entry already handed over is still written.
        logWriter.shutdown()
        super.onDestroy()
    }

    private fun dispatch(event: TakeEvent) {
        if (event == TakeEvent.Trigger) readAloud?.cancel()
        if (event == TakeEvent.Trigger) captions?.cancel()
        if (event == TakeEvent.Trigger && (machine.state == TakeState.Idle || machine.state is TakeState.Error)) {
            val field = fields.focused()
            val inCall = isCallMode(getSystemService(AudioManager::class.java).mode) && !settings.allowDuringCall
            when (val admission = admit(field, settings.noFieldMode, inCall)) {
                is Admission.Refuse -> {
                    if (flashesOver(machine.state)) flashText(refusalText(admission.notice))
                    cue(cueFor(admission))
                    return
                }
                Admission.InCall -> {
                    if (flashesOver(machine.state)) flashText(PillText.InCallNotRecorded)
                    cue(cueFor(admission))
                    return
                }
                Admission.Record -> {
                    polishNotice = null
                    startField = field
                    takeKeyboardTag = fields.keyboardTag()
                    capture.drop()
                }
            }
        }
        val before = machine.state
        val beforeTake = machine.currentTake
        machine.on(event)
        if (event == TakeEvent.Cancel) {
            pill.hide()
            capture.drop()
        }
        render()
        cue(cueFor(before, machine.state, sameTake = beforeTake == machine.currentTake))
    }

    /** The click on the pill and the tone; a Confirm sounds [confirm]. */
    private fun cue(cue: Cue, confirm: Tone? = null) {
        pill.cue(cue)
        toneFor(cue, confirm)?.let { tones.play(it) }
    }

    private fun flashText(text: PillText) = pill.flash(getString(pillString(text)), flashMs(isFailure(text)))

    private fun render() {
        val state = machine.state
        if (state == TakeState.Idle) {
            main.removeCallbacks(tick)
            return
        }
        if (state == TakeState.Starting) return
        pill.show(state, (SystemClock.elapsedRealtime() - recordingSince) / MILLIS_PER_SECOND)
    }

    override fun startTone(take: Int): Boolean {
        capture.begin(take)
        return tones.play(Tone.Start) { dispatch(TakeEvent.StartToneEnded(take)) }
    }

    override fun startRecording(): String? {
        // A microphone foreground service without RECORD_AUDIO throws a SecurityException on Android 14+,
        // so the permission is checked first and named.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            capture.recordingFailed(machine.currentTake, "mic: permission missing")
            return getString(R.string.pill_mic_missing)
        }
        val rec = AudioRecorder(this)
        try {
            goForeground()
            rec.start()
        } catch (failed: RuntimeException) {
            // IllegalStateException from the recorder, SecurityException from the platform.
            stopForeground(STOP_FOREGROUND_REMOVE)
            // The log keeps a fixed line, and so does the pill: the platform's text is not ours to store or show.
            capture.recordingFailed(machine.currentTake, if (failed is SecurityException) "mic: refused by the platform" else "mic: unavailable")
            return micFailureBar(failed)
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
        capture.attempt(take, languageSent(language))
        worker.execute {
            val trace = SttTrace()
            val sttStarted = SystemClock.elapsedRealtime()
            val outcome = dictate(
                transcriber(trace, { capture.note(take, it) }) { take == machine.currentTake },
                wav, language, settings.sttPrompt, settings.noSpeechThreshold,
            )
            capture.stt(take, trace.detected, trace.servedBy, SystemClock.elapsedRealtime() - sttStarted)
            // A blank transcript is silence: it never goes to the polish server.
            if (outcome !is SttOutcome.Text || outcome.text.isBlank()) {
                main.post { answer(outcome, take) }
                return@execute
            }
            val polisher = polisher { take == machine.currentTake }
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
                    val polishStarted = SystemClock.elapsedRealtime()
                    val polished = polish(polisher, outcome.text, settings.polishMinWords, !settings.capitalizeFirstWord)
                    val polishMs = if (polished is PolishOutcome.SkippedShort) null else SystemClock.elapsedRealtime() - polishStarted
                    capture.polished(take, settings.polishModel, clampTimeoutS(settings.polishTimeoutS), polishMs)
                    main.post { answerPolished(polished, outcome.text, take) }
                }
            }
        }
    }

    override fun deliver(text: String) {
        // Every notice for this take on one line, so neither hides the other.
        val notes = mutableListOf<String>()
        polishNotice?.let { notes += it }
        val polishFailed = polishNotice != null
        var shown: PillText? = null
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
            landed = if (decision is Delivery.Drop) "" else finished
            when (decision) {
                Delivery.Insert -> Unit
                is Delivery.Clipboard -> {
                    fields.copy(finished)
                    shown = deliveryText(decision.notice, copied = true)
                }
                is Delivery.Drop -> shown = deliveryText(decision.notice, copied = false)
            }
            shown?.let { notes += getString(pillString(it)) }
        }
        // The cue needs the take's pill still attached, so the pill hides only once the text has landed.
        cue(cueFor(decision, polishFailed), Tone.Landed)
        polishNotice = null
        pill.hide()
        if (notes.isNotEmpty()) pill.flash(notes.joinToString(" · "), flashMs(polishFailed, shown?.let(::isFailure) == true))
    }

    override fun ended() {
        startField = null
    }

    private fun answer(outcome: SttOutcome, take: Int) {
        when (outcome) {
            is SttOutcome.Text -> if (outcome.text.isBlank()) {
                silent(take)
            } else {
                land(take, outcome.text, LogOutcome.PolishOff, outcome.text)
            }
            SttOutcome.Silence -> silent(take)
            is SttOutcome.Failure -> {
                Log.d(TAG, outcome.detail)
                if (live(take)) capture.failed(take, outcome.detail)
                dispatch(TakeEvent.Failure(outcome.bar, take))
            }
        }
    }

    private fun silent(take: Int) {
        if (take == machine.currentTake) pill.flash(getString(R.string.pill_silence), flashMs(false))
        land(take, "", LogOutcome.Silence, "")
    }

    private fun answerPolished(outcome: PolishOutcome, raw: String, take: Int) {
        when (outcome) {
            is PolishOutcome.Text -> land(take, outcome.text, LogOutcome.Cleaned, raw)
            is PolishOutcome.SkippedShort -> land(take, outcome.raw, LogOutcome.SkippedShort, raw)
            is PolishOutcome.Fallback -> {
                Log.d(TAG, outcome.detail)
                // deliver() shows this beside its own notice.
                polishNotice = when {
                    take != machine.currentTake -> null
                    outcome.blocked -> getString(R.string.pill_polish_blocked)
                    else -> outcome.notice ?: getString(R.string.pill_polish_failed)
                }
                val logged = if (outcome.blocked) LogOutcome.CleanupBlocked else LogOutcome.CleanupFailed
                land(take, outcome.raw, logged, raw, outcome.detail)
            }
        }
    }

    /** Whether [Take] still waits on [take]: only then does its answer land, and only then is it logged. */
    private fun live(take: Int) = take == machine.currentTake && machine.state is TakeState.Working

    private fun land(take: Int, text: String, outcome: LogOutcome, raw: String, detail: String? = null) {
        val logged = live(take)
        landed = ""
        try {
            dispatch(TakeEvent.Transcript(text, take))
            if (logged) capture.finish(take, outcome, raw, landed, detail)
        } finally {
            landed = ""
        }
    }

    private fun transcriber(trace: SttTrace, note: (String) -> Unit, current: () -> Boolean): Transcriber {
        val threshold = settings.noSpeechThreshold
        val timeoutMs = clampTimeoutS(settings.sttTimeoutS) * MILLIS_PER_SECOND.toInt()
        val allowInsecureHttp = settings.allowInsecureHttp
        // The log names a lane by its code and the default endpoint by its host alone.
        fun client(baseUrl: String, token: String, lane: String? = null) = trace.wrap(
            lane?.let { "lane $it" } ?: endpointHost(baseUrl) ?: "default",
            SttClient(
                baseUrl = baseUrl, allowInsecureHttp = allowInsecureHttp, model = settings.sttModel, token = token,
                timeoutMs = timeoutMs, scored = threshold > 0f, current = current,
                note = { Log.i(TAG, it); note(lane?.let { code -> "$it (lane $code)" } ?: it) },
            ),
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
                code to client(url, token, code)
            }
        }.toMap()
        val tokenless = tokens.filterValues { it.isBlank() }.keys
        return Routed(client(settings.sttBaseUrl, sttToken), lanes, tokenless, sttToken.isNotBlank(), current) { Log.i(TAG, it); note(it) }
    }

    private fun polisher(current: () -> Boolean): Polisher? {
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
            current = current,
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

    private fun buttonAction() {
        if (!settings.triggerButton) return
        readAloud?.cancel()
        if (machine.state == TakeState.Idle && captions?.trigger() == true) return
        dispatch(TakeEvent.Trigger)
    }

    private fun beginReadAloudMedia() {
        check(machine.state == TakeState.Idle)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("read-aloud", getString(R.string.read_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(this, 44,
            Intent(this, VoxdroidA11yService::class.java).setAction(READ_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, "read-aloud").setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.read_notification)).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.read_stop), stop).build()).build()
        startForeground(2, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == READ_STOP) readAloud?.cancel()
        // The notification's Stop arrives as a start. Left started, the service would survive being switched off in
        // Accessibility settings, recorder included: Android destroys a service only once it is neither bound nor started.
        stopSelf(startId)
        return START_NOT_STICKY
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
        const val READ_STOP = "io.github.iteramen.voxdroid.STOP_READ_ALOUD"
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 1
        const val TICK_MS = 500L
        const val CAP_MS = 5 * 60 * 1000L
        const val MILLIS_PER_SECOND = 1000L
        const val POLISH_UNCONFIGURED = "polish: enabled with no base url or model, stage skipped"
    }
}
