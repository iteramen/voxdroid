package io.github.iteramen.voxdroid

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Timer
import java.util.TimerTask

internal const val TTS_MAX_TEXT = 20_000
internal const val TTS_CHUNK = 500
internal const val TTS_MAX_AUDIO = 12 * 1024 * 1024
internal val TTS_LANGUAGES = listOf("en", "he", "ru")
internal class TtsFailure(val userMessage: String) : Exception(userMessage)

/** No generated toString: a language endpoint can hold a bearer token. */
internal class TtsEndpoint(val baseUrl: String, val model: String, val voice: String, val token: String)
internal class TtsLane(val baseUrl: String, val model: String, val voice: String, val token: String, val inherit: Boolean)
internal class ReadAloudConfig(val enabled: Boolean, val english: TtsEndpoint, val lanes: Map<String, TtsLane>,
    val speed: Float, val timeoutS: Int, val allowHttp: Boolean) {
    fun endpoint(code: String): TtsEndpoint {
        if (code !in TTS_LANGUAGES) throw TtsFailure("Read aloud: unsupported language")
        val lane = lanes[code]
        if (lane != null && lane.inherit && lane.token.isNotBlank()) throw TtsFailure("Read aloud: choose the $code token or default-token inheritance, not both")
        val voice = lane?.voice?.ifBlank { null } ?: if (code == "en") english.voice else ""
        if (voice.isBlank()) throw TtsFailure("Read aloud: configure the $code TTS voice and endpoint first")
        val url = lane?.baseUrl?.ifBlank { null } ?: english.baseUrl
        val model = lane?.model?.ifBlank { null } ?: english.model
        if (url.isBlank() || model.isBlank()) throw TtsFailure("Read aloud: configure the TTS endpoint and model first")
        if (validateBaseUrl(url, allowHttp, EndpointKind.Tts) != null) throw TtsFailure("Read aloud: check the TTS base URL and insecure HTTP setting")
        val token = if (lane == null || lane.inherit) english.token else lane.token
        return TtsEndpoint(url.trimEnd('/'), model, voice, token)
    }

    fun prepare(text: String): List<Pair<String, TtsEndpoint>> {
        if (!enabled) throw TtsFailure("Enable Read aloud in Voxdroid settings first")
        if (!speed.isFinite() || speed !in 0.25f..4f || timeoutS !in 1..120) throw TtsFailure("Read aloud: check speed and timeout settings")
        return ttsChunks(text).map { it to endpoint(ttsLanguage(it)) }
    }
}

/** Dominant supported script in a chunk. Never substitute an English voice for Hebrew/Russian. */
internal fun ttsLanguage(text: String): String {
    val counts = IntArray(4)
    text.codePoints().forEach { code ->
        if (Character.isLetter(code)) counts[when (code) {
            in 0x0590..0x05ff -> 1
            in 0x0400..0x052f -> 2
            in 0x0041..0x024f -> 0
            else -> 3
        }]++
    }
    val winner = (0..3).maxWith(compareBy<Int> { counts[it] }.thenBy { it })
    if (counts[winner] == 0 || winner == 3) throw TtsFailure("Read aloud: select English, Hebrew or Russian text")
    return TTS_LANGUAGES[winner]
}

internal fun ttsChunks(text: String): List<String> {
    var rest = text.trim()
    if (rest.isEmpty()) throw TtsFailure("Read aloud: select text first")
    if (rest.codePointCount(0, rest.length) > TTS_MAX_TEXT) throw TtsFailure("Read aloud: selection exceeds 20,000 characters")
    val result = mutableListOf<String>()
    while (rest.isNotEmpty()) {
        val boundary = rest.offsetByCodePoints(0, minOf(TTS_CHUNK, rest.codePointCount(0, rest.length)))
        val prefix = rest.substring(0, boundary)
        val split = if (boundary == rest.length) boundary else prefix.indices.lastOrNull {
            it >= boundary / 2 && (prefix[it].isWhitespace() || prefix[it] in ".!?;")
        }?.plus(1) ?: boundary
        result += rest.substring(0, split).trim()
        rest = rest.substring(split).trimStart()
    }
    return result
}

internal class SpeechPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

internal fun ttsWav(bytes: ByteArray): SpeechPcm {
    fun bad(): Nothing = throw TtsFailure("Read aloud: invalid or unsupported WAV reply")
    if (bytes.size !in 44..TTS_MAX_AUDIO) bad()
    fun label(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    if (label(0) != "RIFF" || label(8) != "WAVE") bad()
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val declared = data.getInt(4).toLong() and 0xffffffffL
    if (declared != 0xffffffffL && declared + 8 != bytes.size.toLong()) bad()
    var rate = 0; var channels = 0; var frame = 0; var at = 12; var blocks = 0
    var pcm: ShortArray? = null
    while (at + 8 <= bytes.size && ++blocks <= 128) {
        val stored = data.getInt(at + 4).toLong() and 0xffffffffL
        val size = if (stored == 0xffffffffL && label(at) == "data" && declared == 0xffffffffL) (bytes.size - at - 8).toLong() else stored
        if (size > bytes.size - at - 8) bad()
        val start = at + 8
        when (label(at)) {
            "fmt " -> {
                if (rate != 0 || size < 16 || data.getShort(start).toInt() != 1 || data.getShort(start + 14).toInt() != 16) bad()
                channels = data.getShort(start + 2).toInt()
                rate = data.getInt(start + 4)
                frame = data.getShort(start + 12).toInt()
                if (rate !in 8_000..48_000 || channels !in 1..2 || frame != channels * 2 || data.getInt(start + 8) != rate * frame) bad()
            }
            "data" -> {
                if (rate == 0 || pcm != null || size == 0L || size % frame != 0L || size / frame > rate * 120L) bad()
                pcm = ShortArray(size.toInt() / 2) { data.getShort(start + it * 2) }
            }
        }
        at = start + size.toInt() + (size.toInt() and 1)
    }
    if (blocks > 128 || at != bytes.size) bad()
    return SpeechPcm(pcm ?: bad(), rate, channels)
}

internal fun ttsSynthesize(config: ReadAloudConfig, endpoint: TtsEndpoint, text: String, current: () -> Boolean,
    connectionChanged: (HttpURLConnection?) -> Unit): SpeechPcm {
    if (!current()) throw TtsFailure("Read aloud cancelled")
    val connection = URL("${endpoint.baseUrl}/audio/speech").openConnection() as HttpURLConnection
    connectionChanged(connection)
    val timer = Timer("read-aloud-deadline", true)
    timer.schedule(object : TimerTask() { override fun run() { connection.disconnect() } }, config.timeoutS * 1000L)
    return try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = config.timeoutS * 1000
        connection.setRequestProperty("Content-Type", "application/json")
        if (endpoint.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${endpoint.token}")
        val body = JSONObject().put("model", endpoint.model).put("voice", endpoint.voice).put("input", text)
            .put("speed", config.speed).put("response_format", "wav").put("stream", false).toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        if (status != 200) throw TtsFailure("Read aloud: TTS returned HTTP $status")
        val bytes = connection.inputStream.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= TTS_MAX_AUDIO) {
                if (!current()) throw TtsFailure("Read aloud cancelled")
                val count = stream.read(buffer, 0, minOf(buffer.size, TTS_MAX_AUDIO + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        if (!current()) throw TtsFailure("Read aloud cancelled")
        ttsWav(bytes)
    } finally { timer.cancel(); connection.disconnect(); connectionChanged(null) }
}
