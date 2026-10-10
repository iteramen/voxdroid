package io.github.iteramen.voxdroid

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SttClientTest {

    private val wav = "RIFFxxxx".toByteArray()
    private val auto = SttLanguage.Auto(emptyList())
    private var endpoint: Endpoint? = null
    private val notes = mutableListOf<String>()

    @After
    fun stopTheServer() {
        endpoint?.stop()
    }

    @Test
    fun explicitLanguageAndPromptAreSentAndTextIsTrimmed() {
        val server = start(reply = """{"text":" hello world \n"}""")

        val out = client("${server.url}/v1").transcribe(wav, SttLanguage.Explicit("he"), "Glossary: Acme")

        assertEquals("hello world", out.getOrThrow().text)
        val request = server.requests.single()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertTrue(request.contentType, request.contentType.startsWith("multipart/form-data; boundary=voxdroid"))
        val boundary = request.contentType.substringAfter("boundary=")
        assertTrue(request.body, request.body.startsWith("--$boundary\r\n"))
        assertTrue(request.body, request.body.endsWith("--$boundary--\r\n"))
        assertTrue(
            request.body,
            request.body.contains(
                "name=\"file\"; filename=\"clip.wav\"\r\nContent-Type: audio/wav\r\n\r\nRIFFxxxx\r\n",
            ),
        )
        assertTrue(request.body, request.body.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(request.body, request.body.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"))
        assertTrue(request.body, request.body.contains("name=\"language\"\r\n\r\nhe\r\n"))
        assertTrue(request.body, request.body.contains("name=\"prompt\"\r\n\r\nGlossary: Acme\r\n"))
        assertNull(request.body, request.authorization)
    }

    @Test
    fun theFieldsAreWrittenInByovoxsOrder() {
        val server = start()

        client(server.url).transcribe(wav, SttLanguage.Explicit("he"), "Glossary: Acme").getOrThrow()

        val body = server.requests.single().body
        val order = listOf("name=\"file\"", "name=\"model\"", "name=\"response_format\"", "name=\"language\"", "name=\"prompt\"")
            .map { body.indexOf(it) }
        assertFalse(body, order.contains(-1))
        assertEquals(body, order.sorted(), order)
    }

    @Test
    fun aLongTakeWithAGlossaryWritesVadLastAsByovoxDoes() {
        val server = start()

        client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), SttLanguage.Explicit("he"), "Glossary: Acme").getOrThrow()

        val body = server.requests.single().body
        val order = listOf("name=\"file\"", "name=\"model\"", "name=\"response_format\"", "name=\"language\"", "name=\"prompt\"", "name=\"vad\"")
            .map { body.indexOf(it) }
        assertFalse(body, order.contains(-1))
        assertEquals(body, order.sorted(), order)
    }

    @Test
    fun autoSendsCandidatesAndNoLanguageField() {
        val server = start()

        client(server.url).transcribe(wav, SttLanguage.Auto(listOf("en", "ru")), null).getOrThrow()

        val body = server.requests.single().body
        assertEquals("/audio/transcriptions", server.requests.single().path)
        assertTrue(body, body.contains("name=\"language_candidates\"\r\n\r\nen,ru\r\n"))
        assertFalse(body, body.contains("name=\"language\"\r\n"))
        assertFalse(body, body.contains("name=\"prompt\""))
    }

    @Test
    fun anEmptyCandidateListSendsNoLanguageFieldAtAll() {
        val server = start()

        client(server.url).transcribe(wav, auto, "   ").getOrThrow()

        val body = server.requests.single().body
        assertFalse(body, body.contains("name=\"language"))
        assertFalse(body, body.contains("name=\"prompt\""))
    }

    @Test
    fun theBearerIsSentWhenTheTokenIsSet() {
        val server = start()

        client(server.url, token = "tok").transcribe(wav, auto, null).getOrThrow()

        assertEquals("Bearer tok", server.requests.single().authorization)
    }

    @Test
    fun aTakeOfSixtySecondsPlusOneSampleSendsVad() {
        val server = start()

        client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), auto, null).getOrThrow()

        val body = server.requests.single().body
        assertTrue(body, body.contains("name=\"vad\"\r\n\r\ntrue\r\n"))
    }

    @Test
    fun aTakeOfExactlySixtySecondsSendsNoVad() {
        val server = start()

        client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES), auto, null).getOrThrow()

        val body = server.requests.single().body
        assertFalse(body, body.contains("name=\"vad\""))
    }

    @Test
    fun aShortTakeSendsNoVad() {
        val server = start()

        client(server.url).transcribe(wavOfSamples(16_000), auto, null).getOrThrow()
        client(server.url).transcribe(wav, auto, null).getOrThrow()

        server.requests.forEach { assertFalse(it.body, it.body.contains("name=\"vad\"")) }
    }

    @Test
    fun aLaneRequestForALongTakeCarriesVad() {
        val default = start()
        val laneServer = Endpoint(200, """{"text":"x"}""", false)
        try {
            val routed = Routed(client(default.url), mapOf("he" to client(laneServer.url)), emptySet(), false, { true }) {}

            routed.transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), SttLanguage.Explicit("he"), null).getOrThrow()

            assertTrue(default.requests.isEmpty())
            val body = laneServer.requests.single().body
            assertTrue(body, body.contains("name=\"vad\"\r\n\r\ntrue\r\n"))
        } finally {
            laneServer.stop()
        }
    }

    @Test
    fun aLongTakeRefusedWithAnHttpErrorIsResentWithoutVadAndReturnsTheSecondText() {
        val server = start(first = 500 to "failed to process audio", reply = """{"text":"second"}""")

        val out = client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), auto, null)

        assertEquals("requests ${server.requests.size}", 2, server.requests.size)
        assertEquals("second reply's text", "second", out.getOrNull()?.text)
        assertTrue(server.requests[0].body, server.requests[0].body.contains("name=\"vad\"\r\n\r\ntrue\r\n"))
        assertFalse(server.requests[1].body, server.requests[1].body.contains("name=\"vad\""))
        assertEquals(listOf("stt: the server refused vad (HTTP 500); resent the take without it"), notes)
    }

    @Test
    fun aLongTakeRefusedTwiceFailsWithTheSecondStatusAfterExactlyTwoRequests() {
        val server = start(first = 500 to "failed to process audio", status = 503, reply = "overloaded")

        val error = client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), auto, null).exceptionOrNull()

        assertEquals("requests ${server.requests.size}", 2, server.requests.size)
        assertEquals("stt HTTP 503", (error as? SttError)?.bar)
        assertEquals(notes.toString(), 1, notes.size)
    }

    @Test
    fun aLongTakeCancelledDuringItsRefusedUploadIsNotResentWithoutVad() {
        val server = start(first = 500 to "failed to process audio", reply = """{"text":"second"}""")

        client(server.url, current = { server.requests.isEmpty() })
            .transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), auto, null)

        assertEquals("requests ${server.requests.size}", 1, server.requests.size)
    }

    @Test
    fun aShortTakeRefusedWithAnHttpErrorIsNotResent() {
        val server = start(status = 500, reply = "failed to process audio")

        val error = client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES), auto, null).exceptionOrNull()

        assertEquals("requests ${server.requests.size}", 1, server.requests.size)
        assertEquals("stt HTTP 500", (error as? SttError)?.bar)
        assertTrue(notes.toString(), notes.isEmpty())
    }

    private fun longByHeader(): ByteArray =
        wavOfSamples(61).also { header -> repeat(4) { header[RATE_OFFSET + it] = if (it == 0) 1 else 0 } }

    @Test
    fun aLongTakeRefusedWithAnUnreadableBodyIsNotResent() {
        val server = start(first = 500 to "failed", reply = """{"text":"second"}""", stallFirst = true)

        val error = SttClient(server.url, true, "whisper-1", "", WATCHDOG_TIMEOUT_MS, true, { true }) { notes.add(it) }
            .transcribe(longByHeader(), auto, null).exceptionOrNull()

        assertEquals("requests ${server.requests.size}", 1, server.requests.size)
        assertEquals("stt HTTP 500 body unreadable", (error as? SttError)?.bar)
        assertTrue(notes.toString(), notes.isEmpty())
    }

    @Test
    fun aLongTakeThatGetsNoResponseIsRepostedWithTheSameBodyAndNoVadlessResend() {
        val server = start(closeFirst = true)

        client(server.url).transcribe(longByHeader(), auto, null).getOrThrow()

        assertEquals("requests ${server.requests.size}", 2, server.requests.size)
        assertEquals(server.requests[0].body, server.requests[1].body)
        assertTrue(server.requests[1].body, server.requests[1].body.contains("name=\"vad\"\r\n\r\ntrue\r\n"))
        assertTrue(notes.toString(), notes.isEmpty())
    }

    @Test
    fun aLongTakeAnswered200SendsOneRequest() {
        val server = start()

        client(server.url).transcribe(wavOfSamples(SIXTY_SECONDS_SAMPLES + 1), auto, null).getOrThrow()

        assertEquals("requests ${server.requests.size}", 1, server.requests.size)
        assertTrue(notes.toString(), notes.isEmpty())
    }

    @Test
    fun segmentLineBreaksAreJoinedOnOneSpace() {
        val server = start(reply = """{"text":" first thing \n second thing\r\n\nthird "}""")

        val out = client(server.url).transcribe(wav, auto, null)

        assertEquals("first thing second thing third", out.getOrThrow().text)
    }

    @Test
    fun noSpeechProbIsTheMaximumOverTheSegments() {
        val server = start(
            reply = """{"text":"x","segments":[{"no_speech_prob":0.03},{"id":1},{"no_speech_prob":0.42}]}""",
        )

        val out = client(server.url).transcribe(wav, auto, null)

        assertEquals(0.42f, out.getOrThrow().noSpeechProb!!, 1e-6f)
    }

    @Test
    fun aReplyWithoutSegmentsCarriesNoScore() {
        val server = start(reply = """{"text":"x","segments":null}""")

        assertNull(client(server.url).transcribe(wav, auto, null).getOrThrow().noSpeechProb)
    }

    @Test
    fun segmentsThatAreNotAnArrayIsAnError() {
        val server = start(reply = """{"text":"x","segments":{"no_speech_prob":0.9}}""")

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt response segments is not an array", (error as SttError).bar)
    }

    @Test
    fun aScoreThatIsNotANumberIsAnError() {
        val server = start(reply = """{"text":"x","segments":[{"no_speech_prob":"high"}]}""")

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt response no_speech_prob is not a number", (error as SttError).bar)
    }

    @Test
    fun aScoreThatIsExplicitlyJsonNullIsAnError() {
        val server = start(reply = """{"text":"x","segments":[{"no_speech_prob":null}]}""")

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt response no_speech_prob is not a number", (error as SttError).bar)
        assertTrue(error.message, error.message!!.startsWith("stt response no_speech_prob is not a number"))
    }

    @Test
    fun aSegmentMissingTheKeyIsSkippedAndALaterScoreStillCounts() {
        val server = start(reply = """{"text":"x","segments":[{"id":0},{"no_speech_prob":0.9}]}""")

        val out = client(server.url).transcribe(wav, auto, null)

        assertEquals(0.9f, out.getOrThrow().noSpeechProb!!, 1e-6f)
    }

    @Test
    fun aReplyWithoutATextFieldIsAnError() {
        val server = start(reply = """{"error":"no speech detected"}""")

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt response has no text field", (error as SttError).bar)
        assertTrue(error.message, error.message!!.contains("no speech detected"))
    }

    @Test
    fun theGateBeingOffAsksForPlainJsonAndReadsNoScore() {
        val server = start(reply = """{"text":"hello","segments":[{"no_speech_prob":"high"}]}""")

        val out = client(server.url, scored = false).transcribe(wav, auto, null).getOrThrow()

        assertEquals("hello", out.text)
        assertNull(out.noSpeechProb)
        val body = server.requests.single().body
        assertTrue(body, body.contains("name=\"response_format\"\r\n\r\njson\r\n"))
        assertFalse(body, body.contains("verbose_json"))
    }

    private val probabilities = JSONObject("""{"language_probabilities":{"he":0.7,"en":0.2,"ar":0.9}}""")

    @Test
    fun theDetectedLanguageIsTheLikeliestSentCandidateAtItsOwnValue() {
        assertEquals("he" to 0.7f, detectedLanguage(probabilities, listOf("en", "he")))
        assertEquals("ar" to 0.9f, detectedLanguage(probabilities, emptyList()))
    }

    @Test
    fun aCandidateMissingFromTheMapIsNotAPickAndNoCandidateInItIsNoDetection() {
        assertEquals("en" to 0.2f, detectedLanguage(probabilities, listOf("en", "ru")))
        assertNull(detectedLanguage(probabilities, listOf("ru")))
    }

    @Test
    fun noMapOrAMapThatIsNotAnObjectOrHoldsNoNumberIsNoDetection() {
        assertNull(detectedLanguage(JSONObject("""{"text":"x"}"""), emptyList()))
        assertNull(detectedLanguage(JSONObject("""{"language_probabilities":null}"""), emptyList()))
        assertNull(detectedLanguage(JSONObject("""{"language_probabilities":[0.9]}"""), emptyList()))
        assertNull(detectedLanguage(JSONObject("""{"language_probabilities":{}}"""), emptyList()))
        assertEquals("en" to 0.1f, detectedLanguage(JSONObject("""{"language_probabilities":{"he":"high","en":0.1}}"""), emptyList()))
    }

    @Test
    fun equalValuesPickTheCodeThatSortsLast() {
        val tie = JSONObject("""{"language_probabilities":{"he":0.5,"en":0.5,"ar":0.5}}""")
        assertEquals("he" to 0.5f, detectedLanguage(tie, emptyList()))
    }

    @Test
    fun aScoredAutoReplyCarriesTheDetectionOverTheCandidatesItSent() {
        val server = start(reply = """{"text":"x","language_probabilities":{"he":0.7,"en":0.2,"ar":0.9}}""")

        val out = client(server.url).transcribe(wav, SttLanguage.Auto(listOf("en", "he")), null).getOrThrow()

        assertEquals("he" to 0.7f, out.detected)
    }

    @Test
    fun aReplyWithNoMapCarriesNoDetection() {
        val server = start(reply = """{"text":"x"}""")

        assertNull(client(server.url).transcribe(wav, auto, null).getOrThrow().detected)
    }

    @Test
    fun anUnscoredClientDetectsNothingEvenWhenTheMapIsThere() {
        val server = start(reply = """{"text":"x","language_probabilities":{"he":0.9}}""")

        assertNull(client(server.url, scored = false).transcribe(wav, auto, null).getOrThrow().detected)
    }

    @Test
    fun anExplicitTakeDetectsNothingEvenWhenTheMapIsThere() {
        val server = start(reply = """{"text":"x","language_probabilities":{"he":0.9}}""")

        assertNull(client(server.url).transcribe(wav, SttLanguage.Explicit("he"), null).getOrThrow().detected)
    }

    @Test
    fun anHttpErrorKeepsTheStatusAndIsNeverRetried() {
        val server = start(status = 500, reply = """{"error":"boom"}""")

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt HTTP 500", (error as SttError).bar)
        assertEquals("stt HTTP 500: {\"error\":\"boom\"}", error.message)
        assertEquals("a received status must not be retried", 1, server.requests.size)
    }

    @Test
    fun aReplyOneByteOverTheBodyLimitIsUnreadableAndNotRetried() {
        val server = start(reply = "a".repeat(BODY_LIMIT_BYTES + 1))

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt HTTP 200 body unreadable", (error as SttError).bar)
        assertEquals("requests ${server.requests.size}", 1, server.requests.size)
        assertTrue(error.message, error.message!!.contains(BODY_LIMIT_BYTES.toString()))
    }

    @Test
    fun theReadStopsAtTheLimitWithoutWaitingForTheRestOfTheBody() {
        val over = "a".repeat(BODY_LIMIT_BYTES + 1)
        val server = start(first = 200 to over, stallFirst = true)

        val error = SttClient(server.url, true, "whisper-1", "", WATCHDOG_TIMEOUT_MS, true, { true }) { notes.add(it) }
            .transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt HTTP 200 body unreadable", (error as SttError).bar)
        assertTrue(error.message, error.message!!.contains("body over $BODY_LIMIT_BYTES bytes"))
    }

    @Test
    fun aReplyExactlyAtTheBodyLimitIsRead() {
        val head = """{"text":""""
        val tail = """"}"""
        val text = "a".repeat(BODY_LIMIT_BYTES - head.length - tail.length)
        val server = start(reply = head + text + tail)

        val reply = client(server.url).transcribe(wav, auto, null).getOrThrow()

        assertEquals(text.length, reply.text.length)
    }

    @Test
    fun aTokenWithALineBreakFailsWithFixedTextAndSendsNothing() {
        val server = start()

        val error = client(server.url, token = BROKEN_TOKEN).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals(0, server.requests.size)
        assertEquals("stt transport: the token has a character an HTTP header cannot carry", error.message)
        for (shown in listOf(error.message!!, (error as SttError).bar)) {
            for (piece in BROKEN_TOKEN.lines()) assertFalse(shown, shown.contains(piece))
        }
    }

    @Test
    fun aFailureBodyThatEchoesTheTokenIsRedacted() {
        val server = start(status = 500, reply = "upstream rejected: Bearer $TOKEN, again $TOKEN")

        val error = client(server.url, token = TOKEN).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt HTTP 500: upstream rejected: Bearer [redacted], again [redacted]", error.message)
    }

    @Test
    fun aTokenStraddlingTheCutLeavesNoFragment() {
        val server = start(status = 500, reply = "a".repeat(195) + TOKEN)

        val error = client(server.url, token = TOKEN).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt HTTP 500: " + ("a".repeat(195) + "[redacted]").take(200), error.message)
        assertFalse(error.message, error.message!!.contains(TOKEN.take(3)))
    }

    @Test
    fun aLaneTokenIsRedactedFromItsOwnFailure() {
        val server = start(status = 500, reply = "echo $TOKEN")

        val error = client(server.url, token = TOKEN).transcribe(wav, SttLanguage.Explicit("he"), null)
            .exceptionOrNull()!!

        assertFalse(error.message, error.message!!.contains(TOKEN))
    }

    @Test
    fun anEmptyTokenRedactsNothing() {
        val server = start(status = 500, reply = "echo $TOKEN")

        val error = client(server.url, token = "").transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt HTTP 500: echo $TOKEN", error.message)
    }

    @Test
    fun aTransportCauseThatQuotesTheTokenIsRedacted() {
        val scheme = "a$TOKEN"

        val attempt = boundedPost("$scheme://host/", true, "text/plain", TOKEN, ByteArray(0), TIMEOUT_MS, "t")

        val detail = (attempt as Attempt.SendFailed).cause
        assertTrue(detail, detail.contains("[redacted]"))
        assertFalse(detail, detail.contains(TOKEN, ignoreCase = true))
    }

    @Test
    fun aMalformedJsonReplyLogsOnlyTheBodyPrefix() {
        val body = """{"text":"${"a".repeat(210)} $SENTINEL"""
        val server = start(reply = body)

        val error = client(server.url).transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt JSON: " + body.take(200), error.message)
        assertFalse(error.message, error.message!!.contains(SENTINEL))
    }

    @Test
    fun anExceptionDetailIsCutAtTheBodyPrefix() {
        val cause = java.net.ProtocolException("Unexpected status line: ${"a".repeat(300)} $SENTINEL")

        val detail = causeDetail(cause, "")

        assertEquals(cause.toString().take(200), detail)
        assertFalse(detail, detail.contains(SENTINEL))
    }

    @Test
    fun aMalformedUrlSendFailureIsCutAtTheBodyPrefix() {
        val scheme = "a".repeat(300) + SENTINEL.replace("_", "")

        val attempt = boundedPost("$scheme://host/", true, "text/plain", "", ByteArray(0), TIMEOUT_MS, "t")

        val detail = (attempt as Attempt.SendFailed).cause
        assertEquals(200, detail.length)
        assertFalse(detail, detail.contains(SENTINEL.replace("_", ""), ignoreCase = true))
    }

    @Test
    fun aTransportFailureIsRetriedExactlyOnce() {
        val server = start(reply = """{"text":"second try"}""", closeFirst = true)

        val out = client(server.url).transcribe(wav, auto, null)

        assertEquals("second try", out.getOrThrow().text)
        assertEquals("the send failure is retried, once", 2, server.requests.size)
    }

    @Test
    fun aDeadEndpointNamesTheStageOnTheBar() {
        // Bind a port, learn it, then drop the socket: nothing is listening there, so the
        // connection is refused outright instead of waiting on a dropped SYN.
        val port = ServerSocket(0).use { it.localPort }

        val error = client("http://127.0.0.1:$port").transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt transport", (error as SttError).bar)
        assertTrue(error.message, error.message!!.startsWith("stt transport: "))
    }

    @Test
    fun aNonHttpSchemeIsATransportFailureAndNothingThrows() {
        val error = client("file:///tmp/x").transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt transport", (error as SttError).bar)
    }

    @Test
    fun aPortOutOfRangeIsATransportFailureAndNothingThrows() {
        for (url in listOf("http://127.0.0.1:99999/v1", "http://whisper_server:87700/v1")) {
            val error = client(url).transcribe(wav, auto, null).exceptionOrNull()!!

            assertEquals("stt transport", (error as SttError).bar)
            assertTrue(error.message, error.message!!.contains("port out of range"))
        }
    }

    @Test
    fun withTheSwitchOffBoundedPostBlocksARemoteHttpUrlBeforeConnecting() {
        val attempt = boundedPost("http://203.0.113.1:9/v1", false, "text/plain", TOKEN, ByteArray(0), TIMEOUT_MS, "t")

        assertTrue("got ${(attempt as? Attempt.SendFailed)?.cause ?: attempt}", attempt === Attempt.Blocked)
    }

    @Test
    fun withTheSwitchOffBoundedPostBlocksAUrlOnlyJavaNetUrlReads() {
        for (url in listOf("http://203.0.113.1:9/v1|", "url:http://203.0.113.1:9/v1", "not a url")) {
            val attempt = boundedPost(url, false, "text/plain", TOKEN, ByteArray(0), TIMEOUT_MS, "t")

            assertTrue("$url: got ${(attempt as? Attempt.SendFailed)?.cause ?: attempt}", attempt === Attempt.Blocked)
        }
    }

    @Test
    fun withTheSwitchOffAUrlOnlyJavaNetUrlReadsSendsNothing() {
        RawServer().use { raw ->
            val url = "http://[::ffff:127.0.0.1]:${raw.port}/v1|"

            val out = client(url, token = TOKEN, allowInsecureHttp = false).transcribe(wav, auto, null)

            assertEquals("requests with the switch off", emptyList<String>(), raw.requests.toList())
            assertEquals("stt: insecure endpoint blocked", (out.exceptionOrNull() as SttError).bar)
            client(url, token = TOKEN, allowInsecureHttp = true).transcribe(wav, auto, null)
            val sent = raw.requests.firstOrNull().orEmpty()
            assertTrue("with the switch on: $sent", sent.contains("Authorization: Bearer $TOKEN"))
        }
    }

    @Test
    fun withTheSwitchOffARemoteHttpEndpointFailsNamingTheRuleAndSendsNothing() {
        val server = start()
        val url = server.url.replace("http://127.0.0.1", "http://[::ffff:127.0.0.1]")

        val out = client(url, allowInsecureHttp = false).transcribe(wav, auto, null)

        assertEquals("requests with the switch off", 0, server.requests.size)
        val error = out.exceptionOrNull()!!
        assertEquals("stt: insecure endpoint blocked", (error as SttError).bar)
        assertEquals("stt: insecure endpoint blocked", error.message)
        client(url, allowInsecureHttp = true).transcribe(wav, auto, null).getOrThrow()
        assertEquals("requests with the switch on", 1, server.requests.size)
    }

    @Test
    fun withTheSwitchOffALoopbackEndpointIsStillReached() {
        val server = start(reply = """{"text":"local"}""")

        val out = client(server.url, allowInsecureHttp = false).transcribe(wav, auto, null)

        assertEquals("local", out.getOrThrow().text)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anEmptyBaseUrlIsAnErrorAndNothingIsSent() {
        val error = client("").transcribe(wav, auto, null).exceptionOrNull()!!

        assertEquals("stt: no endpoint configured", (error as SttError).bar)
        assertEquals("stt: no endpoint configured", error.message)
    }

    @Test
    fun aPeerThatAcceptsAndNeverReadsIsBoundedByTheWatchdog() {
        val serverSocket = ServerSocket(0)
        try {
            val acceptor = Thread {
                try {
                    while (true) serverSocket.accept() // accepted, never read from.
                } catch (closed: IOException) {
                    // serverSocket.close() in the finally below unblocks accept() the same way.
                }
            }
            acceptor.isDaemon = true
            acceptor.start()

            val bigWav = ByteArray(FOUR_MEGABYTES)
            val start = System.nanoTime()
            val error = SttClient("http://127.0.0.1:${serverSocket.localPort}", true, "whisper-1", "", WATCHDOG_TIMEOUT_MS, true, { true }) {}
                .transcribe(bigWav, auto, null)
                .exceptionOrNull()!!
            val elapsedMs = (System.nanoTime() - start) / NANOS_PER_MILLI

            println("aPeerThatAcceptsAndNeverReadsIsBoundedByTheWatchdog elapsed $elapsedMs ms")
            assertEquals("stt transport", (error as SttError).bar)
            assertTrue("elapsed was $elapsedMs ms", elapsedMs < 4_000)
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun clampTimeoutSKeepsSecondsInOneToSixHundred() {
        assertEquals(1, clampTimeoutS(-5))
        assertEquals(1, clampTimeoutS(0))
        assertEquals(1, clampTimeoutS(1))
        assertEquals(30, clampTimeoutS(30))
        assertEquals(600, clampTimeoutS(600))
        assertEquals(600, clampTimeoutS(Int.MAX_VALUE))
    }

    private fun start(
        status: Int = 200,
        reply: String = """{"text":"x"}""",
        closeFirst: Boolean = false,
        first: Pair<Int, String>? = null,
        stallFirst: Boolean = false,
    ): Endpoint = Endpoint(status, reply, closeFirst, first, stallFirst).also { endpoint = it }

    private fun client(baseUrl: String, token: String = "", scored: Boolean = true, current: () -> Boolean = { true }, allowInsecureHttp: Boolean = true) =
        SttClient(baseUrl, allowInsecureHttp, "whisper-1", token, TIMEOUT_MS, scored, current) { notes.add(it) }

    private fun wavOfSamples(samples: Int) = wav(ByteArray(samples * 2))

    private companion object {
        const val SIXTY_SECONDS_SAMPLES = 60 * 16_000
        const val RATE_OFFSET = 24
        const val SENTINEL = "SENTINEL_PAST_THE_PREFIX"
        const val TOKEN = "tokenvalue12345"
        const val BROKEN_TOKEN = "fake-token\nline2"
        const val TIMEOUT_MS = 5_000
        const val WATCHDOG_TIMEOUT_MS = 1_000
        const val FOUR_MEGABYTES = 4 * 1024 * 1024
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

private const val UNREAD_BYTES = 100L
private const val STALL_MS = 2_500L

private class RawServer : AutoCloseable {

    private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = socket.localPort

    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    init {
        Thread {
            try {
                while (true) {
                    socket.accept().use { peer ->
                        val input = peer.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val c = input.read()
                            if (c < 0) break
                            head.append(c.toChar())
                        }
                        val length = Regex("(?i)content-length: *([0-9]+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        input.readNBytes(length)
                        requests.add(head.toString())
                        val body = """{"text":"x"}"""
                        peer.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                    }
                }
            } catch (closed: IOException) {
                // close() below ends accept() this way.
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = socket.close()
}

private class Endpoint(
    status: Int,
    reply: String,
    closeFirst: Boolean,
    first: Pair<Int, String>? = null,
    stallFirst: Boolean = false,
) {

    class Request(val path: String, val contentType: String, val authorization: String?, val body: String)

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf<Request>())

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            requests.add(
                Request(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    body,
                ),
            )
            // The first connection is closed unread: no status ever reaches the client, which is
            // the one failure the client may retry.
            if (closeFirst && requests.size == 1) {
                exchange.close()
            } else {
                val (code, text) = if (first != null && requests.size == 1) first else status to reply
                val bytes = text.toByteArray()
                if (stallFirst && requests.size == 1) {
                    // The status goes out with a body shorter than its declared length, then the
                    // body stalls past the client's timeout: the client has a status and cannot
                    // read the body.
                    exchange.sendResponseHeaders(code, bytes.size + UNREAD_BYTES)
                    exchange.responseBody.write(bytes)
                    exchange.responseBody.flush()
                    Thread.sleep(STALL_MS)
                    exchange.close()
                } else {
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
        }
        if (stallFirst) server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun stop() {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }
}
