package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.util.AttributeSet
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** An accessibility overlay that never takes focus, so the field and Gboard keep theirs; [place] decides the height. */
class Pill(
    private val service: AccessibilityService,
    private val settings: Settings,
    private val onStop: () -> Unit,
    private val onRetry: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val hideLater = Runnable { hide() }

    fun show(state: TakeState, elapsedS: Long) {
        main.removeCallbacks(hideLater)
        val v = view ?: attach()
        val line = v.findViewById<TextView>(R.id.pill_line)
        val primary = v.findViewById<Button>(R.id.pill_primary)
        val dot = v.findViewById<View>(R.id.pill_dot)
        // ✕ while recording, on an error and on a notice; not once Stop has handed the take on.
        v.findViewById<Button>(R.id.pill_cancel).visibility =
            if (state is TakeState.Working) View.GONE else View.VISIBLE
        when (state) {
            TakeState.Recording -> {
                line.text = "%d:%02d".format(elapsedS / 60, elapsedS % 60)
                tint(dot, R.color.dot_recording)
                primary.visibility = View.VISIBLE
                primary.setText(R.string.pill_stop)
                primary.setOnClickListener { onStop() }
            }
            is TakeState.Working -> {
                line.setText(if (state.stage == Stage.Polish) R.string.pill_polish else R.string.pill_stt)
                tint(dot, R.color.dot_working)
                primary.visibility = View.GONE
            }
            is TakeState.Error -> {
                line.text = state.message
                tint(dot, R.color.dot_error)
                primary.visibility = if (state.canRetry) View.VISIBLE else View.GONE
                primary.setText(R.string.pill_retry)
                primary.setOnClickListener { onRetry() }
            }
            TakeState.Idle -> hide()
        }
    }

    fun flash(message: String) {
        val v = view ?: attach()
        v.findViewById<TextView>(R.id.pill_line).text = message
        v.findViewById<Button>(R.id.pill_primary).visibility = View.GONE
        v.findViewById<Button>(R.id.pill_cancel).visibility = View.VISIBLE
        tint(v.findViewById(R.id.pill_dot), R.color.dot_idle)
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, FLASH_MS)
    }

    /** The platform plays nothing for a view that is not attached, so a cue on a pill just added plays once the window attaches. */
    fun cue(cue: Cue) {
        if (cue == Cue.None || !settings.hapticCues) return
        val v = view ?: return
        val feedback = if (cue == Cue.Confirm) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
        if (v.isAttachedToWindow) v.performHapticFeedback(feedback) else v.post { v.performHapticFeedback(feedback) }
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        view?.let { windows.removeView(it) }
        view = null
        params = null
    }

    fun keyboardTop(): Int? = service.windows
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().also(it::getBoundsInScreen).top }

    /** Re-place after the keyboard moved or resized; a pill the user is dragging is left alone. */
    fun follow() {
        val v = view ?: return
        val p = params ?: return
        if ((v as PillLayout).dragging) return
        p.y = topFor(v)
        windows.updateViewLayout(v, p)
    }

    @SuppressLint("InflateParams")
    private fun attach(): View {
        val v = LayoutInflater.from(service).inflate(R.layout.pill, null)
        v.findViewById<Button>(R.id.pill_cancel).setOnClickListener { onCancel() }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Screen coordinates throughout: the keyboard's bounds come from getBoundsInScreen, so the overlay ignores the system bars' insets.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Centred by the window manager so the pill stays centred as its width changes; x stays 0 and only y is ever set.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            fitInsetsTypes = 0
        }
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        p.y = topFor(v)
        dragWith(v, p)
        // A wrapping notice or a large font changes the height after placement: re-place so the bottom keeps its distance above the keyboard.
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { follow() }
        }
        windows.addView(v, p)
        view = v
        params = p
        return v
    }

    private fun topFor(v: View): Int {
        return place(
            settings.pillSaved, v.measuredHeight,
            windows.currentWindowMetrics.bounds.height(), keyboardTop(),
            margin = (MARGIN_DP * service.resources.displayMetrics.density).toInt(),
        )
    }

    private fun dragWith(v: View, p: WindowManager.LayoutParams) {
        var originY = 0
        (v as PillLayout).onDown = { originY = p.y }
        v.onDrag = { _, dy, ended ->
            p.y = originY + dy.toInt()
            windows.updateViewLayout(v, p)
            if (ended) {
                settings.pillSaved = remember(p.y, v.height, windows.currentWindowMetrics.bounds.height(), keyboardTop())
            }
        }
    }

    private fun tint(dot: View, color: Int) {
        dot.background?.mutate()?.setTint(service.getColor(color))
    }

    private companion object {
        const val FLASH_MS = 2500L
        const val MARGIN_DP = 12
    }
}

/** Takes the gesture from Stop and ✕ only past [isDrag]'s threshold, so a slightly wobbly tap still taps. */
class PillLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    var onDown: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float, ended: Boolean) -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var startX = 0f
    private var startY = 0f
    var dragging = false
        private set

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX
                startY = e.rawY
                dragging = false
                onDown?.invoke()
            }
            MotionEvent.ACTION_MOVE -> if (isDrag(e.rawX - startX, e.rawY - startY, slop)) dragging = true
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX
                startY = e.rawY
                onDown?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && isDrag(e.rawX - startX, e.rawY - startY, slop)) dragging = true
                if (dragging) onDrag?.invoke(e.rawX - startX, e.rawY - startY, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDrag?.invoke(e.rawX - startX, e.rawY - startY, true)
                dragging = false
            }
        }
        return true
    }
}
