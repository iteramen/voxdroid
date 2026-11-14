package io.github.iteramen.voxdroid

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the viewer and Copy show for an empty transcript, so a silent or failed take is plainly textless. */
internal const val NO_TEXT = "(no text)"

private val FULL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
private val ROW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** A lane code, `default` or a bare host, as `SttTrace` names them; anything else may carry userinfo, path or query. */
private val SERVED_BY = Regex("""lane [a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*|default|\[[0-9A-Fa-f:.]{2,45}]|[A-Za-z0-9.-]{1,253}""")

/** A language code and its score, as `SttTrace` writes them; with no candidates sent, the code is whatever key the server chose. */
private val DETECTED = Regex("""[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})* [0-9]\.[0-9]{2}""")

internal fun newestFirst(entries: List<DictationEntry>): List<DictationEntry> = entries.sortedByDescending { it.atMs }

/** Fixed English, so a pasted report reads the same from every phone. */
internal fun outcomeLabel(outcome: LogOutcome): String = when (outcome) {
    LogOutcome.Cleaned -> "Cleaned"
    LogOutcome.CleanupFailed -> "Cleanup failed, raw text delivered"
    LogOutcome.CleanupBlocked -> "Cleanup blocked (insecure endpoint), raw text delivered"
    LogOutcome.SkippedShort -> "Too short for cleanup"
    LogOutcome.PolishOff -> "Cleanup off"
    LogOutcome.Silence -> "Silence"
    LogOutcome.SttFailed -> "Speech-to-text failed"
    LogOutcome.RecordingFailed -> "Recording failed"
}

internal fun logTime(atMs: Long, zone: ZoneId): String = FULL_TIME.withZone(zone).format(Instant.ofEpochMilli(atMs))

internal fun logRowTitle(entry: DictationEntry, zone: ZoneId): String =
    ROW_TIME.withZone(zone).format(Instant.ofEpochMilli(entry.atMs)) + " · " + outcomeLabel(entry.outcome)

/** The delivered text, else the raw transcript, on one line; the row's TextView ellipsizes it. */
internal fun logRowText(entry: DictationEntry): String =
    entry.delivered.ifBlank { entry.raw }.trim().replace(Regex("""\s+"""), " ").ifEmpty { NO_TEXT }

internal fun shownText(text: String): String = text.ifBlank { NO_TEXT }

/** A settings or server value on a metadata line, bounded so the lines stay a small, fixed share of a report link. */
private const val MAX_VALUE_CHARS = 100

/** The first [chars] UTF-16 units, never half of a surrogate pair. */
internal fun String.cut(chars: Int): String = when {
    length <= chars -> this
    chars > 0 && this[chars - 1].isHighSurrogate() -> substring(0, chars - 1)
    else -> substring(0, chars.coerceAtLeast(0))
}

private fun String.value(): String = if (length <= MAX_VALUE_CHARS) this else cut(MAX_VALUE_CHARS) + "…"

/** Every field but the text. Details pass [safeDetail] again: a record read from disk is not trusted to have passed it at write. */
internal fun logMetadata(entry: DictationEntry, zone: ZoneId): List<String> = listOfNotNull(
    "Time: " + logTime(entry.atMs, zone),
    "Outcome: " + outcomeLabel(entry.outcome) + " (" + entry.outcome.name + ")",
    entry.detail?.let { "Detail: " + safeDetail(it) },
    "App: " + entry.appVersion.value(),
    entry.sttLanguage?.let { "Language sent: " + it.value() },
    entry.sttDetected?.let { "Language detected: " + if (DETECTED.matches(it)) it else "omitted" },
    entry.sttServedBy?.let { "Answered by: " + if (SERVED_BY.matches(it)) it.value() else "omitted" },
    entry.sttMs?.let { "Speech-to-text: $it ms" },
    entry.polishModel?.let { "Cleanup model: " + it.value() },
    entry.polishTimeoutS?.let { "Cleanup timeout: $it s" },
    entry.polishMs?.let { "Cleanup: $it ms" },
)

/** Retries, failures, disconnects and notes, in the order they happened. */
internal fun logEvents(entry: DictationEntry, zone: ZoneId): List<String> =
    entry.events.map { logTime(it.atMs, zone) + " " + it.kind.name + ": " + safeDetail(it.detail) }

/** A Markdown code block no content can close or escape: a transcript's mentions, links and markup stay literal text. */
private fun fenced(text: String): String {
    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
    return fence + "text\n" + text + "\n" + fence
}

/**
 * The report body, for Copy and for the issue link alike: both texts, an empty Expected block for the reporter, then metadata
 * and events. [textChars] shortens each text and [withEvents] drops the events, each with a line that says so.
 */
internal fun logReport(entry: DictationEntry, zone: ZoneId, textChars: Int = Int.MAX_VALUE, withEvents: Boolean = true): String {
    fun text(title: String, text: String): String {
        val shown = text.cut(textChars)
        val note = if (shown.length == text.length) "" else
            "\n\nTruncated to fit the link: the first ${shown.length} of ${text.length} characters are shown."
        return "### $title\n\n" + fenced(shownText(shown)) + note
    }
    val events = logEvents(entry, zone)
    val details = listOf("Voxdroid dictation log entry") + logMetadata(entry, zone) + when {
        events.isEmpty() -> listOf("Events: none")
        withEvents -> listOf("Events:") + events.map { "- $it" }
        else -> listOf("Events: ${events.size} omitted to fit the link")
    }
    return listOf(
        text("Raw transcript", entry.raw),
        text("Delivered text", entry.delivered),
        "### Expected\n\n" + fenced(""),
        "### Details\n\n" + fenced(details.joinToString("\n")),
    ).joinToString("\n\n")
}
