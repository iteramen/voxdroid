package io.github.iteramen.voxdroid

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The dictation log, newest first, or with [EXTRA_ID] one entry, or with [EXTRA_REPORT] too that entry's editable report.
 * Every show re-reads [DictationLog.entries], so expiry applies; switching the log off or Delete all redraws an open viewer
 * from the emptied store.
 */
class DictationLogActivity : AppCompatActivity() {
    private val selection = LogViewSession()
    private val draft: ReportDraft by viewModels()
    private val log by lazy { DictationLog(dictationLogDir(filesDir)) }
    private val main = Handler(Looper.getMainLooper())
    private val reader = Executors.newSingleThreadExecutor()
    private val cleared: () -> Unit = { runOnUiThread { if (!isDestroyed) show() } }
    private lateinit var content: FrameLayout
    private val expiry = Runnable { show() }
    private val clockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A Recents thumbnail of a transcript would outlive Delete all and the 30 days.
        setRecentsScreenshotEnabled(false)
        // Restored without its draft, a preview would show the text again with every redaction undone: close it instead.
        if (savedInstanceState != null && intent.getBooleanExtra(EXTRA_REPORT, false) && !draft.held) {
            finish()
            return
        }
        setContentView(R.layout.section)
        content = findViewById(R.id.section_content)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(
                when {
                    intent.getBooleanExtra(EXTRA_REPORT, false) -> R.string.dictation_log_report_title
                    intent.hasExtra(EXTRA_ID) -> R.string.dictation_log_entry
                    else -> R.string.dictation_log_title
                },
            )
            setNavigationOnClickListener { finish() }
        }
        DictationLog.onDelete(cleared)
    }

    override fun onResume() {
        super.onResume()
        selection.resume()
        registerReceiver(clockChanged, IntentFilter(Intent.ACTION_TIME_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        show()
    }

    override fun onPause() {
        unregisterReceiver(clockChanged)
        selection.pause()
        main.removeCallbacks(expiry)
        keepDraft()
        content.removeAllViews()
        super.onPause()
    }

    override fun onDestroy() {
        DictationLog.removeOnDelete(cleared)
        reader.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun show() {
        val request = selection.invalidate()
        main.removeCallbacks(expiry)
        keepDraft()
        content.removeAllViews()
        if (!selection.active) return
        message(R.string.dictation_log_loading)
        reader.execute {
            val epoch = log.begin()?.epoch
            val entries = log.entries()
            main.post {
                if (isDestroyed || !selection.currentRequest(request)) return@post
                if (!selection.accept(request, epoch, entries, log.begin()?.epoch)) {
                    show()
                    return@post
                }
                render(epoch != null)
                selection.nextExpiryDelay()?.let { main.postDelayed(expiry, it) }
            }
        }
    }

    private fun render(enabled: Boolean) {
        content.removeAllViews()
        val entries = selection.entries
        val id = intent.getStringExtra(EXTRA_ID)
        val entry = entries.firstOrNull { it.id == id }
        val report = if (intent.getBooleanExtra(EXTRA_REPORT, false)) draft.textFor(entry) { reportDraft(it, ZoneId.systemDefault()) } else null
        when {
            id != null && entry == null -> message(R.string.dictation_log_entry_gone)
            entry != null && report != null -> preview(entry, report)
            entry != null -> detail(entry)
            entries.isEmpty() -> message(if (enabled) R.string.dictation_log_empty else R.string.dictation_log_off)
            else -> list(entries)
        }
    }

    private fun message(res: Int) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        content.addView(TextView(this).apply {
            setText(res)
            setPadding(pad, pad, pad, pad)
        })
    }

    private fun list(entries: List<DictationEntry>) {
        val zone = ZoneId.systemDefault()
        content.addView(ListView(this).apply {
            adapter = object : ArrayAdapter<DictationEntry>(context, R.layout.dictation_log_row, entries) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: layoutInflater.inflate(R.layout.dictation_log_row, parent, false)
                    val entry = entries[position]
                    row.findViewById<TextView>(R.id.log_row_title).text = logRowTitle(entry, zone)
                    row.findViewById<TextView>(R.id.log_row_text).text = logRowText(entry)
                    return row
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                val entry = selection.currentEntry(entries[position].id, log.begin()?.epoch)
                if (entry == null) show() else startActivity(DictationLogActivity.intent(context, entry.id))
            }
        })
    }

    private fun detail(entry: DictationEntry) {
        val zone = ZoneId.systemDefault()
        val view = layoutInflater.inflate(R.layout.dictation_log_entry, content, false)
        content.addView(view)
        view.findViewById<TextView>(R.id.log_entry_outcome).text = logRowTitle(entry, zone)
        transcript(view.findViewById(R.id.log_entry_raw), entry.raw)
        transcript(view.findViewById(R.id.log_entry_delivered), entry.delivered)
        view.findViewById<TextView>(R.id.log_entry_meta).text = logMetadata(entry, zone).joinToString("\n")
        view.findViewById<TextView>(R.id.log_entry_events).text =
            logEvents(entry, zone).joinToString("\n").ifEmpty { getString(R.string.dictation_log_no_events) }
        // Side by side only when each column keeps room for its text at the user's font size.
        val wide = resources.configuration.run { screenWidthDp >= 600 * fontScale }
        view.findViewById<LinearLayout>(R.id.log_entry_texts).orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (box in listOf(R.id.log_entry_raw_box, R.id.log_entry_delivered_box)) {
            view.findViewById<View>(box).layoutParams =
                LinearLayout.LayoutParams(if (wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (wide) 1f else 0f)
        }
        view.findViewById<View>(R.id.log_entry_copy).setOnClickListener { current(entry)?.let { copy(logReport(it, zone)) } }
        view.findViewById<View>(R.id.log_entry_report).setOnClickListener {
            current(entry)?.let { startActivity(DictationLogActivity.intent(this, it.id, report = true)) }
        }
    }

    /** Nothing leaves the phone from here until Continue, and then only the text as edited. */
    private fun preview(entry: DictationEntry, text: String) {
        val view = layoutInflater.inflate(R.layout.dictation_log_report, content, false)
        content.addView(view)
        val editor = view.findViewById<EditText>(R.id.log_report_draft)
        editor.setText(text)
        editor.setSelection(draft.cursor.coerceIn(0, editor.length()))
        val status = view.findViewById<TextView>(R.id.log_report_status)
        view.findViewById<View>(R.id.log_report_copy).setOnClickListener { current(entry)?.let { copy(editor.text.toString()) } }
        view.findViewById<View>(R.id.log_report_cancel).setOnClickListener { finish() }
        view.findViewById<View>(R.id.log_report_continue).setOnClickListener {
            val current = current(entry) ?: return@setOnClickListener
            val url = reportUrl(reportTitle(current), editor.text.toString())
            if (url.length >= REPORT_URL_MAX) {
                status.text = getString(R.string.dictation_log_report_too_long, url.length, REPORT_URL_MAX)
                return@setOnClickListener
            }
            // The selector names any browser, so an app that claims github.com links cannot take this one.
            val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { selector = browser })
                status.text = ""
            } catch (e: ActivityNotFoundException) {
                status.setText(R.string.dictation_log_report_no_browser)
            }
        }
    }

    /** The entry while it is still in the log; otherwise `null`, and the screen redraws without it. */
    private fun current(entry: DictationEntry): DictationEntry? =
        selection.currentEntry(entry.id, log.begin()?.epoch).also { if (it == null) show() }

    private fun copy(text: String) {
        val clip = ClipData.newPlainText(getString(R.string.dictation_log_entry), text)
        // Sensitive, so Android shows no preview of the transcript.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun keepDraft() {
        val editor = content.findViewById<EditText>(R.id.log_report_draft) ?: return
        draft.keep(editor.text.toString(), editor.selectionEnd)
    }

    private fun transcript(view: TextView, text: String) {
        view.text = shownText(text)
        view.setTypeface(null, if (text.isBlank()) Typeface.ITALIC else Typeface.NORMAL)
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_REPORT = "report"

        fun intent(context: Context, id: String? = null, report: Boolean = false): Intent =
            Intent(context, DictationLogActivity::class.java).apply {
                id?.let { putExtra(EXTRA_ID, it) }
                if (report) putExtra(EXTRA_REPORT, true)
            }
    }
}
