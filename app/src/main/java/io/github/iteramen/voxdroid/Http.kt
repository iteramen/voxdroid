package io.github.iteramen.voxdroid

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface Attempt {
    class Answered(val status: Int, val body: String) : Attempt
    class Unreadable(val status: Int, val cause: String) : Attempt
    class SendFailed(val cause: String) : Attempt
    object Blocked : Attempt
}

internal const val HTTP_OK_FIRST = 200
internal const val HTTP_OK_LAST = 299

internal fun redact(text: String, token: String) =
    if (token.isBlank()) text else text.replace(token, REDACTED)

internal fun bodyPrefix(body: String, token: String) = redact(body, token).take(BODY_PREFIX_CHARS)

internal fun causeDetail(cause: Throwable, token: String) = bodyPrefix(cause.toString(), token)

internal fun boundedPost(
    url: String,
    allowInsecureHttp: Boolean,
    contentType: String,
    token: String,
    body: ByteArray,
    timeoutMs: Int,
    watchdogThread: String,
): Attempt {
    if (!portInRange(url)) return Attempt.SendFailed("port out of range")
    if (insecureBlocked(url, allowInsecureHttp)) return Attempt.Blocked
    val connection = try {
        // A `file:` or `ftp:` URL opens a non-HttpURLConnection; a direct cast would throw outside this catch.
        (URL(url).openConnection() as? HttpURLConnection) ?: throw IOException("not an http(s) URL")
    } catch (unreachable: IOException) {
        return Attempt.SendFailed(causeDetail(unreachable, token))
    }
    // HttpURLConnection does not bound outputStream.write(body): a peer that never reads would
    // wedge the worker thread, so this watchdog bounds the whole attempt.
    val timedOut = AtomicBoolean(false)
    val watchdog = Timer(watchdogThread, true)
    watchdog.schedule(
        object : TimerTask() {
            override fun run() {
                timedOut.set(true)
                connection.disconnect()
            }
        },
        timeoutMs.toLong(),
    )
    return try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = timeoutMs
        // Android would follow a 3xx as a bodiless GET; a 3xx is this stage's failure instead.
        connection.instanceFollowRedirects = false
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", contentType)
        if (token.isNotBlank()) {
            try {
                connection.setRequestProperty("Authorization", "Bearer $token")
            } catch (refused: IllegalArgumentException) {
                return Attempt.SendFailed(HEADER_REFUSED)
            }
        }
        connection.outputStream.use { it.write(body) }
        // A watchdog disconnect can leave write() returning normally; without this check responseCode would start a second read timeout.
        if (timedOut.get()) throw IOException("timeout after $timeoutMs ms")
        val status = connection.responseCode
        if (status < 0) throw IOException("no status line")
        try {
            val stream = if (status in HTTP_OK_FIRST..HTTP_OK_LAST) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val bytes = stream?.use { it.readNBytes(BODY_LIMIT_BYTES + 1) } ?: ByteArray(0)
            if (bytes.size > BODY_LIMIT_BYTES) throw IOException("body over $BODY_LIMIT_BYTES bytes")
            Attempt.Answered(status, bytes.toString(Charsets.UTF_8))
        } catch (truncated: IOException) {
            Attempt.Unreadable(status, causeDetail(truncated, token))
        }
    } catch (transport: IOException) {
        if (timedOut.get()) {
            Attempt.SendFailed("timeout after $timeoutMs ms")
        } else {
            Attempt.SendFailed(causeDetail(transport, token))
        }
    } finally {
        watchdog.cancel()
        connection.disconnect()
    }
}

internal const val BODY_LIMIT_BYTES = 10 * 1024 * 1024

private const val CONNECT_TIMEOUT_MS = 5_000
private const val BODY_PREFIX_CHARS = 200
private const val REDACTED = "[redacted]"
private const val HEADER_REFUSED = "the token has a character an HTTP header cannot carry"
