package io.github.iteramen.voxdroid

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Any HTTP status counts as reachable, even a 401; no token is sent. */
fun probe(baseUrl: String, allowInsecureHttp: Boolean): Probe {
    if (baseUrl.isBlank()) return Probe.Unknown
    if (!portInRange(baseUrl)) return Probe.Unreachable
    if (insecureBlocked(baseUrl, allowInsecureHttp)) return Probe.Unreachable
    val started = System.nanoTime()
    return try {
        // Not an http(s) URL (a hand-edited preference file can hold one) is no endpoint that answers.
        val connection = URL(baseUrl.trim().trimEnd('/') + "/models").openConnection() as? HttpURLConnection
            ?: return Probe.Unreachable
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // A 3xx is a status; following it would contact an address nobody configured.
        connection.instanceFollowRedirects = false
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
        Probe.Reachable((System.nanoTime() - started) / 1_000_000)
    } catch (failed: IOException) {
        Probe.Unreachable
    }
}

private const val TIMEOUT_MS = 3000
