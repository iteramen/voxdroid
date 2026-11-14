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

/** Every field but the text. Details pass [safeDetail] again: a record read from disk is not trusted to have passed it at write. */
internal fun logMetadata(entry: DictationEntry, zone: ZoneId): List<String> = listOfNotNull(
    "Time: " + logTime(entry.atMs, zone),
    "Outcome: " + outcomeLabel(entry.outcome) + " (" + entry.outcome.name + ")",
    entry.detail?.let { "Detail: " + safeDetail(it) },
    "App: " + entry.appVersion,
    entry.sttLanguage?.let { "Language sent: $it" },
    entry.sttDetected?.let { "Language detected: $it" },
    entry.sttServedBy?.let { "Answered by: " + if (SERVED_BY.matches(it)) it else "omitted" },
    entry.sttMs?.let { "Speech-to-text: $it ms" },
    entry.polishModel?.let { "Cleanup model: $it" },
    entry.polishTimeoutS?.let { "Cleanup timeout: $it s" },
    entry.polishMs?.let { "Cleanup: $it ms" },
)

/** Retries, failures, disconnects and notes, in the order they happened. */
internal fun logEvents(entry: DictationEntry, zone: ZoneId): List<String> =
    entry.events.map { logTime(it.atMs, zone) + " " + it.kind.name + ": " + safeDetail(it.detail) }

/** The Copy text: metadata, events, then both texts, ready to paste into a report. */
internal fun logReport(entry: DictationEntry, zone: ZoneId): String = buildString {
    appendLine("Voxdroid dictation log entry")
    logMetadata(entry, zone).forEach(::appendLine)
    val events = logEvents(entry, zone)
    if (events.isEmpty()) appendLine("Events: none") else {
        appendLine("Events:")
        events.forEach { appendLine("- $it") }
    }
    appendLine("Raw transcript:")
    appendLine(shownText(entry.raw))
    appendLine("Delivered text:")
    append(shownText(entry.delivered))
}
