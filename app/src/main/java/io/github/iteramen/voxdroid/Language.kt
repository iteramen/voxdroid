package io.github.iteramen.voxdroid

import java.net.MalformedURLException
import java.net.URL
import java.util.Locale

/** Keeps valid ISO 639-1 codes (two lowercase ASCII letters, so never `auto`); anything else is named to [warn] and never sent. */
internal fun languageCodes(raw: String, warn: (String) -> Unit): List<String> =
    raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { code ->
            val valid = code.length == CODE_LENGTH && code.all { it in 'a'..'z' }
            if (!valid) warn("language code dropped, not ISO 639-1: $code")
            valid
        }
        .distinct()

internal fun defaultLanguageCodes(locale: Locale): String =
    listOf(Locale.forLanguageTag(locale.toLanguageTag()).language, "en")
        .filter { it.length == CODE_LENGTH && it.all { c -> c in 'a'..'z' } }
        .distinct()
        .joinToString(",")

internal fun languageRowTitle(code: String): String =
    Locale.forLanguageTag(code).let { "${it.getDisplayLanguage(it)} ($code)" }

internal fun languageRowSummary(
    explicit: Boolean,
    laneUrl: String,
    sentAs: String,
    autoDetect: String,
    notSet: String,
): String = "${if (explicit) sentAs else autoDetect} · ${if (laneUrl.isBlank()) notSet else hostOf(laneUrl)}"

/** `URI` reads no host from underscore hostnames, which the URL box accepts; `java.net.URL` is what connects. */
private fun hostOf(url: String): String {
    val host = try {
        URL(url.trim()).host
    } catch (malformed: MalformedURLException) {
        null
    }
    return host?.takeIf { it.isNotEmpty() } ?: url.trim()
}

private const val CODE_LENGTH = 2
