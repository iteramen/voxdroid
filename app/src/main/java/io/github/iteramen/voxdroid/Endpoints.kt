package io.github.iteramen.voxdroid

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

internal enum class EndpointKind(val path: String) {
    Stt("/audio/transcriptions"),
    Polish("/chat/completions"),
}

internal enum class BaseUrlProblem {
    NotAnHttpUrl,
    PathAlreadyThere,
    PortOutOfRange,
    InsecureHttp,
}

internal fun validateBaseUrl(url: String, allowInsecureHttp: Boolean, kind: EndpointKind): BaseUrlProblem? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val uri = try {
        URI(trimmed)
    } catch (malformed: URISyntaxException) {
        return BaseUrlProblem.NotAnHttpUrl
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != HTTP && scheme != HTTPS) return BaseUrlProblem.NotAnHttpUrl
    if (!portInRange(trimmed)) return BaseUrlProblem.PortOutOfRange
    if (uri.path.orEmpty().trimEnd('/').endsWith(kind.path)) return BaseUrlProblem.PathAlreadyThere
    if (insecureBlocked(trimmed, allowInsecureHttp)) return BaseUrlProblem.InsecureHttp
    return null
}

internal fun insecureBlocked(url: String, allowInsecureHttp: Boolean): Boolean =
    !allowInsecureHttp && isCleartextToRemoteHost(url)

/** Reads the port with `java.net.URL`, which is what connects: `URI` reads `http://h:87700` as having no port. */
internal fun portInRange(url: String): Boolean {
    val port = try {
        URL(url.trim()).port
    } catch (malformed: MalformedURLException) {
        return true
    }
    return port == -1 || port in MIN_PORT..MAX_PORT
}

/** Loopback is exempt only when `URI` and `URL` read the same loopback host; strings only `URL` accepts are not loopback. */
internal fun isCleartextToRemoteHost(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val connects = try {
        URL(trimmed)
    } catch (malformed: MalformedURLException) {
        return true
    }
    if (HTTPS.equals(connects.protocol, ignoreCase = true)) return false
    if (!HTTP.equals(connects.protocol, ignoreCase = true)) return true
    val read = try {
        URI(trimmed).host
    } catch (malformed: URISyntaxException) {
        null
    } ?: return true
    return !(read.equals(connects.host, ignoreCase = true) && isLoopback(read))
}

internal fun urlSummary(url: String, allowInsecureHttp: Boolean, notSet: String, cleartext: String, blocked: String): String = when {
    url.isBlank() -> notSet
    insecureBlocked(url, allowInsecureHttp) -> "$url\n$blocked"
    isCleartextToRemoteHost(url) -> "$url\n$cleartext"
    else -> url
}

/** `URI.getHost` keeps an IPv6 literal's brackets. */
private fun isLoopback(host: String): Boolean {
    val bare = host.trim('[', ']').lowercase()
    return bare == LOCALHOST || bare in IPV6_LOOPBACK || IPV4_LOOPBACK.matches(bare)
}

private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val HTTP = "http"
private const val HTTPS = "https"
private const val LOCALHOST = "localhost"

private val IPV4_LOOPBACK = Regex("127(\\.[0-9]{1,3}){3}")
private val IPV6_LOOPBACK = setOf("::1", "0:0:0:0:0:0:0:1")

/** The lane's own token, else the STT token only when [inherit] says so; unset [inherit] trusts the STT endpoint's own server alone. */
internal fun laneToken(
    sttBaseUrl: String,
    sttToken: String,
    laneUrl: String,
    ownToken: String,
    inherit: Boolean?,
): String = when {
    ownToken.isNotBlank() -> ownToken
    laneInherits(sttBaseUrl, sttToken, laneUrl, inherit) -> sttToken
    else -> ""
}

internal fun laneInherits(sttBaseUrl: String, sttToken: String, laneUrl: String, inherit: Boolean?): Boolean =
    inherit ?: (sttToken.isNotBlank() && sameServer(sttBaseUrl, laneUrl))

internal data class LaneSwitch(val checked: Boolean, val enabled: Boolean)

internal fun laneSwitch(
    sttBaseUrl: String,
    sttToken: String,
    laneUrl: String,
    ownToken: String,
    inherit: Boolean?,
): LaneSwitch = LaneSwitch(laneInherits(sttBaseUrl, sttToken, laneUrl, inherit), enabled = ownToken.isBlank())

/** Compares scheme and authority as written, folding ASCII case only; `URI` reads no host from underscore hostnames. */
internal fun sameServer(a: String, b: String): Boolean =
    schemeOf(a).equalsAsciiIgnoreCase(schemeOf(b)) && authorityOf(a).equalsAsciiIgnoreCase(authorityOf(b))

private fun schemeOf(url: String): String = url.trim().substringBefore("://", "")

private fun authorityOf(url: String): String {
    val trimmed = url.trim()
    val rest = trimmed.substringAfter("://", trimmed)
    return rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
}

private fun String.equalsAsciiIgnoreCase(other: String): Boolean =
    length == other.length && indices.all { asciiLower(this[it]) == asciiLower(other[it]) }

private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c
