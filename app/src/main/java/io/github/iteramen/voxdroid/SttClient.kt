package io.github.iteramen.voxdroid

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream

/** The literal "auto" never goes on the wire: auto is a candidate list or nothing at all. */
sealed interface SttLanguage {

    fun formFields(): List<Pair<String, String>>

    data class Explicit(val code: String) : SttLanguage {
        override fun formFields() = listOf("language" to code)
    }

    data class Auto(val candidates: List<String>) : SttLanguage {
        override fun formFields() = when {
            candidates.isEmpty() -> emptyList()
            else -> listOf("language_candidates" to candidates.joinToString(","))
        }
    }
}

/** [bar] is the pill's head and drops the response body; neither field ever contains the token. */
class SttError(val bar: String, message: String) : Exception(message)

internal fun <T> notCurrent(): Result<T> = Result.failure(SttError(NOT_CURRENT, NOT_CURRENT))

private const val NOT_CURRENT = "stt not sent: the take was cancelled"

class SttClient(
    baseUrl: String,
    private val allowInsecureHttp: Boolean,
    private val model: String,
    private val token: String,
    private val timeoutMs: Int,
    private val scored: Boolean,
    private val current: () -> Boolean,
    private val note: (String) -> Unit,
) : Transcriber {

    private val endpoint = baseUrl.trim().trimEnd('/')

    override fun transcribe(
        wav: ByteArray,
        language: SttLanguage,
        prompt: String?,
    ): Result<Transcription> {
        if (endpoint.isEmpty()) return fail(NO_ENDPOINT)
        val long = isLongTake(wav)
        var attempt = send(wav, language, prompt, vad = long)
        // A server started without `--vad-model` answers `vad=true` with an HTTP error, so the take
        // is sent once more without it, and that second outcome stands.
        if (long && attempt is Attempt.Answered && attempt.status !in HTTP_OK_FIRST..HTTP_OK_LAST) {
            if (!current()) return notCurrent()
            note("stt: the server refused vad (HTTP ${attempt.status}); resent the take without it")
            attempt = send(wav, language, prompt, vad = false)
        }

        val answered = when (attempt) {
            is Attempt.Answered -> attempt
            is Attempt.Unreadable ->
                return fail("stt HTTP ${attempt.status} body unreadable", attempt.cause)
            is Attempt.SendFailed -> return fail("stt transport", attempt.cause)
            Attempt.Blocked -> return fail(INSECURE_BLOCKED)
        }
        if (answered.status !in HTTP_OK_FIRST..HTTP_OK_LAST) {
            return fail("stt HTTP ${answered.status}", prefix(answered.body))
        }
        val fields = try {
            JSONTokener(answered.body).nextValue() as? JSONObject
                ?: return fail("stt response is not an object", prefix(answered.body))
        } catch (malformed: JSONException) {
            return fail("stt JSON", prefix(answered.body))
        }
        val text = fields.opt("text") as? String
            ?: return fail("stt response has no text field", prefix(answered.body))
        // whisper breaks `text` at segment boundaries, not at spoken line breaks, so lines join on a space.
        val joined = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val prob = if (scored) {
            maxNoSpeechProb(fields, answered.body).getOrElse { return Result.failure(it) }
        } else {
            null
        }
        // Only an auto take on a scored client is asked what it heard; an explicit one was told.
        val detected = if (scored && language is SttLanguage.Auto) detectedLanguage(fields, language.candidates) else null
        return Result.success(Transcription(joined, prob, detected))
    }

    /** `null` when no segment is scored; a malformed `segments` or score fails loudly instead of looking like an unscored server. */
    private fun maxNoSpeechProb(fields: JSONObject, body: String): Result<Float?> {
        val segments = fields.opt("segments")
        if (segments == null || segments === JSONObject.NULL) return Result.success(null)
        if (segments !is JSONArray) return fail("stt response segments is not an array", prefix(body))
        var max: Float? = null
        for (i in 0 until segments.length()) {
            val segment = segments.opt(i) as? JSONObject ?: continue
            // Absent and JSON null differ: an absent key is skipped, a null score is a reply this client does not understand.
            val score = segment.opt("no_speech_prob") ?: continue
            val prob = (score as? Number)?.toFloat()
                ?: return fail("stt response no_speech_prob is not a number", prefix(body))
            max = if (max == null) prob else maxOf(max, prob)
        }
        return Result.success(max)
    }

    private fun send(wav: ByteArray, language: SttLanguage, prompt: String?, vad: Boolean): Attempt {
        val boundary = BOUNDARY_PREFIX + java.lang.Long.toHexString(System.nanoTime())
        val form = Multipart(boundary)
        form.file("file", "clip.wav", "audio/wav", wav)
        form.text("model", model)
        form.text("response_format", if (scored) "verbose_json" else "json")
        for ((name, value) in language.formFields()) form.text(name, value)
        if (!prompt.isNullOrBlank()) form.text("prompt", prompt)
        if (vad) form.text("vad", "true")
        val body = form.finish()
        val contentType = "multipart/form-data; boundary=$boundary"

        // Retry only when no response came back: once a status arrived, the audio reached the server.
        val first = post(contentType, body)
        return if (first is Attempt.SendFailed) post(contentType, body) else first
    }

    private fun post(contentType: String, body: ByteArray): Attempt =
        boundedPost(endpoint + PATH, allowInsecureHttp, contentType, token, body, timeoutMs, WATCHDOG_THREAD)

    private fun <T> fail(bar: String, detail: String? = null): Result<T> =
        Result.failure(SttError(bar, if (detail.isNullOrEmpty()) bar else "$bar: $detail"))

    private fun prefix(body: String) = bodyPrefix(body, token)

    private companion object {
        const val PATH = "/audio/transcriptions"
        const val NO_ENDPOINT = "stt: no endpoint configured"
        const val INSECURE_BLOCKED = "stt: insecure endpoint blocked"
        const val BOUNDARY_PREFIX = "voxdroid"
        const val WATCHDOG_THREAD = "voxdroid-stt-watchdog"
    }
}

/** The sent candidate with the highest `language_probabilities` value (the server omits values below 0.001); ties pick the code that sorts last. */
internal fun detectedLanguage(fields: JSONObject, candidates: List<String>): Pair<String, Float>? {
    val map = fields.opt("language_probabilities") as? JSONObject ?: return null
    return map.keys().asSequence()
        .filter { candidates.isEmpty() || it in candidates }
        .sorted()
        .mapNotNull { code -> (map.opt(code) as? Number)?.let { code to it.toFloat() } }
        .fold(null as Pair<String, Float>?) { best, next -> if (best == null || next.second >= best.second) next else best }
}

/** Whether [wav], a 44-byte-header 16-bit mono WAV, holds more than [LONG_TAKE_S] seconds. */
internal fun isLongTake(wav: ByteArray): Boolean {
    if (wav.size < WAV_HEADER_BYTES) return false
    val rate = (0 until Int.SIZE_BYTES).sumOf { (wav[RATE_OFFSET + it].toLong() and 0xFF) shl (Byte.SIZE_BITS * it) }
    val samples = (wav.size - WAV_HEADER_BYTES) / BYTES_PER_PCM_SAMPLE
    return samples > LONG_TAKE_S * rate
}

private const val LONG_TAKE_S = 60
private const val WAV_HEADER_BYTES = 44
private const val RATE_OFFSET = 24
private const val BYTES_PER_PCM_SAMPLE = 2

/** A negative timeout throws `IllegalArgumentException` in `setReadTimeout` on the worker thread and strands the pill in Working. */
internal fun clampTimeoutS(seconds: Int): Int = seconds.coerceIn(MIN_TIMEOUT_S, MAX_TIMEOUT_S)

private const val MIN_TIMEOUT_S = 1
private const val MAX_TIMEOUT_S = 600

private class Multipart(private val boundary: String) {

    private val body = ByteArrayOutputStream()

    fun text(name: String, value: String) {
        body.write(utf8("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"))
        body.write(utf8(value))
        body.write(utf8("\r\n"))
    }

    fun file(name: String, filename: String, contentType: String, bytes: ByteArray) {
        body.write(
            utf8(
                "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; " +
                    "filename=\"$filename\"\r\nContent-Type: $contentType\r\n\r\n",
            ),
        )
        body.write(bytes)
        body.write(utf8("\r\n"))
    }

    fun finish(): ByteArray {
        body.write(utf8("--$boundary--\r\n"))
        return body.toByteArray()
    }

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)
}
