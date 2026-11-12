package io.github.iteramen.voxdroid

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ReadAloudTest {
    @Test fun optionalOwnServerSmokeUsesSyntheticTextOnly() {
        val url = System.getenv("VOXDROID_TTS_TEST_URL").orEmpty()
        org.junit.Assume.assumeTrue("Set VOXDROID_TTS_TEST_URL for an explicit live-server smoke test", url.isNotBlank())
        val cfg = ReadAloudConfig(true, TtsEndpoint(url, "kokoro", "af_bella", ""), emptyMap(), 1f, 30, true)
        val audio = ttsSynthesize(cfg, cfg.endpoint("en"), "Read aloud test.", { true }, {})
        assertEquals(24_000, audio.sampleRate); assertEquals(1, audio.channels); assertTrue(audio.samples.isNotEmpty())
    }
    private fun config(lanes: Map<String, TtsLane> = emptyMap(), url: String = "http://127.0.0.1:8770/v1") =
        ReadAloudConfig(true, TtsEndpoint(url, "kokoro", "af_bella", "synthetic-root-token"), lanes, 1f, 3, false)

    @Test fun allThreeScriptsAreRoutedAndUnknownScriptsRefuse() {
        assertEquals("en", ttsLanguage("Hello world"))
        assertEquals("he", ttsLanguage("שלום עולם"))
        assertEquals("ru", ttsLanguage("Привет мир"))
        refuses { ttsLanguage("مرحبا") }; refuses { ttsLanguage("123 😀") }
    }
    @Test fun missingHebrewAndRussianVoicesNeverUseEnglish() {
        assertEquals("af_bella", config().endpoint("en").voice)
        for (code in listOf("he", "ru")) refuses { config().endpoint(code) }
        refuses { config().prepare("שלום") }; refuses { config().prepare("Привет") }
    }
    @Test fun languageOverridesAndTokenInheritanceAreExplicit() {
        val lane = TtsLane("https://example.invalid/v1", "he-model", "he-voice", "synthetic-lane-token", false)
        val endpoint = config(mapOf("he" to lane)).endpoint("he")
        assertEquals("https://example.invalid/v1", endpoint.baseUrl)
        assertEquals("he-model", endpoint.model); assertEquals("he-voice", endpoint.voice)
        assertEquals("synthetic-lane-token", endpoint.token)
        assertEquals("", config(mapOf("he" to TtsLane(lane.baseUrl, lane.model, lane.voice, "", false))).endpoint("he").token)
        refuses { config(mapOf("he" to TtsLane(lane.baseUrl, lane.model, lane.voice, lane.token, true))).endpoint("he") }
        val inherited = TtsLane(lane.baseUrl, lane.model, lane.voice, "", true)
        assertEquals("synthetic-root-token", config(mapOf("he" to inherited)).endpoint("he").token)
    }
    @Test fun chunksPreserveUnicodeAndBoundLongSelections() {
        val input = "שלום 😀 עולם. ".repeat(200)
        val parts = ttsChunks(input)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.codePointCount(0, it.length) <= TTS_CHUNK })
        assertEquals(input.trim().split(Regex("\\s+")), parts.joinToString(" ").split(Regex("\\s+")))
        refuses { ttsChunks("") }; refuses { ttsChunks("a".repeat(TTS_MAX_TEXT + 1)) }
    }
    @Test fun wavIsValidatedBeforePlayback() {
        val audio = ttsWav(wav())
        assertEquals(24_000, audio.sampleRate); assertEquals(1, audio.channels)
        assertArrayEquals(shortArrayOf(0, 100, -100, 0), audio.samples)
        refuses { ttsWav(byteArrayOf(0)) }
        refuses { ttsWav(wav().copyOf(45)) }
        for ((position, value) in listOf(20 to 3, 22 to 0, 34 to 32)) {
            val malformed = wav(); malformed[position] = value.toByte(); refuses { ttsWav(malformed) }
        }
        refuses { ttsWav(ByteArray(TTS_MAX_AUDIO + 1)) }
    }
    @Test fun kokoroStreamingLengthsUseTheBoundedReceivedBody() {
        val bytes = wav()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1).putInt(40, -1)
        assertEquals(4, ttsWav(bytes).samples.size)
    }
    @Test fun speedTimeoutAndBackupTypesAreValidated() {
        assertNull(validateNumber("tts_speed", "1.25"))
        assertEquals(NumberProblem.TtsRange, validateNumber("tts_speed", "4.1"))
        assertEquals(NumberProblem.TtsRange, validateNumber("tts_timeout_s", "0"))
        assertEquals(StoredAs.Secret, storedAs("tts_token_he"))
        assertFalse(ConfigBackup.allowed("tts_enabled", false))
        assertFalse(ConfigBackup.allowed("tts_enabled", true))
        assertTrue(ConfigBackup.snapshot(mapOf("tts_enabled" to true), mapOf("tts_enabled" to true)).let { it.settings.isEmpty() && it.secrets.isEmpty() })
        val source = ConfigSnapshot(mapOf("tts_url_en" to "http://127.0.0.1/v1", "tts_speed" to 1.25f,
            "tts_timeout_s" to 30, "tts_voice_he" to "", "tts_inherit_token_ru" to false), mapOf("tts_token_en" to "synthetic"))
        val password = "synthetic passphrase".toCharArray()
        assertEquals(source, ConfigBackup.decrypt(ConfigBackup.encrypt(source, password), password))
    }
    @Test fun actualRequestUsesOnlyTtsCredentialsAndValidatesTheReply() {
        serve(200, wav()) { port, request ->
            val pcm = ttsSynthesize(config(url = "http://127.0.0.1:$port/v1"),
                TtsEndpoint("http://127.0.0.1:$port/v1", "kokoro", "af_bella", "synthetic-tts-token"), "Harmless fixture.", { true }, {})
            assertEquals(4, pcm.samples.size)
            val raw = request.get()
            assertTrue(raw.contains("POST /v1/audio/speech")); assertTrue(raw.contains("Bearer synthetic-tts-token"))
            assertFalse(raw.contains("synthetic-root-token"))
            val body = JSONObject(raw.substringAfter("\r\n\r\n"))
            assertEquals("Harmless fixture.", body.getString("input")); assertEquals("wav", body.getString("response_format"))
            assertFalse(body.getBoolean("stream"))
        }
    }
    @Test fun responseErrorsNeverEchoTheSourceTextOrToken() {
        serve(403, "synthetic-token PRIVATE-FIXTURE".toByteArray()) { port, _ ->
            val error = refuses { ttsSynthesize(config(), TtsEndpoint("http://127.0.0.1:$port/v1", "kokoro", "af_bella", "synthetic-token"),
                "PRIVATE-FIXTURE", { true }, {}) }
            assertEquals("Read aloud: TTS returned HTTP 403", error.userMessage)
        }
    }
    @Test fun redirectsCannotForwardSelectedTextOrTtsCredentials() {
        ServerSocket(0).use { target ->
            target.soTimeout = 250
            serve(302, "synthetic-token PRIVATE-FIXTURE".toByteArray(), "Location: http://127.0.0.1:${target.localPort}/never\r\n") { port, _ ->
                val error = refuses { ttsSynthesize(config(), TtsEndpoint("http://127.0.0.1:$port/v1", "kokoro", "af_bella", "synthetic-token"),
                    "PRIVATE-FIXTURE", { true }, {}) }
                assertEquals("Read aloud: TTS returned HTTP 302", error.userMessage)
            }
            try { target.accept().close(); fail("Redirect target was contacted") } catch (_: java.net.SocketTimeoutException) { }
        }
    }
    @Test fun cancelledBeforeStartDoesNotOpenAnEndpoint() {
        val error = refuses { ttsSynthesize(config(), config().endpoint("en"), "Harmless fixture.", { false }, {}) }
        assertEquals("Read aloud cancelled", error.userMessage)
    }

    private fun wav(): ByteArray = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(44); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(24_000); putInt(48_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(8); for (n in listOf(0, 100, -100, 0)) putShort(n.toShort())
    }.array()
    private fun refuses(action: () -> Any?): TtsFailure {
        try { action(); fail("Expected refusal") } catch (failure: TtsFailure) { return failure }
        throw AssertionError("Expected refusal")
    }
    private fun serve(status: Int, reply: ByteArray, extra: String = "", test: (Int, AtomicReference<String>) -> Unit) {
        ServerSocket(0).use { server ->
            val captured = AtomicReference("")
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val header = java.io.ByteArrayOutputStream()
                    while (!header.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) {
                        val b = input.read(); if (b < 0) return@thread; header.write(b)
                    }
                    val head = header.toString(Charsets.US_ASCII)
                    val length = Regex("(?i)Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                    captured.set(head + input.readNBytes(length).toString(Charsets.UTF_8))
                    socket.getOutputStream().write(("HTTP/1.1 $status Result\r\nContent-Type: audio/wav\r\nContent-Length: ${reply.size}\r\n${extra}Connection: close\r\n\r\n").toByteArray() + reply)
                }
            }
            test(server.localPort, captured)
            worker.join(6000); assertFalse(worker.isAlive)
        }
    }
}
