package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset

class DictationLogViewTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(
        id: String = ID_A, atMs: Long = T0, outcome: LogOutcome = LogOutcome.Cleaned, raw: String = "", delivered: String = "",
        detail: String? = null, events: List<LogEvent> = emptyList(),
    ) = DictationEntry(id, atMs, "0.5.0 (abc1234)", outcome, raw = raw, delivered = delivered, detail = detail, events = events)

    @Test
    fun theStoreReadsNewestFirstAndIsEmptyWhileOffOrDeleted() {
        val now = T0 + 10_000
        val log = DictationLog(dictationLogDir(temp.newFolder("files")), { now })
        assertEquals("off: empty", emptyList<DictationEntry>(), newestFirst(log.entries()))
        assertTrue(log.setEnabled(true))
        assertEquals("on, nothing yet: empty", emptyList<DictationEntry>(), newestFirst(log.entries()))
        val ticket = log.begin()!!
        for ((id, at) in listOf(ID_B to T0 + 2_000, ID_A to T0, ID_C to T0 + 5_000)) {
            assertEquals(LogWrite.Saved, log.put(ticket, entry(id = id, atMs = at, raw = "r", delivered = "d")))
        }
        assertEquals(listOf(ID_C, ID_B, ID_A), newestFirst(log.entries()).map { it.id })
        assertTrue(log.deleteAll())
        assertEquals("delete all: empty", emptyList<DictationEntry>(), newestFirst(log.entries()))
        assertEquals(LogWrite.Stale, log.put(ticket, entry(id = ID_A)))
        assertTrue(log.setEnabled(false))
        assertEquals("switched off: empty", emptyList<DictationEntry>(), newestFirst(log.entries()))
    }

    @Test
    fun aCleanedEntryCopiesAsAReportWithEveryFieldItHas() {
        val full = DictationEntry(
            id = ID_A, atMs = T0, appVersion = "0.5.0 (abc1234)", outcome = LogOutcome.Cleaned,
            raw = "hello there\nfriend", delivered = "Hello there, friend", polishModel = "llama", polishTimeoutS = 20,
            sttLanguage = "auto:en,he", sttDetected = "en 0.98", sttServedBy = "api.example.com", sttMs = 812, polishMs = 300,
        )
        assertEquals(
            """
            ### Raw transcript

            ```text
            hello there
            friend
            ```

            ### Delivered text

            ```text
            Hello there, friend
            ```

            ### Expected

            ```text

            ```

            ### Details

            ```text
            Voxdroid dictation log entry
            Time: 2026-11-14 20:03:05 +00:00
            Outcome: Cleaned (Cleaned)
            App: 0.5.0 (abc1234)
            Language sent: auto:en,he
            Language detected: en 0.98
            Answered by: api.example.com
            Speech-to-text: 812 ms
            Cleanup model: llama
            Cleanup timeout: 20 s
            Cleanup: 300 ms
            Events: none
            ```
            """.trimIndent(),
            logReport(full, UTC),
        )
        assertEquals("2026-11-14 20:03 · Cleaned", logRowTitle(full, UTC))
        assertEquals("the row shows the delivered text on one line", "Hello there, friend", logRowText(full))
    }

    @Test
    fun aSilentTakeShowsNoTextPlainly() {
        val silent = entry(outcome = LogOutcome.Silence)
        assertEquals(NO_TEXT, logRowText(silent))
        assertEquals("2026-11-14 20:03 · Silence", logRowTitle(silent, UTC))
        assertTrue(logReport(silent, UTC).startsWith("### Raw transcript\n\n```text\n$NO_TEXT\n```\n\n### Delivered text\n\n```text\n$NO_TEXT\n```\n"))
        assertEquals("whitespace is no text either", NO_TEXT, shownText(" \n "))
    }

    @Test
    fun aDroppedDeliveryRowFallsBackToTheRawTranscript() {
        val dropped = entry(outcome = LogOutcome.PolishOff, raw = "  typed\tnowhere  ")
        assertEquals("typed nowhere", logRowText(dropped))
        assertTrue(logReport(dropped, UTC).contains("### Delivered text\n\n```text\n$NO_TEXT\n```\n"))
    }

    @Test
    fun aFailedThenRetriedTakeListsItsEventsInOrder() {
        val events = listOf(
            LogEvent(T0 + 1_000, LogEventKind.Transport, "stt transport: request timed out; resent once"),
            LogEvent(T0 + 2_000, LogEventKind.SttFailed, "stt HTTP 502: response content omitted"),
            LogEvent(T0 + 9_000, LogEventKind.Retry, "attempt 2"),
        )
        val failed = entry(outcome = LogOutcome.SttFailed, detail = "stt HTTP 502: response content omitted", events = events.take(2))
        val failedReport = logReport(failed, UTC)
        assertTrue(failedReport.contains("Outcome: Speech-to-text failed (SttFailed)\nDetail: stt HTTP 502: response content omitted\n"))
        assertTrue(failedReport.contains(
            "Events:\n- 2026-11-14 20:03:06 +00:00 Transport: stt transport: request timed out; resent once\n" +
                "- 2026-11-14 20:03:07 +00:00 SttFailed: stt HTTP 502: response content omitted\n```",
        ))
        assertTrue(failedReport.startsWith("### Raw transcript\n\n```text\n$NO_TEXT\n```\n"))

        val retried = entry(outcome = LogOutcome.Cleaned, raw = "ok", delivered = "Ok.", events = events)
        assertEquals(
            listOf(
                "2026-11-14 20:03:06 +00:00 Transport: stt transport: request timed out; resent once",
                "2026-11-14 20:03:07 +00:00 SttFailed: stt HTTP 502: response content omitted",
                "2026-11-14 20:03:14 +00:00 Retry: attempt 2",
            ),
            logEvents(retried, UTC),
        )
        assertTrue(logReport(retried, UTC).startsWith("### Raw transcript\n\n```text\nok\n```\n\n### Delivered text\n\n```text\nOk.\n```\n"))
    }

    @Test
    fun everyOutcomeHasALabel() {
        assertEquals(LogOutcome.entries.size, LogOutcome.entries.map(::outcomeLabel).toSet().size)
    }

    /** A record read from disk is not trusted: a planted or older file's secrets never reach the screen or the clipboard. */
    @Test
    fun credentialsInAStoredRecordAreNeitherShownNorCopied() {
        val planted = DictationEntry(
            id = ID_A, atMs = T0, appVersion = "0.5.0 (abc1234)", outcome = LogOutcome.SttFailed,
            detail = "stt HTTP 401 Authorization: Bearer $SECRET",
            sttServedBy = "user:$SECRET@api.example.com",
            events = listOf(
                LogEvent(T0, LogEventKind.Note, "https://user:$SECRET@api.example.com/v1?key=$SECRET"),
                LogEvent(T0, LogEventKind.Transport, "stt transport: host unavailable"),
            ),
        )
        val shown = listOf(logReport(planted, UTC), logRowTitle(planted, UTC), logRowText(planted)) +
            logMetadata(planted, UTC) + logEvents(planted, UTC)
        for (text in shown) assertFalse(text, text.contains(SECRET))
        val report = logReport(planted, UTC)
        assertTrue(report.contains("Detail: diagnostic detail omitted\n"))
        assertTrue(report.contains("Answered by: omitted\n"))
        assertTrue(report.contains("Note: diagnostic detail omitted\n"))
        assertTrue("positive control: a known safe detail survives", report.contains("Transport: stt transport: host unavailable\n"))
        assertTrue("positive control: a lane name survives", logMetadata(planted.copy(sttServedBy = "lane he"), UTC).contains("Answered by: lane he"))
    }

    private companion object {
        val UTC: ZoneOffset = ZoneOffset.UTC
        val T0 = Instant.parse("2026-11-14T20:03:05Z").toEpochMilli()
        const val ID_A = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        const val ID_B = "1f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        const val ID_C = "2f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        const val SECRET = "sk-test-0000secret"
    }
}
