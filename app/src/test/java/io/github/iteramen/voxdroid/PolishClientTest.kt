package io.github.iteramen.voxdroid

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections

class PolishClientTest {

    private var endpoint: Completions? = null

    @After
    fun stopTheServer() {
        endpoint?.stop()
    }

    @Test
    fun theRequestIsTheSystemPromptAndTheWrappedTranscript() {
        val server = start(reply = content(" cleaned up \n"))

        val out = client("${server.url}/v1", systemPrompt = "SYSTEM").polish("hello  world")

        assertEquals("cleaned up", out.getOrThrow())
        val request = server.requests.single()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("application/json", request.contentType)
        val body = JSONObject(request.body)
        assertEquals("gemma", body.getString("model"))
        assertEquals(0.3, body.getDouble("temperature"), 1e-9)
        assertEquals(1024, body.getInt("max_tokens"))
        val messages = body.getJSONArray("messages")
        assertEquals(request.body, 2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYSTEM", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(
            "<transcription>hello  world</transcription>",
            messages.getJSONObject(1).getString("content"),
        )
        assertNull(request.body, request.authorization)
    }

    @Test
    fun theBearerIsSentWhenTheTokenIsSet() {
        val server = start()

        client(server.url, token = "tok").polish("hello world").getOrThrow()

        assertEquals("Bearer tok", server.requests.single().authorization)
    }

    @Test
    fun aBlankTokenSendsNoHeaderAndTheTrailingSlashIsDropped() {
        val server = start()

        client("${server.url}/v1/", token = "  ").polish("hello world").getOrThrow()

        assertNull(server.requests.single().authorization)
        assertEquals("/v1/chat/completions", server.requests.single().path)
    }

    @Test
    fun aFailedSendIsNeverRetried() {
        val server = start(closeFirst = true)

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(PolishOutcome.Fallback(RAW, ""), fallbackWithoutDetail(outcome))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anHttpErrorTypesTheRawTranscript() {
        val server = start(status = 500, reply = """{"error":"boom"}""")

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertEquals("polish HTTP 500: {\"error\":\"boom\"}", fallback(outcome).detail)
    }

    @Test
    fun aTimeoutTypesTheRawTranscript() {
        val server = start(delayMs = SLOW_MS)

        val outcome = polish(client(server.url, timeoutMs = SHORT_TIMEOUT_MS), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertTrue(fallback(outcome).detail, fallback(outcome).detail.startsWith("polish transport: "))
    }

    @Test
    fun aDeadEndpointTypesTheRawTranscript() {
        // Bind a port, learn it, then drop the socket: nothing is listening there.
        val port = ServerSocket(0).use { it.localPort }

        val outcome = polish(client("http://127.0.0.1:$port"), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertTrue(fallback(outcome).detail, fallback(outcome).detail.startsWith("polish transport: "))
    }

    @Test
    fun withTheSwitchOffARemoteHttpEndpointTypesTheRawTranscriptAndSendsNothing() {
        val server = start()
        val url = server.url.replace("http://127.0.0.1", "http://[::ffff:127.0.0.1]")

        val outcome = polish(client(url, allowInsecureHttp = false), RAW, 0)
        // `URI` reads `url:` as the scheme, `java.net.URL` strips it and opens plain http.
        val prefixed = polish(client("url:$url", allowInsecureHttp = false), RAW, 0)

        assertEquals("requests with the switch off", 0, server.requests.size)
        assertEquals(PolishOutcome.Fallback(RAW, "polish: insecure endpoint blocked", blocked = true), outcome)
        assertEquals(PolishOutcome.Fallback(RAW, "polish: insecure endpoint blocked", blocked = true), prefixed)
        assertEquals(PolishOutcome.Text("x"), polish(client(url, allowInsecureHttp = true), RAW, 0))
        assertEquals("requests with the switch on", 1, server.requests.size)
    }

    @Test
    fun withTheSwitchOffALoopbackEndpointIsStillReached() {
        val server = start()

        assertEquals(PolishOutcome.Text("x"), polish(client(server.url, allowInsecureHttp = false), RAW, 0))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun anFtpEndpointTypesTheRawTranscript() {
        val outcome = polish(client("ftp://x"), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertTrue(fallback(outcome).detail, fallback(outcome).detail.startsWith("polish transport: "))
    }

    @Test
    fun aReplyThatIsNotJsonTypesTheRawTranscript() {
        val server = start(reply = "<html>gateway</html>")

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertEquals("polish response is not an object: <html>gateway</html>", fallback(outcome).detail)
    }

    @Test
    fun aTruncatedJsonReplyTypesTheRawTranscript() {
        val server = start(reply = """{"choices":[{"message":""")

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertTrue(fallback(outcome).detail, fallback(outcome).detail.startsWith("polish JSON: "))
    }

    @Test
    fun aTokenWithALineBreakFallsBackWithFixedTextAndSendsNothing() {
        val server = start(reply = content("Cleaned."))

        val outcome = polish(client(server.url, token = BROKEN_TOKEN), RAW, 0)

        assertEquals(0, server.requests.size)
        assertEquals(RAW, fallback(outcome).raw)
        val detail = fallback(outcome).detail
        assertEquals("polish transport: the token has a character an HTTP header cannot carry", detail)
        for (piece in BROKEN_TOKEN.lines()) assertFalse(detail, detail.contains(piece))
    }

    @Test
    fun aFailureBodyThatEchoesTheTokenIsRedacted() {
        val server = start(status = 500, reply = "upstream rejected: Bearer $TOKEN, again $TOKEN")

        val outcome = polish(client(server.url, token = TOKEN), RAW, 0)

        assertEquals(
            "polish HTTP 500: upstream rejected: Bearer [redacted], again [redacted]",
            fallback(outcome).detail,
        )
    }

    @Test
    fun aNoContentBodyThatEchoesTheTokenIsRedacted() {
        val server = start(reply = """{"text": "auth=Bearer $TOKEN"}""")

        val outcome = polish(client(server.url, token = TOKEN), RAW, 0)

        assertEquals(
            "polish response has no content field: {\"text\": \"auth=Bearer [redacted]\"}",
            fallback(outcome).detail,
        )
    }

    @Test
    fun aTokenStraddlingTheCutLeavesNoFragment() {
        val server = start(status = 500, reply = "a".repeat(195) + TOKEN)

        val outcome = polish(client(server.url, token = TOKEN), RAW, 0)

        assertEquals("polish HTTP 500: " + ("a".repeat(195) + "[redacted]").take(200), fallback(outcome).detail)
        assertFalse(fallback(outcome).detail, fallback(outcome).detail.contains(TOKEN.take(3)))
    }

    @Test
    fun anEmptyTokenRedactsNothing() {
        val server = start(status = 500, reply = "echo $TOKEN")

        val outcome = polish(client(server.url, token = ""), RAW, 0)

        assertEquals("polish HTTP 500: echo $TOKEN", fallback(outcome).detail)
    }

    @Test
    fun aTransportCauseThatQuotesTheTokenIsRedacted() {
        val outcome = polish(client("a$TOKEN://host", token = TOKEN), RAW, 0)

        assertFalse(fallback(outcome).detail, fallback(outcome).detail.contains(TOKEN, ignoreCase = true))
        assertTrue(fallback(outcome).detail, fallback(outcome).detail.contains("[redacted]"))
    }

    @Test
    fun aMalformedJsonReplyLogsOnlyTheBodyPrefix() {
        val body = """{"choices":[{"message":{"content":"${"a".repeat(210)} $SENTINEL"""
        val server = start(reply = body)

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals("polish JSON: " + body.take(200), fallback(outcome).detail)
        assertFalse(fallback(outcome).detail, fallback(outcome).detail.contains(SENTINEL))
    }

    @Test
    fun anEmptyChoicesArrayTypesTheRawTranscript() {
        val server = start(reply = """{"choices":[]}""")

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertEquals("polish response has no content field: {\"choices\":[]}", fallback(outcome).detail)
    }

    @Test
    fun aReplyWithoutAContentFieldTypesTheRawTranscript() {
        val server = start(reply = """{"choices":[{"message":{"role":"assistant"}}]}""")

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertTrue(
            fallback(outcome).detail,
            fallback(outcome).detail.startsWith("polish response has no content field: "),
        )
    }

    @Test
    fun blankContentTypesTheRawTranscript() {
        val server = start(reply = content("  \n "))

        val outcome = polish(client(server.url), RAW, 0)

        assertEquals(RAW, fallback(outcome).raw)
        assertEquals("polish returned empty content", fallback(outcome).detail)
    }

    @Test
    fun aFirstWordIGetsItsCapitalBack() {
        for ((from, to) in listOf(
            "i think" to "I think",
            "i'm in" to "I'm in",
            "i\u2019ll go" to "I\u2019ll go",
            "i've done it" to "I've done it",
            "i'd say so" to "I'd say so",
            "i" to "I",
            "i, for one" to "I, for one",
            "  i think" to "  I think",
        )) assertEquals(from, to, restoreFirstI(from))
    }

    @Test
    fun nothingButAFirstWordPronounIsTouched() {
        for (same in listOf(
            "so i think", "set i to zero", "(i) first", "i.e. later", "i18n", "iPhone", "i's",
            "\u0438 \u0442\u0430\u043a", "I think", "", "   ",
        )) assertEquals(same, same, restoreFirstI(same))
    }

    @Test
    fun theReplyIsRaisedOnlyWithTheLowercaseRuleAndNeverTheRawFallback() {
        val server = start(reply = content("i think"))
        assertEquals(PolishOutcome.Text("I think"), polish(client(server.url), RAW, 0, lowercaseRule = true))
        assertEquals(PolishOutcome.Text("i think"), polish(client(server.url), RAW, 0, lowercaseRule = false))
        endpoint?.stop()
        val failing = start(status = 500)
        assertEquals("i think", fallback(polish(client(failing.url), "i think", 0, lowercaseRule = true)).raw)
    }

    @Test
    fun belowMinWordsTheStageIsSkippedAndNothingIsSent() {
        val server = start(reply = content("Cleaned."))

        val outcome = polish(client(server.url), "two words", 3)

        assertEquals(PolishOutcome.Text("two words"), outcome)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun aTranscriptAtMinWordsIsPolished() {
        val server = start(reply = content("Three whole words."))

        val outcome = polish(client(server.url), "three whole words", 3)

        assertEquals(PolishOutcome.Text("Three whole words."), outcome)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun wordsAreCountedOverAnyWhitespaceRun() {
        assertEquals(0, wordCount(""))
        assertEquals(0, wordCount("   \n\t "))
        assertEquals(1, wordCount(" one "))
        assertEquals(3, wordCount("one  two\nthree"))
    }

    @Test
    fun nbspSeparatedWordsCountAndArePolished() {
        val raw = "привет мир как дела"
        assertEquals(4, wordCount(raw))
        val server = start(reply = content("Cleaned."))

        val outcome = polish(client(server.url), raw, 3)

        assertEquals(PolishOutcome.Text("Cleaned."), outcome)
        assertEquals(1, server.requests.size)
    }

    private fun fallback(outcome: PolishOutcome) = outcome as PolishOutcome.Fallback

    private fun fallbackWithoutDetail(outcome: PolishOutcome) = fallback(outcome).copy(detail = "")

    private fun content(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(text)}}}]}"""

    private fun start(
        status: Int = 200,
        reply: String = """{"choices":[{"message":{"content":"x"}}]}""",
        delayMs: Long = 0,
        closeFirst: Boolean = false,
    ): Completions = Completions(status, reply, delayMs, closeFirst).also { endpoint = it }

    private fun client(
        baseUrl: String,
        token: String = "",
        timeoutMs: Int = TIMEOUT_MS,
        systemPrompt: String = "SYSTEM",
        allowInsecureHttp: Boolean = true,
    ) = PolishClient(baseUrl, allowInsecureHttp, "gemma", token, timeoutMs, systemPrompt)

    private companion object {
        const val RAW = "hello world"
        const val SENTINEL = "SENTINEL_PAST_THE_PREFIX"
        const val TOKEN = "tokenvalue12345"
        const val BROKEN_TOKEN = "fake-token\nline2"
        const val TIMEOUT_MS = 5_000
        const val SHORT_TIMEOUT_MS = 200
        const val SLOW_MS = 1_200L
    }
}

private class Completions(status: Int, reply: String, delayMs: Long, closeFirst: Boolean) {

    class Request(val path: String, val contentType: String, val authorization: String?, val body: String)

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf<Request>())

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
            requests.add(
                Request(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    body,
                ),
            )
            if (delayMs > 0) Thread.sleep(delayMs)
            // Closed unread: no status ever reaches the client.
            if (closeFirst && requests.size == 1) {
                exchange.close()
            } else {
                val bytes = reply.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)
}
