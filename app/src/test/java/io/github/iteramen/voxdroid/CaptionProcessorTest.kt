package io.github.iteramen.voxdroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ShortBuffer
import java.security.MessageDigest

class CaptionProcessorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_000L
    private lateinit var filesDir: File
    private lateinit var spoolDir: File
    private val stt = FakeStt()
    private val polisher = FakePolisher()
    private var cleanupOn = true
    private var current: () -> Boolean = { error("no STT request yet") }
    private lateinit var decoder: FakeDecoder
    private lateinit var processor: CaptionProcessor

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        spoolDir = captionSpoolDir(filesDir)
        decoder = FakeDecoder()
        processor = open()
    }

    private fun open() = openCaptionProcessor(
        filesDir,
        decoder,
        transcriber = { _, current -> this.current = current; stt },
        polisher = { if (cleanupOn) polisher else null },
        now = { clock },
    )

    private fun run(audio: InputStream = ByteArrayInputStream(captionOgg()), settings: CaptionSettings = settings()) =
        processor.process(audio, settings, BUDGET_MS)

    @Test
    fun aVoiceMessageBecomesACaptionKeyedByItsEncodedBytes() {
        val audio = captionOgg()

        val outcome = run(ByteArrayInputStream(audio)) as CaptionOutcome.Captioned

        assertEquals("hello there.", outcome.raw)
        assertEquals("Hello there", outcome.displayed)
        assertEquals(CaptionCleanup.Cleaned, outcome.cleanup)
        assertEquals(CaptionKey(CAPTION_FINGERPRINT, sha256(audio), captionProfile(settings())!!), outcome.key)
        assertEquals(listOf("hello there."), polisher.calls)
    }

    @Test
    fun aSpoolDeleteFailurePreventsNetworkRequests() {
        decoder = FakeDecoder(during = { file ->
            assertTrue(file.delete())
            assertTrue(file.mkdir())
            File(file, "held").writeText("synthetic deletion failure")
        })
        processor = open()
        assertEquals(CaptionOutcome.ReadFailed, run())
        assertTrue(stt.calls.isEmpty())
        assertTrue(polisher.calls.isEmpty())
        assertTrue("recovery sweep retries orphaned task data", processor.sweep())
        assertSpoolEmpty()
    }

    @Test
    fun sttGetsTheNormalizedWavAndNeverTheOggBytes() {
        val audio = captionOgg()

        run(ByteArrayInputStream(audio))

        val (wav, _) = stt.calls.single()
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("one second at 48 kHz is 16000 samples", 44 + 16_000 * 2, wav.size)
        assertArrayEquals(ShortArray(16_000) { 1000 }, pcmOf(wav))
        assertFalse(String(wav, Charsets.ISO_8859_1).contains("OpusHead"))
        assertFalse("the dictation client reads it as a one-second 16 kHz WAV", isLongTake(wav))
    }

    @Test
    fun importedSpeechIsAutoDetectedAcrossTheWholeLanguageList() {
        run(settings = settings(languages = listOf("he", "en", "ru")))

        assertEquals(SttLanguage.Auto(listOf("he", "en", "ru")), stt.calls.single().second)
    }

    @Test
    fun theFingerprintIsTheEncodedBytesAloneRepeatableAndDistinct() {
        val first = captionOgg(ByteArray(64) { 1 })
        val second = captionOgg(ByteArray(64) { 2 })

        val a = (run(ByteArrayInputStream(first)) as CaptionOutcome.Captioned).key!!
        val again = (run(ByteArrayInputStream(first), settings(sttModel = "other")) as CaptionOutcome.Captioned).key!!
        val b = (run(ByteArrayInputStream(second)) as CaptionOutcome.Captioned).key!!

        assertEquals(a.audioSha256, again.audioSha256)
        assertNotEquals("a setting moves the profile", a.profile, again.profile)
        assertNotEquals(a.audioSha256, b.audioSha256)
        assertEquals(sha256(second), b.audioSha256)
    }

    @Test
    fun overThirtyTwoMebibytesIsRefusedBeforeDecodingAndTheSourceIsClosed() {
        val source = CaptionSource(Long.MAX_VALUE, claims = 10)

        assertEquals(CaptionOutcome.TooLarge, run(source))

        assertTrue(source.closed)
        assertEquals(CAPTION_MAX_ENCODED_BYTES + 1, source.served)
        assertEquals(0, decoder.calls)
        assertTrue(stt.calls.isEmpty())
        assertSpoolEmpty()
    }

    @Test
    fun anythingButOggOpusIsUnsupportedAndNeverDecoded() {
        val vorbis = captionOgg().also { "\u0001vorbis\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(it, 28) }
        for (audio in listOf(wav(ByteArray(3200)), vorbis, ByteArray(0), "not audio".toByteArray())) {
            assertEquals(CaptionOutcome.Unsupported, run(ByteArrayInputStream(audio)))
        }

        assertEquals(0, decoder.calls)
        assertTrue(stt.calls.isEmpty())
        assertSpoolEmpty()
    }

    @Test
    fun aDecodeThatBreaksTheContractRejectsTheWholeRequest() {
        val cases = listOf(
            CaptionOutcome.Unsupported to FakeDecoder(rate = 44_100),
            CaptionOutcome.TooLong to FakeDecoder(frames = CAPTION_MAX_SECONDS * 48_000 + 1),
            CaptionOutcome.Malformed to FakeDecoder(frames = 0),
            CaptionOutcome.Malformed to FakeDecoder(during = { throw CaptionStop(CaptionOutcome.Malformed) }),
        )
        for ((expected, broken) in cases) {
            decoder = broken
            processor = open()

            assertEquals(expected, run())
            assertTrue("$expected sends nothing", stt.calls.isEmpty())
            assertSpoolEmpty()
        }
    }

    @Test
    fun aReadFailureIsReadFailedAndTheSourceIsClosed() {
        val source = CaptionSource(Long.MAX_VALUE, failAfter = 1)

        assertEquals(CaptionOutcome.ReadFailed, run(source))

        assertTrue(source.closed)
        assertSpoolEmpty()
    }

    @Test
    fun theSpoolHoldsTheExactBytesWhileDecodingAndIsGoneBeforeStt() {
        val audio = captionOgg()
        var seen: ByteArray? = null
        decoder = FakeDecoder(during = { seen = it.readBytes(); assertEquals(spoolDir, it.parentFile) })
        processor = open()
        stt.reply = { assertSpoolEmpty(); Result.success(Transcription("fine", null)) }

        run(ByteArrayInputStream(audio))

        assertArrayEquals(audio, seen)
        assertSpoolEmpty()
    }

    @Test
    fun openingSweepsAbandonedSpoolsButNotARunningRequests() {
        spoolDir.mkdirs()
        val abandoned = File(spoolDir, "left-by-a-dead-process.ogg").also { it.writeText("old") }
        var survived = false
        decoder = FakeDecoder(during = { live ->
            val stray = File(spoolDir, "another.ogg").also { it.writeText("old") }
            open()
            survived = live.exists()
            assertFalse(stray.exists())
        })

        processor = open()
        assertFalse("the sweep at open removed the abandoned spool", abandoned.exists())
        assertTrue(run() is CaptionOutcome.Captioned)

        assertTrue("a second open left the running request's spool alone", survived)
        assertSpoolEmpty()
    }

    @Test
    fun cancelDuringDecodeEndsCancelledAndSendsNothing() {
        decoder = FakeDecoder(during = { processor.cancel() })
        processor = open()

        assertEquals(CaptionOutcome.Cancelled, run())

        assertTrue(stt.calls.isEmpty())
        assertSpoolEmpty()
    }

    @Test
    fun aLaterRequestSupersedesTheRunningOne() {
        var started = false
        var inner: CaptionOutcome? = null
        decoder = FakeDecoder(during = {
            if (!started) {
                started = true
                inner = processor.process(ByteArrayInputStream(captionOgg()), settings(), BUDGET_MS)
            }
        })
        processor = open()

        assertEquals(CaptionOutcome.Cancelled, run())

        assertTrue(inner is CaptionOutcome.Captioned)
        assertEquals("only the later request reached STT", 1, stt.calls.size)
        assertSpoolEmpty()
    }

    @Test
    fun aTranscriptThatArrivesAfterCancelIsDroppedAndNeverCleaned() {
        stt.reply = { processor.cancel(); Result.success(Transcription("too late", null)) }

        assertEquals(CaptionOutcome.Cancelled, run())

        assertFalse("routing is told the request is no longer current", current())
        assertTrue(polisher.calls.isEmpty())
    }

    @Test
    fun theDeadlineStopsDecodingBeforeAnyRequest() {
        decoder = FakeDecoder(during = { clock += BUDGET_MS })
        processor = open()

        assertEquals(CaptionOutcome.TimedOut, run())

        assertTrue(stt.calls.isEmpty())
        assertSpoolEmpty()
    }

    @Test
    fun aTranscriptPastTheDeadlineIsDroppedAndNoLaterRequestIsStarted() {
        stt.reply = {
            assertTrue("current while inside the budget", current())
            clock += BUDGET_MS
            Result.success(Transcription("too late", null))
        }

        assertEquals(CaptionOutcome.TimedOut, run())

        assertFalse("no lane resend or retry may start", current())
        assertTrue(polisher.calls.isEmpty())
    }

    @Test
    fun aCleanupReplyPastTheDeadlineIsDropped() {
        polisher.reply = { clock += BUDGET_MS; Result.success("Too late.") }

        assertEquals(CaptionOutcome.TimedOut, run())
        assertSpoolEmpty()
    }

    @Test
    fun aSilentOrBlankTranscriptIsSilenceAndNeverCleaned() {
        stt.reply = { Result.success(Transcription("you", 0.9f)) }
        assertEquals(CaptionOutcome.Silence, run(settings = settings(noSpeechThreshold = 0.3f)))

        stt.reply = { Result.success(Transcription("  ", null)) }
        assertEquals(CaptionOutcome.Silence, run())

        assertTrue(polisher.calls.isEmpty())
    }

    @Test
    fun anSttFailureIsTypedAndNotACaption() {
        stt.reply = { Result.failure(SttError("stt HTTP 502", "stt HTTP 502: bad gateway")) }

        assertEquals(CaptionOutcome.SttFailed("stt HTTP 502", "stt HTTP 502: bad gateway"), run())
        assertTrue(polisher.calls.isEmpty())
    }

    @Test
    fun cleanupOutcomesKeepUsableRawText() {
        stt.reply = { Result.success(Transcription("two words.", null)) }

        val short = run(settings = settings(polishMinWords = 3)) as CaptionOutcome.Captioned
        assertEquals(CaptionCleanup.SkippedShort, short.cleanup)
        assertEquals("two words", short.displayed)
        assertTrue(polisher.calls.isEmpty())

        polisher.reply = { Result.failure(PolishError("polish HTTP 500")) }
        val fallback = run() as CaptionOutcome.Captioned
        assertEquals(CaptionCleanup.Fallback, fallback.cleanup)
        assertEquals("two words.", fallback.raw)
        assertEquals("two words", fallback.displayed)

        cleanupOn = false
        val off = run(settings = settings(stripTerminalPeriod = false)) as CaptionOutcome.Captioned
        assertEquals(CaptionCleanup.Off, off.cleanup)
        assertEquals("two words.", off.displayed)
    }

    @Test
    fun aLowerCaseFirstWordKeepsTheCapitalOfI() {
        polisher.reply = { Result.success("i think so.") }

        val out = run(settings = settings(capitalizeFirstWord = false)) as CaptionOutcome.Captioned

        assertEquals("I think so", out.displayed)
    }

    @Test
    fun cleanupIsOnlyBuiltWhenEnabledWithAnAddressAndAModel() {
        assertNotNull(captionPolisher(settings()))
        assertNull(captionPolisher(settings(polishEnabled = false)))
        assertNull(captionPolisher(settings(polishBaseUrl = " ")))
        assertNull(captionPolisher(settings(polishModel = "")))
        assertTrue("the routing is dictation's", captionTranscriber(settings()) { true } is Routed)
    }

    @Test
    fun theProfileMovesWithEveryNonSecretSettingThatShapesTheCaption() {
        val base = captionProfile(settings())!!
        val changed = mapOf(
            "stt url" to settings(sttBaseUrl = "https://other.example/v1"),
            "stt model" to settings(sttModel = "large-v3"),
            "stt timeout" to settings(sttTimeoutS = 99),
            "cleanup timeout" to settings(polishTimeoutS = 99),
            "insecure switch" to settings(allowInsecureHttp = true),
            "glossary" to settings(sttPrompt = "Acme"),
            "no-speech threshold" to settings(noSpeechThreshold = 0.5f),
            "languages" to settings(languages = listOf("en", "he")),
            "a lane" to settings(lanes = mapOf("he" to CaptionLane("https://he.example/v1", ""))),
            "cleanup off" to settings(polishEnabled = false),
            "cleanup url" to settings(polishBaseUrl = "https://llm2.example/v1"),
            "cleanup model" to settings(polishModel = "m2"),
            "cleanup prompt" to settings(polishPrompt = "PROMPT 2"),
            "minimum words" to settings(polishMinWords = 4),
            "capitalization" to settings(capitalizeFirstWord = false),
            "terminal period" to settings(stripTerminalPeriod = false),
        )
        for ((name, s) in changed) assertNotEquals(name, base, captionProfile(s))

        val same = mapOf(
            "tokens" to settings(sttToken = "other", polishToken = "other"),
            "trailing slash and spaces" to settings(sttBaseUrl = " https://stt.example/v1/ "),
            "a cleanup url while cleanup is off" to settings(polishEnabled = false, polishBaseUrl = "https://x.example/?key=1"),
        )
        val off = captionProfile(settings(polishEnabled = false))
        for ((name, s) in same) assertEquals(name, if (s.polishEnabled) base else off, captionProfile(s))
        val withLane = settings(lanes = mapOf("he" to CaptionLane("https://he.example/v1", "lane-secret")))
        val laneToken = settings(lanes = mapOf("he" to CaptionLane("https://he.example/v1", "")))
        assertEquals("a lane token", captionProfile(withLane), captionProfile(laneToken))
    }

    @Test
    fun theProfileIsVersionedOpaqueAndHoldsNoSecretOrAddress() {
        val profile = captionProfile(settings())!!

        assertTrue(profile, profile.matches(Regex("$CAPTION_PROFILE_VERSION:[0-9a-f]{64}")))
        for (secret in listOf("stt-secret", "llm-secret", "stt.example", "whisper-1")) assertFalse(secret, profile.contains(secret))
        CaptionKey(CAPTION_FINGERPRINT, "0".repeat(64), profile)
    }

    @Test
    fun anAddressThatCouldCarryACredentialMakesTheCaptionUncacheable() {
        val unsafe = listOf(
            settings(sttBaseUrl = "https://user:pass@stt.example/v1"),
            settings(sttBaseUrl = "https://stt.example/v1?key=abc"),
            settings(sttBaseUrl = "https://stt.example/v1#frag"),
            settings(sttBaseUrl = "http://user@whisper_server:8770/v1"),
            settings(lanes = mapOf("he" to CaptionLane("https://k@he.example/v1", ""))),
            settings(polishBaseUrl = "https://llm.example/v1?api_key=abc"),
        )
        for (s in unsafe) assertNull(s.sttBaseUrl, captionProfile(s))

        val out = run(settings = unsafe.first()) as CaptionOutcome.Captioned
        assertNull("shown, never cached", out.key)
        assertEquals("Hello there", out.displayed)
        assertNotNull("a path @ is not user info", captionProfile(settings(sttBaseUrl = "https://stt.example/v1/@team")))
    }

    private fun assertSpoolEmpty() {
        assertEquals("spool files left", emptyList<String>(), spoolDir.list().orEmpty().toList())
    }

    private class FakeStt(var reply: () -> Result<Transcription> = { Result.success(Transcription("hello there.", 0.01f)) }) : Transcriber {
        val calls = mutableListOf<Pair<ByteArray, SttLanguage>>()

        override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?): Result<Transcription> {
            calls += wav to language
            return reply()
        }
    }

    private class FakePolisher(var reply: (String) -> Result<String> = { Result.success("Hello there.") }) : Polisher {
        val calls = mutableListOf<String>()

        override fun polish(raw: String): Result<String> {
            calls += raw
            return reply(raw)
        }
    }

    /** Stands in for MediaCodec: emits [frames] frames of 1000 at [rate] after [during] sees the spool. */
    private class FakeDecoder(
        private val rate: Int = 48_000,
        private val frames: Int = 48_000,
        private val during: (File) -> Unit = {},
    ) : CaptionDecoder {
        var calls = 0

        override fun decode(file: File, sink: CaptionPcmSink, check: () -> Unit) {
            calls++
            during(file)
            check()
            sink.format(rate, 1, true)
            var left = frames
            while (left > 0) {
                val n = minOf(left, rate)
                sink.samples(ShortBuffer.wrap(ShortArray(n) { 1000 }))
                left -= n
                check()
            }
        }
    }

    private companion object {
        const val BUDGET_MS = 60_000L

        fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun settings(
            sttBaseUrl: String = "https://stt.example/v1",
            sttModel: String = "whisper-1",
            sttToken: String = "stt-secret",
            sttPrompt: String = "",
            sttTimeoutS: Int = 30,
            noSpeechThreshold: Float = 0.3f,
            allowInsecureHttp: Boolean = false,
            languages: List<String> = listOf("he", "en"),
            lanes: Map<String, CaptionLane> = emptyMap(),
            polishEnabled: Boolean = true,
            polishBaseUrl: String = "https://llm.example/v1",
            polishModel: String = "m",
            polishToken: String = "llm-secret",
            polishMinWords: Int = 0,
            polishTimeoutS: Int = 20,
            capitalizeFirstWord: Boolean = true,
            stripTerminalPeriod: Boolean = true,
            polishPrompt: String = "PROMPT",
        ) = CaptionSettings(
            sttBaseUrl, sttModel, sttToken, sttPrompt, sttTimeoutS, noSpeechThreshold, allowInsecureHttp, languages, lanes,
            polishEnabled, polishBaseUrl, polishModel, polishToken, polishMinWords, polishTimeoutS, capitalizeFirstWord,
            stripTerminalPeriod, polishPrompt,
        )
    }
}
