package io.github.iteramen.voxdroid

import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The dictation log, newest first, or with [EXTRA_ID] one entry. Every show re-reads [DictationLog.entries], so expiry applies;
 * switching the log off or Delete all redraws an open viewer from the emptied store.
 */
class DictationLogActivity : AppCompatActivity() {
    private val selection = LogViewSession()
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
        setContentView(R.layout.section)
        content = findViewById(R.id.section_content)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(if (intent.hasExtra(EXTRA_ID)) R.string.dictation_log_entry else R.string.dictation_log_title)
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
        when {
            id != null -> entries.firstOrNull { it.id == id }?.let(::detail) ?: message(R.string.dictation_log_entry_gone)
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
        view.findViewById<View>(R.id.log_entry_copy).setOnClickListener {
            val current = selection.currentEntry(entry.id, log.begin()?.epoch)
            if (current == null) { show(); return@setOnClickListener }
            val clip = ClipData.newPlainText(getString(R.string.dictation_log_entry), logReport(current, zone))
            // Sensitive, so Android shows no preview of the transcript.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
    }

    private fun transcript(view: TextView, text: String) {
        view.text = shownText(text)
        view.setTypeface(null, if (text.isBlank()) Typeface.ITALIC else Typeface.NORMAL)
    }

    companion object {
        private const val EXTRA_ID = "id"

        fun intent(context: Context, id: String? = null): Intent =
            Intent(context, DictationLogActivity::class.java).apply { id?.let { putExtra(EXTRA_ID, it) } }
    }
}
