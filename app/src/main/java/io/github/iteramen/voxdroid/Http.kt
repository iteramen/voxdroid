package io.github.iteramen.voxdroid

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface Attempt {
    class Answered(val status: Int, val body: String) : Attempt
    class Unreadable(val status: Int, val cause: String) : Attempt
    /** [reach] is null for a failure that is not the connection's: a refused port, URL or token header. */
    class SendFailed(val cause: String, val reach: Reach? = null) : Attempt
    object Blocked : Attempt
}

/** A connection failure as the pill names it, read from the exception's type and never from its text. */
internal enum class Reach(val label: String) {
    TimedOut("timed out"),
    Unreachable("unreachable"),
}

internal fun reachOf(failure: IOException): Reach? = when (failure) {
    is SocketTimeoutException -> Reach.TimedOut
    is UnknownHostException, is ConnectException, is NoRouteToHostException, is PortUnreachableException -> Reach.Unreachable
    else -> null
}

/** A hint from the host alone: 100.64.0.0/10 or a name under `.ts.net` says nothing about whether Tailscale is up. */
internal fun tailnetHost(host: String): Boolean {
    val name = host.removeSuffix(".")
    if (name.length > TS_NET.length && name.endsWith(TS_NET, ignoreCase = true)) return true
    val octets = name.split('.')
    if (octets.size != IPV4_OCTETS || octets.any { it.isEmpty() || it.length > 3 || !it.all(Char::isAsciiDigit) }) return false
    val (first, second) = octets.map(String::toInt)
    return octets.all { it.toInt() <= MAX_OCTET } && first == CGNAT_FIRST && second in CGNAT_SECOND
}

internal fun tailnetUrl(url: String): Boolean = try {
    tailnetHost(URL(url.trim()).host)
} catch (malformed: MalformedURLException) {
    false
}

/** The pill's line for a connection failure, built from fixed words only: nothing from the exception, the reply or the URL but the host's class. */
internal fun connectionNote(head: String, reach: Reach, url: String, tail: String = ""): String {
    val note = "$head ${reach.label}$tail"
    return if (tailnetUrl(url)) "$note — $TAILSCALE_HINT" else note
}

private fun Char.isAsciiDigit() = this in '0'..'9'

private const val TS_NET = ".ts.net"
private const val IPV4_OCTETS = 4
private const val MAX_OCTET = 255
private const val CGNAT_FIRST = 100
private val CGNAT_SECOND = 64..127
internal const val TAILSCALE_HINT = "Tailscale not connected?"

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
    current: () -> Boolean = { true },
): Attempt {
    if (!current()) return Attempt.SendFailed(REQUEST_CANCELLED)
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
    // Generation checks must interrupt blocked I/O too. Disconnect runs on the watchdog,
    // never the accessibility/main thread. The deadline task still bounds live requests.
    watchdog.schedule(object : TimerTask() {
        override fun run() {
            if (!current()) connection.disconnect()
        }
    }, 0L, 50L)
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
        if (!current()) return Attempt.SendFailed(REQUEST_CANCELLED)
        connection.outputStream.use {
            if (!current()) return Attempt.SendFailed(REQUEST_CANCELLED)
            it.write(body)
        }
        if (!current()) return Attempt.SendFailed(REQUEST_CANCELLED)
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
            if (!current()) return Attempt.SendFailed(REQUEST_CANCELLED)
            if (bytes.size > BODY_LIMIT_BYTES) throw IOException("body over $BODY_LIMIT_BYTES bytes")
            Attempt.Answered(status, bytes.toString(Charsets.UTF_8))
        } catch (truncated: IOException) {
            if (!current()) Attempt.SendFailed(REQUEST_CANCELLED)
            else Attempt.Unreadable(status, causeDetail(truncated, token))
        }
    } catch (transport: IOException) {
        if (!current()) {
            Attempt.SendFailed(REQUEST_CANCELLED)
        } else if (timedOut.get()) {
            Attempt.SendFailed("timeout after $timeoutMs ms", Reach.TimedOut)
        } else {
            Attempt.SendFailed(causeDetail(transport, token), reachOf(transport))
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
private const val REQUEST_CANCELLED = "request cancelled"
