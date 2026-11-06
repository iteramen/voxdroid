package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.PersistableBundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/** Our own non-focusable surface; no native chat view or input connection is modified. */
internal class CaptionPanel(
    private val service: AccessibilityService,
    private val onClose: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var text: TextView? = null
    private var status: TextView? = null
    private var copy: Button? = null
    private var refresh: Button? = null
    private var displayed: String? = null

    fun working(source: CaptionApp) {
        create()
        displayed = null
        status?.text = service.getString(R.string.caption_working, source.label)
        text?.text = service.getString(R.string.caption_acquiring)
        copy?.isEnabled = false
        refresh?.isEnabled = false
    }

    fun show(outcome: CaptionOutcome.Captioned, remembered: Boolean) {
        create()
        displayed = outcome.displayed
        text?.text = outcome.displayed
        val saved = outcome.savedAtMs?.let {
            service.getString(R.string.caption_saved, DateFormat.getMediumDateFormat(service).format(Date(it)), DateFormat.getTimeFormat(service).format(Date(it)))
        }
        status?.text = listOfNotNull(
            saved ?: service.getString(if (remembered) R.string.caption_remembered else R.string.caption_private),
            if (outcome.cleanup == CaptionCleanup.Fallback) service.getString(R.string.caption_raw_fallback) else null,
        ).joinToString("\n")
        copy?.isEnabled = true
        refresh?.isEnabled = true
    }

    fun notice(message: String) {
        create()
        displayed = null
        status?.text = service.getString(R.string.caption_title)
        text?.text = message
        copy?.isEnabled = false
        refresh?.isEnabled = false
    }

    fun hide() {
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        text = null
        status = null
        copy = null
        refresh = null
        displayed = null
    }

    private fun create() {
        if (root != null) return
        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(0xff202124.toInt())
            elevation = dp(12).toFloat()
        }
        status = TextView(service).apply {
            setTextColor(0xffbdc1c6.toInt())
            textSize = 13f
        }.also(box::addView)
        text = TextView(service).apply {
            setTextColor(0xffffffff.toInt())
            textSize = 18f
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(0, dp(8), 0, dp(8))
        }
        val scroll = ScrollView(service).apply { addView(text) }
        val available = windows.currentWindowMetrics.bounds.height()
        box.addView(scroll, LinearLayout.LayoutParams(-1, minOf(dp(240), available / 3)))
        val buttons = LinearLayout(service)
        fun button(label: Int, action: () -> Unit): Button = Button(service).apply {
            setText(label)
            isAllCaps = false
            setOnClickListener { action() }
        }.also { buttons.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        copy = button(R.string.caption_copy) {
            displayed?.let { value ->
                val clip = ClipData.newPlainText(service.getString(R.string.caption_title), value)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
        }
        button(R.string.caption_close, onClose)
        box.addView(buttons)
        // Keep the long action readable at large accessibility font sizes.
        refresh = Button(service).apply {
            setText(R.string.caption_retranscribe)
            isAllCaps = false
            setOnClickListener { onRefresh() }
        }.also { box.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        val params = WindowManager.LayoutParams(
            minOf(windows.currentWindowMetrics.bounds.width() - dp(24), dp(540)), -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        windows.addView(box, params)
        root = box
    }
}
