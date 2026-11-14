package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset

class DictationReportTest {

    private fun entry(
        raw: String = "", delivered: String = "", outcome: LogOutcome = LogOutcome.Cleaned, events: List<LogEvent> = emptyList(),
    ) = DictationEntry(
        id = ID, atMs = T0, appVersion = "0.5.0 (abc1234)", outcome = outcome, raw = raw, delivered = delivered,
        polishModel = "llama", polishTimeoutS = 20, sttLanguage = "auto:he,ru", sttDetected = "he 0.98",
        sttServedBy = "api.example.com", sttMs = 812, polishMs = 300, events = events,
    )

    private fun url(entry: DictationEntry) = reportUrl(reportTitle(entry), reportDraft(entry, UTC))

    /** The query as GitHub reads it. `URLDecoder` would also turn a `+` into a space, so a raw `+` in the link fails here. */
    private fun query(url: String): Map<String, String> {
        assertTrue("only link-safe ASCII", Regex("""[A-Za-z0-9%._*:/?&=-]+""").matches(url))
        return url.substringAfter('?').split('&').associate {
            assertFalse("no form-encoded space: $it", it.contains('+'))
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
    }

    @Test
    fun theLinkOpensTheCleanupMissTemplateWithTheTitleAndTheWholeBody() {
        val take = entry(raw = "hello there friend", delivered = "Hello there, friend.")
        val link = url(take)
        assertTrue(link.startsWith("https://github.com/iteramen/voxdroid/issues/new?template=cleanup-miss.md&title="))
        val query = query(link)
        assertEquals(setOf("template", "title", "body"), query.keys)
        assertEquals("Cleanup miss: Cleaned", query["title"])
        assertEquals("a report that fits is the Copy text, unchanged", logReport(take, UTC), query["body"])
    }

    @Test
    fun hebrewCyrillicEmojiPunctuationAndLineBreaksSurviveTheLink() {
        val raw = "שלום עולם, מה נשמע?\nпривет мир — «как дела»\r\n100% a+b=c&d #1 @someone <b>x</b> 🎤\ttab"
        val take = entry(raw = raw, delivered = "שלום עולם.\n\nПривет, мир!")
        val body = query(url(take)).getValue("body")
        assertEquals(logReport(take, UTC), body)
        assertTrue("the raw text is whole, inside its block", body.contains("### Raw transcript\n\n```text\n$raw\n```\n"))
        assertTrue(body.contains("### Delivered text\n\n```text\nשלום עולם.\n\nПривет, мир!\n```\n"))
    }

    @Test
    fun aTranscriptCannotCloseItsCodeBlock() {
        val body = logReport(entry(raw = "before\n```\n@everyone ### not a heading\n````` five"), UTC)
        assertTrue(body.contains("### Raw transcript\n\n``````text\nbefore\n```\n@everyone ### not a heading\n````` five\n``````\n\n### Delivered"))
    }

    @Test
    fun aSilentOrFailedTakeReportsNoTextAndKeepsItsMetadata() {
        for (outcome in listOf(LogOutcome.Silence, LogOutcome.SttFailed, LogOutcome.RecordingFailed)) {
            val query = query(url(entry(outcome = outcome)))
            val body = query.getValue("body")
            assertEquals("Cleanup miss: " + outcomeLabel(outcome), query["title"])
            assertTrue(body.startsWith("### Raw transcript\n\n```text\n$NO_TEXT\n```\n\n### Delivered text\n\n```text\n$NO_TEXT\n```\n\n$EXPECTED"))
            assertTrue(body.contains("Outcome: ${outcomeLabel(outcome)} (${outcome.name})\n"))
        }
    }

    @Test
    fun aLongMultiByteTakeIsCutToFitWithANoteOnEachText() {
        val raw = "שלום ".repeat(2_000)
        val delivered = "Привет 🎤 ".repeat(1_500)
        val take = entry(raw = raw, delivered = delivered, events = EVENTS)
        val link = url(take)
        assertTrue("under the limit, was ${link.length}", link.length < REPORT_URL_MAX)
        assertTrue("and close to it, was ${link.length}", link.length > REPORT_URL_MAX - 64)
        val body = query(link).getValue("body")
        val kept = Regex("""the first (\d+) of (\d+) characters are shown""").findAll(body).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        assertEquals("one note per text, with its real length", listOf(raw.length, delivered.length), kept.map { it.second })
        assertTrue("both texts keep a usable share: $kept", kept.all { it.first > 150 })
        assertTrue(body.contains("```text\n" + raw.take(kept[0].first) + "\n```\n\nTruncated to fit the link: the first "))
        assertTrue(body.contains("```text\n" + delivered.take(kept[1].first) + "\n```\n\nTruncated to fit the link: the first "))
        assertFalse("no half of a surrogate pair, which encodes as ?", body.contains('?'))
        assertTrue("the Expected block survives", body.contains("\n\n$EXPECTED"))
        assertTrue("so does the metadata", body.contains("Language sent: auto:he,ru\nLanguage detected: he 0.98\n"))
        assertTrue("the events are dropped first, and said to be", body.contains("\nEvents: 2 omitted to fit the link\n```"))
    }

    @Test
    fun eventsAloneOverTheLimitAreDroppedBeforeAnyTextIsCut() {
        val many = (1..200).map { LogEvent(T0 + it, LogEventKind.Transport, "stt transport: request timed out; resent once") }
        val take = entry(raw = "short", delivered = "Short.", events = many)
        val body = reportDraft(take, UTC)
        assertTrue(reportUrl(reportTitle(take), body).length < REPORT_URL_MAX)
        assertTrue(body.contains("```text\nshort\n```\n\n### Delivered text\n\n```text\nShort.\n```\n\n$EXPECTED"))
        assertTrue(body.contains("\nEvents: 200 omitted to fit the link\n"))
        assertFalse(body.contains("Truncated"))
    }

    /** What a report costs before any text: bounded whatever the settings and the server put in the metadata. */
    @Test
    fun theOverheadWithoutTextIsFixedAndSmall() {
        val wide = "שלום🎤".repeat(5_000)
        val worst = DictationEntry(
            id = ID, atMs = T0, appVersion = wide, outcome = LogOutcome.CleanupBlocked, detail = "polish: insecure endpoint blocked",
            polishModel = wide, polishTimeoutS = Int.MAX_VALUE, sttLanguage = wide, sttDetected = wide, sttServedBy = "a".repeat(253),
            sttMs = Long.MAX_VALUE, polishMs = Long.MAX_VALUE, events = EVENTS,
        )
        val overhead = reportUrl(reportTitle(worst), logReport(worst, UTC, textChars = 0, withEvents = false)).length
        assertTrue("worst-case overhead $overhead leaves over half the link for text", overhead < REPORT_URL_MAX / 2)
        assertTrue(url(entry()).length < 1_200)
        val body = query(url(worst.copy(raw = wide, delivered = wide))).getValue("body")
        assertTrue(body.contains(EXPECTED))
        assertFalse(body.contains('?'))
    }

    @Test
    fun secretsInAStoredRecordNeverReachTheLink() {
        val planted = DictationEntry(
            id = ID, atMs = T0, appVersion = "0.5.0 (abc1234)", outcome = LogOutcome.SttFailed,
            detail = "stt HTTP 401 Authorization: Bearer $SECRET", sttServedBy = "user:$SECRET@api.example.com",
            sttDetected = "$SECRET 0.99",
            events = listOf(LogEvent(T0, LogEventKind.Note, "https://user:$SECRET@api.example.com/v1?key=$SECRET")),
        )
        val link = url(planted)
        val body = query(link).getValue("body")
        assertFalse(link.contains(SECRET))
        assertFalse(body.contains(SECRET))
        assertTrue("positive control: the omission is what the body carries", body.contains("Detail: diagnostic detail omitted\n"))
        assertTrue("a server's own key for the language is not a language", body.contains("Language detected: omitted\n"))
        assertTrue("positive control: a real one survives", url(entry()).contains("Language%20detected%3A%20he%200.98"))
        assertFalse("the title carries no text of the take", reportTitle(entry(raw = "private words")).contains("private"))
    }

    @Test
    fun aDraftIsKeptWhileItsEntryLivesAndForgottenWithIt() {
        val draft = ReportDraft()
        val take = entry(raw = "private words")
        assertEquals("generated", draft.textFor(take) { "generated" })
        draft.keep("redacted", 3)
        assertEquals("the edit survives a redraw", "redacted", draft.textFor(take) { "generated" })
        assertEquals(3, draft.cursor)
        assertNull("deleted, switched off or expired", draft.textFor(null) { "generated" })
        assertEquals(0, draft.cursor)
        assertEquals("nothing of the old draft comes back", "fresh", draft.textFor(take) { "fresh" })
    }

    private companion object {
        val UTC: ZoneOffset = ZoneOffset.UTC
        val T0 = Instant.parse("2026-11-14T20:03:05Z").toEpochMilli()
        const val ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        const val SECRET = "sk-test-0000secret"
        const val EXPECTED = "### Expected\n\n```text\n\n```\n\n### Details\n\n```text\nVoxdroid dictation log entry\n"
        val EVENTS = listOf(
            LogEvent(T0 + 1_000, LogEventKind.SttFailed, "stt HTTP 502: response content omitted"),
            LogEvent(T0 + 9_000, LogEventKind.Retry, "attempt 2"),
        )
    }
}
