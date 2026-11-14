package io.github.iteramen.voxdroid

import androidx.lifecycle.ViewModel
import java.net.URLEncoder
import java.time.ZoneId

/** A report link stays under this many bytes, fully encoded. */
internal const val REPORT_URL_MAX = 8 * 1024

private const val REPORT_URL = "https://github.com/iteramen/voxdroid/issues/new?template=cleanup-miss.md"

/** No transcript text: the title is not part of the reviewed draft. */
internal fun reportTitle(entry: DictationEntry): String = "Cleanup miss: " + outcomeLabel(entry.outcome)

/** `%20`, not the form encoding's `+`, so the text survives a reader that takes the query literally. */
private fun encoded(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** The new-issue link carrying [body]. Opening it sends the body to GitHub; the caller checks it against [REPORT_URL_MAX]. */
internal fun reportUrl(title: String, body: String): String = REPORT_URL + "&title=" + encoded(title) + "&body=" + encoded(body)

/**
 * [logReport] for [entry], whole when its link fits [REPORT_URL_MAX]; otherwise without the events, and then with both texts
 * cut to the longest shared length that fits.
 */
internal fun reportDraft(entry: DictationEntry, zone: ZoneId): String {
    fun fits(body: String) = reportUrl(reportTitle(entry), body).length < REPORT_URL_MAX
    val whole = logReport(entry, zone)
    if (fits(whole)) return whole
    val withoutEvents = logReport(entry, zone, withEvents = false)
    if (fits(withoutEvents)) return withoutEvents
    var low = 0
    // A text of REPORT_URL_MAX units cannot fit, so the search never encodes more of a long take than that.
    var high = minOf(maxOf(entry.raw.length, entry.delivered.length) - 1, REPORT_URL_MAX)
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(logReport(entry, zone, mid, withEvents = false))) low = mid else high = mid - 1
    }
    return logReport(entry, zone, low, withEvents = false)
}

/** The report text under edit. Held in memory only, across a redraw or a rotation; it goes when its entry does. */
internal class ReportDraft : ViewModel() {
    private var text: String? = null
    var cursor = 0
        private set

    /** False until a draft exists and once it is dropped; so also in a preview recreated after its process died, whose edits went with it. */
    val held: Boolean get() = text != null

    fun keep(text: String, cursor: Int) {
        this.text = text
        this.cursor = cursor
    }

    /** The kept text, else a [fresh] one; `null`, forgetting the kept text, when the entry is gone. */
    fun textFor(entry: DictationEntry?, fresh: (DictationEntry) -> String): String? {
        if (entry == null) {
            text = null
            cursor = 0
            return null
        }
        return text ?: fresh(entry).also { text = it }
    }
}
