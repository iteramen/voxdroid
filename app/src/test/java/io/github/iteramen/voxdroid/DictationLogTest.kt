package io.github.iteramen.voxdroid

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

class DictationLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var time = T0
    private lateinit var filesDir: File
    private lateinit var dir: File
    private lateinit var log: DictationLog
    private lateinit var capture: DictationCapture
    private val unsaved = mutableListOf<LogWrite>()

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        dir = dictationLogDir(filesDir)
        log = DictationLog(dir, { time })
        capture = DictationCapture(log, "0.5.0 (abc1234)", { time }, unsaved = { unsaved += it })
    }

    @After
    fun tearDown() = capture.close()

    private fun on(): DictationLogTicket {
        assertTrue(log.setEnabled(true))
        return log.begin()!!
    }

    private fun entry(id: String = ID, atMs: Long = time, outcome: LogOutcome = LogOutcome.Cleaned) =
        DictationEntry(id, atMs, "v", outcome, raw = "raw", delivered = "Raw.")

    private fun files() = dir.listFiles().orEmpty().map { it.name }.filter { it != DictationLog.STATE }.toSet()

    @Test
    fun isOffByDefaultAndCapturesNothing() {
        assertFalse(log.enabled)
        capture.begin(1)
        capture.attempt(1, "en")
        assertFalse("no record is opened while off", capture.holding)
        capture.finish(1, LogOutcome.PolishOff, "hello", "hello")
        assertEquals(emptyList<DictationEntry>(), log.entries())
        assertFalse("no directory is created while off", dir.exists())
    }

    @Test
    fun anEntryRoundTripsEveryFieldAndOmitsWhatDidNotRun() {
        val full = DictationEntry(
            id = ID, atMs = T0, appVersion = "0.5.0 (abc1234)", outcome = LogOutcome.CleanupFailed,
            raw = "שלום, Ольга 👩‍👩‍👧 \"quoted\" \\ back\nslash", delivered = "", detail = "polish HTTP 502: response content omitted",
            polishModel = "llama", polishTimeoutS = 20, sttLanguage = "auto:en,he", sttDetected = "he 0.93",
            sttServedBy = "lane he", sttMs = 1234, polishMs = 567,
            events = listOf(LogEvent(T0 + 1, LogEventKind.Transport, "stt transport: request timed out; resent once")),
        )
        assertEquals(full, dictationEntryOf(JSONObject(full.toJson().toString())))

        val bare = DictationEntry(ID, T0, "v", LogOutcome.Silence)
        val json = bare.toJson()
        for (key in listOf("detail", "polish_model", "polish_timeout_s", "stt_language", "stt_detected", "stt_served_by", "stt_ms", "polish_ms")) {
            assertFalse("$key is absent when not recorded", json.has(key))
        }
        assertEquals(bare, dictationEntryOf(json))

        val ticket = on()
        assertEquals(LogWrite.Saved, log.put(ticket, full))
        assertEquals(listOf(full), log.entries())
    }

    @Test
    fun aMalformedOrFutureFormatRecordReadsAsNothing() {
        assertNull(dictationEntryOf(JSONObject(entry().toJson().toString()).put("v", DICTATION_LOG_FORMAT + 1)))
        assertNull(dictationEntryOf(JSONObject(entry().toJson().toString()).put("outcome", "Exploded")))
        assertNull(dictationEntryOf(JSONObject(entry().toJson().toString()).apply { remove("raw") }))
    }

    @Test
    fun aCleanedTakeRecordsItsStagesAndMetadata() {
        on()
        capture.begin(1)
        capture.attempt(1, "auto:en,he")
        capture.stt(1, "en 0.97", "stt.example", 800)
        capture.polished(1, "llama", 20, 300)
        capture.finish(1, LogOutcome.Cleaned, "hello there", "Hello there")

        val saved = log.entries().single()
        assertEquals(LogOutcome.Cleaned, saved.outcome)
        assertEquals("hello there", saved.raw)
        assertEquals("Hello there", saved.delivered)
        assertEquals("auto:en,he", saved.sttLanguage)
        assertEquals("en 0.97", saved.sttDetected)
        assertEquals("stt.example", saved.sttServedBy)
        assertEquals(800L, saved.sttMs)
        assertEquals("llama", saved.polishModel)
        assertEquals(20, saved.polishTimeoutS)
        assertEquals(300L, saved.polishMs)
        assertEquals("0.5.0 (abc1234)", saved.appVersion)
        assertEquals(T0, saved.atMs)
        assertEquals(emptyList<LogEvent>(), saved.events)
        assertFalse("a finished take is not held", capture.holding)
        assertEquals(emptyList<LogWrite>(), unsaved)
    }

    @Test
    fun aSilentTakeIsRecordedWithEmptyText() {
        on()
        capture.begin(1)
        capture.attempt(1, "en")
        capture.stt(1, null, "stt.example", 400)
        capture.finish(1, LogOutcome.Silence, "", "")

        val saved = log.entries().single()
        assertEquals(LogOutcome.Silence, saved.outcome)
        assertEquals("", saved.raw)
        assertEquals("", saved.delivered)
        assertNull("no polish stage ran", saved.polishModel)
        assertNull(saved.polishMs)
    }

    @Test
    fun aFailedTakeIsRecordedAtOnceWithoutInventedText() {
        on()
        capture.begin(1)
        capture.attempt(1, "he")
        capture.failed(1, "stt HTTP 502: response content omitted")

        val saved = log.entries().single()
        assertEquals(LogOutcome.SttFailed, saved.outcome)
        assertEquals("", saved.raw)
        assertEquals("", saved.delivered)
        assertEquals("stt HTTP 502: response content omitted", saved.detail)
        assertNull("no detection invented", saved.sttDetected)
        assertEquals(listOf(LogEventKind.SttFailed), saved.events.map { it.kind })
        assertTrue("kept open for a Retry", capture.holding)
    }

    @Test
    fun aRecordingFailureIsItsOwnRecord() {
        on()
        capture.begin(1)
        capture.recordingFailed(1, "mic: permission missing")

        val saved = log.entries().single()
        assertEquals(LogOutcome.RecordingFailed, saved.outcome)
        assertEquals("mic: permission missing", saved.detail)
        assertNull(saved.sttLanguage)
        assertFalse(capture.holding)
    }

    @Test
    fun aFailedAttemptThenASuccessfulRetryIsOneRecordKeepingTheFailure() {
        on()
        capture.begin(1)
        capture.attempt(1, "en")
        capture.note(1, "stt transport: connection unavailable; resent once")
        capture.failed(1, "stt transport: connection unavailable")
        val failedId = log.entries().single().id
        time += 5_000
        capture.attempt(1, "en")
        capture.stt(1, null, "stt.example", 700)
        capture.finish(1, LogOutcome.PolishOff, "second go", "second go")

        val saved = log.entries().single()
        assertEquals("the Retry rewrites the failed take's record", failedId, saved.id)
        assertEquals(LogOutcome.PolishOff, saved.outcome)
        assertNull("the outcome detail is the final attempt's", saved.detail)
        assertEquals("second go", saved.raw)
        assertEquals(T0, saved.atMs)
        assertEquals(listOf(LogEventKind.Transport, LogEventKind.SttFailed, LogEventKind.Retry), saved.events.map { it.kind })
        assertEquals("stt transport: connection unavailable", saved.events[1].detail)
        assertEquals("attempt 2", saved.events[2].detail)
        assertEquals(T0 + 5_000, saved.events[2].atMs)
    }

    @Test
    fun disconnectsAndProtocolNotesAttachToTheirTake() {
        on()
        capture.begin(1)
        capture.attempt(1, "auto:en,he")
        capture.note(1, "stt transport: request timed out; resent once (lane he)")
        capture.note(1, "stt: the server rejected language candidates; resent with standard fields")
        capture.note(1, "stt[he] lane returned nothing; retrying the default endpoint")
        capture.finish(1, LogOutcome.CleanupFailed, "raw", "raw", "polish transport: connection unavailable")

        val saved = log.entries().single()
        assertEquals(listOf(LogEventKind.Transport, LogEventKind.Note, LogEventKind.Note), saved.events.map { it.kind })
        assertEquals("polish transport: connection unavailable", saved.detail)
    }

    @Test
    fun aStaleTakesLateCallbacksTouchNothing() {
        on()
        capture.begin(1)
        capture.attempt(1, "en")
        capture.begin(2)
        capture.attempt(2, "he")
        capture.note(1, "stt transport: request timed out; resent once")
        capture.stt(1, "en 0.99", "old.example", 9_999)
        capture.polished(1, "old", 1, 1)
        capture.failed(1, "stt HTTP 500: response content omitted")
        capture.finish(1, LogOutcome.Cleaned, "old take", "old take")
        assertEquals("take 1 writes nothing once take 2 is open", emptyList<DictationEntry>(), log.entries())

        capture.finish(2, LogOutcome.PolishOff, "new take", "new take")
        val saved = log.entries().single()
        assertEquals("new take", saved.raw)
        assertEquals("he", saved.sttLanguage)
        assertNull(saved.sttServedBy)
        assertEquals(emptyList<LogEvent>(), saved.events)

        capture.finish(2, LogOutcome.Cleaned, "again", "again")
        assertEquals("a finished take cannot be finished twice", "new take", log.entries().single().raw)
    }

    @Test
    fun aDroppedTakeIsNotRecorded() {
        on()
        capture.begin(1)
        capture.attempt(1, "en")
        capture.drop()
        capture.finish(1, LogOutcome.PolishOff, "cancelled", "cancelled")
        assertEquals(emptyList<DictationEntry>(), log.entries())
    }

    @Test
    fun entriesExpireThirtyDaysAfterTheTakeAtTheBoundary() {
        val ticket = on()
        assertEquals(LogWrite.Saved, log.put(ticket, entry()))
        time = T0 + DICTATION_LOG_TTL_MS - 1
        assertEquals("one millisecond short of 30 days is kept", 1, log.entries().size)
        time = T0 + DICTATION_LOG_TTL_MS
        assertEquals("30 days is gone", emptyList<DictationEntry>(), log.entries())
        assertEquals(emptySet<String>(), files())
    }

    @Test
    fun openingPrunesExpiredEntriesAtStartup() {
        val ticket = on()
        assertEquals(LogWrite.Saved, log.put(ticket, entry()))
        time = T0 + DICTATION_LOG_TTL_MS
        openDictationLog(filesDir) { time }
        assertEquals("pruned on open, before any read", emptySet<String>(), files())
    }

    @Test
    fun everyWritePrunesExpiredEntries() {
        val ticket = on()
        assertEquals(LogWrite.Saved, log.put(ticket, entry()))
        time = T0 + DICTATION_LOG_TTL_MS
        assertEquals(LogWrite.Saved, log.put(ticket, entry(OTHER_ID)))
        assertEquals(setOf(OTHER_ID + DictationLog.ENTRY_SUFFIX), files())
    }

    @Test
    fun futureDatedForeignAndStrayFilesArePruned() {
        val ticket = on()
        assertEquals(LogWrite.Invalid, log.put(ticket, entry(atMs = T0 + 1)))
        File(dir, "notes.txt").writeText("stray")
        File(dir, "$OTHER_ID.json").writeText(entry().toJson().toString())
        assertTrue(log.prune())
        assertEquals("future-dated, stray and wrong-name files are dropped", emptySet<String>(), files())
        assertEquals(LogWrite.Invalid, log.put(ticket, entry(id = "../escape")))
    }

    @Test
    fun deleteAllErasesEveryEntryStaysOnAndFencesEarlierTickets() {
        val ticket = on()
        assertEquals(LogWrite.Saved, log.put(ticket, entry()))
        capture.begin(1)
        capture.attempt(1, "en")

        assertTrue(log.deleteAll())

        assertTrue(log.enabled)
        assertEquals(emptyList<DictationEntry>(), log.entries())
        assertFalse("the take in flight is dropped from memory", capture.holding)
        assertEquals(LogWrite.Stale, log.put(ticket, entry()))
        capture.finish(1, LogOutcome.PolishOff, "late", "late")
        assertEquals(emptyList<DictationEntry>(), log.entries())
    }

    @Test
    fun switchingOffDeletesEverythingAndAnInFlightTakeCannotRecreateIt() {
        on()
        capture.begin(1)
        capture.attempt(1, "en")
        capture.failed(1, "stt HTTP 500: response content omitted")
        capture.begin(2)
        capture.attempt(2, "en")
        assertEquals(1, log.entries().size)

        assertTrue(log.setEnabled(false))

        assertFalse(dir.exists() && dir.listFiles().orEmpty().isNotEmpty())
        assertFalse("in-memory take dropped", capture.holding)
        capture.note(2, "stt transport: request timed out; resent once")
        capture.finish(2, LogOutcome.PolishOff, "late", "late")
        assertEquals(emptyList<DictationEntry>(), log.entries())

        assertTrue("on again is a new epoch", log.setEnabled(true))
        capture.finish(2, LogOutcome.PolishOff, "late", "late")
        assertEquals("the earlier take does not come back", emptyList<DictationEntry>(), log.entries())
    }

    @Test
    fun aWriteAlreadyQueuedWhenTheLogGoesOffIsRefused() {
        val queued = mutableListOf<() -> Unit>()
        val deferred = DictationCapture(log, "v", { time }, write = { queued += it }, unsaved = { unsaved += it })
        try {
            on()
            deferred.begin(1)
            deferred.attempt(1, "en")
            deferred.finish(1, LogOutcome.PolishOff, "in flight", "in flight")
            assertTrue(log.setEnabled(false))
            assertTrue(log.setEnabled(true))
            queued.forEach { it() }
            assertEquals(emptyList<DictationEntry>(), log.entries())
            assertEquals(listOf(LogWrite.Stale), unsaved)
        } finally {
            deferred.close()
        }
    }

    @Test
    fun aFailedRenameKeepsTheOldRecordAndLeavesNoSidecar() {
        val ticket = on()
        assertEquals(LogWrite.Saved, log.put(ticket, entry()))
        val failing = DictationLog(dir, { time }) { _, _ -> throw IOException("rename refused") }
        assertEquals(LogWrite.Failed, failing.put(ticket, entry().copy(raw = "changed")))
        assertEquals("raw", log.entries().single().raw)
        assertEquals(setOf("$ID.json"), files())
    }

    @Test
    fun everyFileTheLogOwnsIsExcludedFromCloudBackupAndDeviceTransfer() {
        val entry = File(dir, ID + DictationLog.ENTRY_SUFFIX)
        val state = File(dir, DictationLog.STATE)
        val owned = listOf(dir, entry, state, DictationLog.sidecarOf(entry), DictationLog.sidecarOf(state))
            .map { it.relativeTo(filesDir).invariantSeparatorsPath }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile())
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excludes = (document.getElementsByTagName(section).item(0) as Element).getElementsByTagName("exclude")
            val paths = (0 until excludes.length).map { excludes.item(it) as Element }
                .filter { it.getAttribute("domain") == "file" }.map { it.getAttribute("path").trimEnd('/') }
            assertTrue("positive control: $section has file rules", paths.isNotEmpty())
            for (path in owned) assertTrue("$section excludes $path", paths.any { path == it || path.startsWith("$it/") })
        }
    }

    @Test
    fun detailsAreStrippedOfSecretsBeforeTheyAreStored() {
        val leaky = "stt transport: https://user:hunter2@stt.example/v1?key=gsk_secret#frag Bearer gsk_token token=abc&x=1"
        val safe = safeDetail(leaky)
        for (secret in listOf("user", "hunter2", "gsk_secret", "frag", "gsk_token", "abc")) {
            assertFalse("'$secret' survived: $safe", safe.contains(secret))
        }
        assertEquals("diagnostic detail omitted", safe)
        assertEquals("diagnostic detail omitted", safeDetail("x".repeat(MAX_DETAIL_CHARS + 50)))

        val ticket = on()
        val stored = entry().copy(detail = leaky, events = listOf(LogEvent(T0, LogEventKind.Transport, leaky)))
        assertEquals(LogWrite.Saved, log.put(ticket, stored))
        val bytes = File(dir, ID + DictationLog.ENTRY_SUFFIX).readText()
        assertFalse(bytes, bytes.contains("hunter2") || bytes.contains("gsk_"))
        assertTrue("positive control: the record holds the omission marker", bytes.contains("diagnostic detail omitted"))
    }

    @Test
    fun theServedByNameIsTheHostAloneAndTheLanguageIsAsAsked() {
        assertEquals("stt.example", endpointHost(" https://user:pass@stt.example:8443/v1?key=secret "))
        assertNull(endpointHost("not a url"))
        assertEquals("he", languageSent(SttLanguage.Explicit("he")))
        assertEquals("auto", languageSent(SttLanguage.Auto(emptyList())))
        assertEquals("auto:en,he", languageSent(SttLanguage.Auto(listOf("en", "he"))))
    }

    @Test
    fun theTraceNamesTheLastEndpointThatAnsweredAndTheFirstDetection() {
        val trace = SttTrace()
        fun answering(text: String, detected: Pair<String, Float>? = null) = object : Transcriber {
            override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?) =
                Result.success(Transcription(text, null, detected))
        }
        val failing = object : Transcriber {
            override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?) =
                Result.failure<Transcription>(SttError("stt HTTP 502", "stt HTTP 502"))
        }
        val auto = SttLanguage.Auto(listOf("en", "he"))
        trace.wrap("stt.example", answering("hi", "he" to 0.9f)).transcribe(ByteArray(0), auto, null)
        trace.wrap("lane he", answering("", "en" to 0.5f)).transcribe(ByteArray(0), auto, null)
        assertEquals("lane he", trace.servedBy)
        trace.wrap("lane ru", failing).transcribe(ByteArray(0), auto, null)
        assertEquals("a failure answers nothing", "lane he", trace.servedBy)
        assertEquals("he 0.90", trace.detected)
        assertNotNull(trace.servedBy)
    }

    @Test
    fun deletingBeforeRetryNeverAcquiresNewConsentForTheOldTake() {
        on()
        capture.begin(1)
        capture.attempt(1, "en")
        capture.failed(1, "stt HTTP 502: response content omitted")
        assertTrue(log.deleteAll())
        capture.attempt(1, "en")
        capture.finish(1, LogOutcome.PolishOff, "old take", "old take")
        assertEquals(emptyList<DictationEntry>(), log.entries())
    }

    @Test
    fun anExpiredWriteCannotRecreateADeletedOldRecord() {
        val ticket = on()
        time += DICTATION_LOG_TTL_MS
        assertEquals(LogWrite.Invalid, log.put(ticket, entry(atMs = T0)))
        assertEquals(emptySet<String>(), files())
    }

    @Test
    fun unknownDiagnosticsAreOmittedRatherThanPartiallyRedacted() {
        for (detail in listOf("Authorization: Basic c2VjcmV0", "server said private transcript", "credential: private-value")) {
            assertEquals("diagnostic detail omitted", safeDetail(detail))
        }
    }

    @Test
    fun privacyChangesDuringRecordingFenceTheFirstTranscription() {
        for (change in listOf("delete", "off-on", "enable")) {
            assertTrue(log.setEnabled(change != "enable"))
            val machine = Take(object : TakeHost {
                override fun startTone(take: Int): Boolean { capture.begin(take); return false }
                override fun startRecording(): String? = null
                override fun stopRecording() = ByteArray(0)
                override fun armCap() = Unit
                override fun cancelCap() = Unit
                override fun transcribe(wav: ByteArray, take: Int) = capture.attempt(take, "en")
                override fun deliver(text: String) = Unit
                override fun ended() = Unit
            })
            machine.on(TakeEvent.Trigger)
            assertEquals(TakeState.Recording, machine.state)
            when (change) {
                "delete" -> assertTrue(log.deleteAll())
                "off-on" -> { assertTrue(log.setEnabled(false)); assertTrue(log.setEnabled(true)) }
                "enable" -> assertTrue(log.setEnabled(true))
            }
            machine.on(TakeEvent.Trigger)
            capture.finish(machine.currentTake, LogOutcome.PolishOff, "pre-change take", "pre-change take")
            assertEquals(change, emptyList<DictationEntry>(), log.entries())
        }
    }

    @Test
    fun knownDiagnosticShapesSurviveButAdditionalContentDoesNot() {
        for (detail in listOf(
            "stt transport: port out of range",
            "polish transport: port out of range",
            "stt[he] HTTP 401: no token sent: response content omitted",
            "stt transport: timeout after 15000 ms; resent once (lane he)",
            "polish HTTP 502 body unreadable: connection unavailable",
            "stt: the server refused vad (HTTP 400); resent the take without it",
            "polish returned incomplete content",
        )) {
            assertEquals(detail, safeDetail(detail))
            assertEquals("diagnostic detail omitted", safeDetail(detail + " private token"))
        }
    }

    private companion object {
        const val T0 = 1_800_000_000_000L
        const val ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        const val OTHER_ID = "1f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"

        fun rulesFile(): File = sequenceOf(
            "src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
        ).map(::File).first(File::exists)
    }
}
