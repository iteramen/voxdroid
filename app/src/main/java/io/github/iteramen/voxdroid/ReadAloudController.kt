package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Visible controls and explicit read-only text picking; selected text and speech are never stored. */
internal class ReadAloudController(private val service: AccessibilityService, private val canStart: () -> Boolean,
    private val mic: () -> Unit, private val beginMedia: () -> Unit, private val endMedia: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val windows = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val prefs = ReadAloudSettings(service)
    private val generation = AtomicLong()
    private val lock = Any()
    private var glyph: View? = null
    private var glyphParams: WindowManager.LayoutParams? = null
    private var glyphContext: ReadControlContext? = null
    private var picker: View? = null
    private var active = false
    private var sourcePackage: String? = null
    private var sourceWindow = -1
    private var track: AudioTrack? = null
    private var media = false
    private var focused = false
    private var choices = emptyList<Choice>()
    private var selected: Choice? = null
    private var selectedText: String? = null
    private var contentKept = 0
    @Volatile private var playbackStarts = 0
    @Volatile private var playbackCompletes = 0
    @Volatile private var lastError = "none"
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> if (change < 0) cancel() }, main).build()

    val busy: Boolean get() = active || picker != null

    fun observe(event: AccessibilityEvent?) {
        val root = service.getRootInActiveWindow(0)
        val context = controlContext(root)
        if (context != ReadControlContext.External) {
            cancel()
            if (context == ReadControlContext.Hidden) hideGlyph() else showGlyph()
            return
        }
        if (busy) {
            val sameSource = root != null && root.windowId == sourceWindow && root.packageName?.toString() == sourcePackage
            val invalid = readAloudInvalidated(
                sameSource,
                event != null && event.packageName?.toString() == sourcePackage, event?.eventType,
                picker != null, sameSource && (picker != null || selectionStillCurrent()))
            if (invalid) cancel()
            else if (active && event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.packageName?.toString() == sourcePackage) contentKept++
        }
        showGlyph()
    }

    /** Refresh only the explicitly chosen node. Never find a replacement or read surrounding text. */
    private fun selectionStillCurrent(): Boolean {
        val choice = selected ?: return false
        return choiceStillCurrent(choice) && choice.node.text?.toString() == selectedText
    }

    private fun choiceStillCurrent(choice: Choice): Boolean {
        val node = choice.node
        return node.refresh() && node.windowId == sourceWindow && node.packageName?.toString() == sourcePackage &&
            node.isVisibleToUser && !node.isPassword && !node.isEditable &&
            Rect().also(node::getBoundsInScreen) == choice.bounds
    }

    fun preferencesChanged() { cancel(); hideGlyph(); showGlyph() }

    private fun controlContext(root: AccessibilityNodeInfo?): ReadControlContext {
        if (root == null) return ReadControlContext.Hidden
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return readControlContext(root.packageName?.toString() == service.packageName,
            field?.isEditable == true && field.viewIdResourceName == "${service.packageName}:id/try_field",
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            field?.isPassword == true)
    }

    private fun showGlyph() {
        val context = controlContext(service.getRootInActiveWindow(0))
        val microphone = Settings(service).triggerButton
        val speaker = prefs.enabled && context == ReadControlContext.External
        if (context == ReadControlContext.Hidden || (!microphone && !speaker)) { hideGlyph(); return }
        if (glyphContext != context) hideGlyph()
        if (glyph != null) return
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val screen = windows.currentWindowMetrics.bounds
        val paired = microphone && speaker
        val width = dp(if (paired) 104 else 48)
        val height = dp(48)
        val space = ControlSpace(screen.width(), screen.height(), width, height, density)
        val position = placeControl(rememberControl(ControlPosition(prefs.x ?: space.maxX,
            prefs.y ?: screen.height() / 3), space), space)
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }
        val box = PillLayout(service, null).apply {
            orientation = LinearLayout.HORIZONTAL
            val inset = if (paired) dp(4) else 0
            setPadding(inset, 0, inset, 0)
            setBackgroundResource(if (paired) R.drawable.floating_controls_pair else R.drawable.floating_controls_background)
            clipToOutline = true
        }
        // Reuse the pill's touch-slop interception: dragging cancels the child button's tap.
        var oldX = 0; var oldY = 0
        box.onDown = { oldX = params.x; oldY = params.y }
        box.onDrag = { dx, dy, ended ->
            val bounds = windows.currentWindowMetrics.bounds
            params.x = (oldX + dx).toInt().coerceIn(0, maxOf(0, bounds.width() - box.width))
            params.y = (oldY + dy).toInt().coerceIn(0, maxOf(0, bounds.height() - box.height))
            windows.updateViewLayout(box, params)
            if (ended) prefs.position(params.x, params.y)
        }
        fun action(tagName: String, icon: Int, description: Int, visible: Boolean, click: () -> Unit) =
            ImageButton(service).apply {
                tag = tagName
                setImageResource(icon)
                contentDescription = service.getString(description)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.floating_control_press)
                visibility = if (visible) View.VISIBLE else View.GONE
                setOnClickListener { click() }
            }
        box.addView(action("read_mic", R.drawable.ic_control_mic, R.string.read_dictate, microphone) {
            cancel(); mic()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        box.addView(action("read_speaker", R.drawable.ic_control_speaker, R.string.read_speak_stop, speaker) {
            chooseOrStop()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        if (active) params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        windows.addView(box, params); glyph = box; glyphParams = params; glyphContext = context
    }

    private fun keepScreenOn(on: Boolean) {
        val view = glyph ?: return
        val params = glyphParams ?: return
        val flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (params.flags == flags) return
        params.flags = flags
        windows.updateViewLayout(view, params)
    }

    private fun hideGlyph() { glyph?.let { runCatching { windows.removeView(it) } }; glyph = null; glyphParams = null; glyphContext = null }
    private fun hidePicker() { picker?.let { runCatching { windows.removeView(it) } }; picker = null; choices = emptyList() }

    private class Choice(val node: AccessibilityNodeInfo, val bounds: Rect)

    private fun chooseOrStop() {
        if (busy) { cancel(); return }
        if (!prefs.enabled) { notice(service.getString(R.string.read_enable_first)); return }
        if (!canStart()) { notice(service.getString(R.string.read_finish_first)); return }
        if (audio.mode != AudioManager.MODE_NORMAL) { notice(service.getString(R.string.read_finish_first)); return }
        val root = service.getRootInActiveWindow(0) ?: run { notice(service.getString(R.string.read_no_text)); return }
        if (root.packageName?.toString() == service.packageName || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val candidates = mutableListOf<Choice>()
        val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && ++visited <= 600) {
            val node = pending.removeFirst()
            if (node.isPassword) continue
            if (node.isVisibleToUser && !node.isEditable && node.text?.length?.let { it >= 15 } == true) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() > 0 && bounds.height() > 0) candidates += Choice(node, bounds)
            }
            for (i in 0 until node.childCount.coerceAtMost(100)) node.getChild(i)?.let(pending::addLast)
        }
        if (candidates.isEmpty()) { notice(service.getString(R.string.read_no_text)); return }
        sourcePackage = root.packageName?.toString(); sourceWindow = root.windowId
        choices = candidates
        val paint = Paint().apply { color = 0xff64b5f6.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * service.resources.displayMetrics.density }
        val overlay = object : View(service) {
            override fun onDraw(canvas: Canvas) {
                val offset = IntArray(2); getLocationOnScreen(offset)
                canvas.save(); canvas.translate(-offset[0].toFloat(), -offset[1].toFloat())
                candidates.forEach { canvas.drawRect(it.bounds, paint) }; canvas.restore()
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    val chosen = candidates.filter { it.bounds.contains(event.rawX.toInt(), event.rawY.toInt()) }
                        .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
                    hidePicker()
                    if (chosen == null) cancel() else {
                        val currentRoot = service.getRootInActiveWindow(0)
                        if (currentRoot?.windowId != sourceWindow || currentRoot.packageName?.toString() != sourcePackage ||
                            !choiceStillCurrent(chosen)) cancel()
                        else speak(chosen, chosen.node.text?.toString().orEmpty())
                    }
                }; return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        windows.addView(overlay, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT))
        picker = overlay
        notice(service.getString(R.string.read_pick_text))
    }

    private fun speak(choice: Choice, text: String) {
        val config = prefs.snapshot()
        val prepared = try { config.prepare(text) } catch (failed: TtsFailure) { notice(failed.userMessage); return }
        if (!canStart()) { notice(service.getString(R.string.read_finish_first)); return }
        val id = generation.incrementAndGet()
        lastError = "none"
        try { beginMedia(); media = true } catch (_: RuntimeException) { lastError = "media_foreground_failed"; notice(service.getString(R.string.read_failed)); return }
        active = true
        selected = choice; selectedText = text
        keepScreenOn(true)
        worker.queue.clear()
        worker.execute {
            try {
                for ((chunk, endpoint) in prepared) {
                    if (generation.get() != id) break
                    val pcm = ttsSynthesize(config, endpoint, chunk, { generation.get() == id }, {})
                    if (generation.get() != id) break
                    synchronized(lock) {
                        if (generation.get() != id) return@execute
                        if (!focused) {
                            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw TtsFailure("Read aloud: audio output is busy")
                            focused = true
                        }
                    }
                    play(pcm, id)
                }
            } catch (failed: Exception) {
                if (generation.get() == id) lastError = if (failed is TtsFailure) Regex("HTTP [0-9]{3}").find(failed.userMessage)?.value ?: "tts_refused" else "transport_or_audio_failed"
                if (generation.get() == id) main.post { if (generation.get() == id) notice(
                    if (failed is TtsFailure) failed.userMessage else service.getString(R.string.read_failed)) }
            } finally { main.post { if (generation.get() == id) cancel() } }
        }
    }

    private fun play(pcm: SpeechPcm, id: Long) {
        val mask = if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw TtsFailure("Read aloud: audio format unavailable")
        val player = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.sampleRate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(minimum * 2).build()
        try {
            synchronized(lock) { if (generation.get() != id) return; track = player; player.play(); playbackStarts++ }
            var offset = 0
            while (offset < pcm.samples.size && generation.get() == id) {
                val count = player.write(pcm.samples, offset, minOf(4096, pcm.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                if (count <= 0) throw TtsFailure("Read aloud: audio playback stopped")
                offset += count
            }
            while (generation.get() == id && player.playbackHeadPosition < pcm.samples.size / pcm.channels) Thread.sleep(20)
            if (generation.get() == id) playbackCompletes++
        } finally { synchronized(lock) { if (track === player) track = null }; player.release() }
    }

    fun cancel() {
        generation.incrementAndGet(); hidePicker(); active = false
        selected = null; selectedText = null
        keepScreenOn(false)
        worker.queue.clear()
        synchronized(lock) {
            track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
            if (focused) { audio.abandonAudioFocusRequest(focus); focused = false }
        }
        if (media) { media = false; endMedia() }
        sourcePackage = null; sourceWindow = -1
    }
    fun screenOff() { cancel(); hideGlyph() }
    fun destroy() { screenOff(); worker.shutdownNow() }
    /** Called only from the isolated debug package's service dump: no text, endpoints, tokens or app identity. */
    fun diagnostics(out: java.io.PrintWriter) {
        val playing = synchronized(lock) { track?.playState == AudioTrack.PLAYSTATE_PLAYING }
        out.println("read-aloud enabled=${prefs.enabled} active=$active picking=${picker != null} playing=$playing starts=$playbackStarts completed=$playbackCompletes error=$lastError")
        val root = service.getRootInActiveWindow(0)
        val awake = glyphParams?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0 && glyphParams != null
        out.println("read-state glyph=${glyph != null} root=${root != null} ownRoot=${root?.packageName?.toString() == service.packageName} locked=${service.getSystemService(KeyguardManager::class.java).isKeyguardLocked} keepScreenOn=$awake contentKept=$contentKept")
        val box = glyph as? LinearLayout
        if (box != null) for (i in 0 until box.childCount) {
            val child = box.getChildAt(i)
            if (child.tag in listOf("read_mic", "read_speaker") && child.visibility == View.VISIBLE) {
                val location = IntArray(2); child.getLocationOnScreen(location)
                if (child.isShown) out.println("read-control ${child.tag}=${Rect(location[0], location[1], location[0] + child.width, location[1] + child.height).toShortString()}")
            }
        }
        choices.forEachIndexed { index, choice -> out.println("read-choice $index chars=${choice.node.text?.length ?: 0} bounds=${choice.bounds.toShortString()}") }
    }
    private fun notice(text: String) { Toast.makeText(service, text, Toast.LENGTH_LONG).show() }
}
