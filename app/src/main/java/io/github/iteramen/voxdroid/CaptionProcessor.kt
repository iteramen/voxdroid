package io.github.iteramen.voxdroid

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Bump on any change to what [captionProfile] reads or to how audio is normalized. */
internal const val CAPTION_PROFILE_VERSION = "caption-profile-v2"

/** The normalization [CaptionNormalizer] applies, part of every profile. */
internal const val CAPTION_NORMALIZATION = "pcm16-mono-16k-wav44-box-v1"

private const val MILLIS_PER_SECOND = 1000

sealed interface CaptionOutcome {
    /** [key] is `null` when no safe processing profile could be formed ([captionProfile]): show the caption, never cache it. */
    data class Captioned(
        val key: CaptionKey?, val raw: String, val displayed: String, val cleanup: CaptionCleanup,
        val savedAtMs: Long? = null,
    ) : CaptionOutcome

    data object Silence : CaptionOutcome

    /** [detail] can quote 200 characters of the reply, which may be the transcript: never log it at the default level. */
    data class SttFailed(val bar: String, val detail: String) : CaptionOutcome

    /** More than [CAPTION_MAX_ENCODED_BYTES] encoded bytes. */
    data object TooLarge : CaptionOutcome

    /** More than [CAPTION_MAX_SECONDS] of decoded audio. */
    data object TooLong : CaptionOutcome

    /** Not Ogg/Opus, or a decoded format [CaptionNormalizer] does not take. */
    data object Unsupported : CaptionOutcome

    /** The decoder failed or produced no frame. */
    data object Malformed : CaptionOutcome

    /** The source could not be read, or the spool could not be written, or could not be deleted before upload. */
    data object ReadFailed : CaptionOutcome

    /** Cancelled, or superseded by a later request on the same processor. */
    data object Cancelled : CaptionOutcome

    data object TimedOut : CaptionOutcome
}

class CaptionLane(val baseUrl: String, val token: String)

/**
 * Every setting one request uses, read once when it starts ([captionSettings]). Not a data class: the tokens ride along for
 * the requests and must not reach a `toString`; [captionProfile] never reads them.
 */
class CaptionSettings(
    val sttBaseUrl: String,
    val sttModel: String,
    val sttToken: String,
    val sttPrompt: String,
    val sttTimeoutS: Int,
    val noSpeechThreshold: Float,
    val allowInsecureHttp: Boolean,
    /** The whole *Your languages* list: imported speech is auto-detected across all of it, whatever the keyboard or the explicit flags say. */
    val languages: List<String>,
    /** By language code, each with the token [laneToken] gives it. */
    val lanes: Map<String, CaptionLane>,
    val polishEnabled: Boolean,
    val polishBaseUrl: String,
    val polishModel: String,
    val polishToken: String,
    val polishMinWords: Int,
    val polishTimeoutS: Int,
    val capitalizeFirstWord: Boolean,
    val stripTerminalPeriod: Boolean,
    /** `res/raw/polish_prompt.txt` as read ([readBuiltInPrompt]). */
    val polishPrompt: String,
)

/** Snapshot the configured processing choices at the start of each explicit caption request. */
fun captionSettings(settings: Settings, polishPrompt: String): CaptionSettings = CaptionSettings(
    sttBaseUrl = settings.sttBaseUrl,
    sttModel = settings.sttModel,
    sttToken = settings.sttToken,
    sttPrompt = settings.sttPrompt,
    sttTimeoutS = settings.sttTimeoutS,
    noSpeechThreshold = settings.noSpeechThreshold,
    allowInsecureHttp = settings.allowInsecureHttp,
    languages = settings.chipLanguages,
    lanes = settings.chipLanguages.mapNotNull { code ->
        settings.laneBaseUrl(code).ifBlank { null }?.let { url ->
            code to CaptionLane(url, laneToken(settings.sttBaseUrl, settings.sttToken, url, settings.laneToken(code), settings.laneInheritsToken(code)))
        }
    }.toMap(),
    polishEnabled = settings.polishEnabled,
    polishBaseUrl = settings.polishBaseUrl,
    polishModel = settings.polishModel,
    polishToken = settings.polishToken,
    polishMinWords = settings.polishMinWords,
    polishTimeoutS = settings.polishTimeoutS,
    capitalizeFirstWord = settings.capitalizeFirstWord,
    stripTerminalPeriod = settings.stripTerminalPeriod,
    polishPrompt = polishPrompt,
)

/**
 * The opaque processing profile for [CaptionKey]: a version and a SHA-256 over every non-secret setting that shapes the
 * caption. Tokens are left out. Timeouts and the insecure-http switch are included because they can change a cleanup fallback.
 * `null` (uncacheable) when an address has user info, a query or a fragment: those can hold credentials, and leaving
 * them out could make two endpoints share a profile.
 */
internal fun captionProfile(s: CaptionSettings): String? {
    val fields = mutableListOf(CAPTION_NORMALIZATION, profileUrl(s.sttBaseUrl) ?: return null, s.sttModel, s.sttPrompt, s.noSpeechThreshold.toString())
    fields += listOf(s.sttTimeoutS.toString(), s.polishTimeoutS.toString(), s.allowInsecureHttp.toString())
    fields += s.languages.size.toString()
    fields += s.languages
    fields += s.lanes.size.toString()
    for ((code, lane) in s.lanes.toSortedMap()) {
        fields += code
        fields += profileUrl(lane.baseUrl) ?: return null
    }
    if (cleanupConfigured(s)) {
        fields += listOf("cleanup", profileUrl(s.polishBaseUrl) ?: return null, s.polishModel, s.polishPrompt)
        fields += listOf(s.polishMinWords.toString(), s.capitalizeFirstWord.toString())
    } else {
        fields += "no cleanup"
    }
    fields += s.stripTerminalPeriod.toString()
    // Length-prefixed, so no field's text can pass for a boundary.
    val canonical = fields.joinToString("") { "${it.length}:$it;" }
    val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return "$CAPTION_PROFILE_VERSION:" + hash.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private fun profileUrl(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    val authority = trimmed.substringAfter("://", "").takeWhile { it != '/' }
    return trimmed.takeIf { '@' !in authority && '?' !in trimmed && '#' !in trimmed }
}

private fun cleanupConfigured(s: CaptionSettings) = s.polishEnabled && s.polishBaseUrl.isNotBlank() && s.polishModel.isNotBlank()

/** The same routing dictation uses: the default endpoint, then a confident detection's lane ([Routed]). */
internal fun captionTranscriber(s: CaptionSettings, current: () -> Boolean): Transcriber {
    val timeoutMs = clampTimeoutS(s.sttTimeoutS) * MILLIS_PER_SECOND
    fun client(baseUrl: String, token: String) = SttClient(
        baseUrl = baseUrl, allowInsecureHttp = s.allowInsecureHttp, model = s.sttModel, token = token,
        timeoutMs = timeoutMs, scored = s.noSpeechThreshold > 0f, current = current, note = {},
    )
    val lanes = s.lanes.mapValues { (_, lane) -> client(lane.baseUrl, lane.token) }
    val tokenless = s.lanes.filterValues { it.token.isBlank() }.keys
    return Routed(client(s.sttBaseUrl, s.sttToken), lanes, tokenless, s.sttToken.isNotBlank(), current) {}
}

/** `null` while cleanup is off or has no address or model, as for dictation. */
internal fun captionPolisher(s: CaptionSettings): Polisher? {
    if (!cleanupConfigured(s)) return null
    return PolishClient(
        baseUrl = s.polishBaseUrl, allowInsecureHttp = s.allowInsecureHttp, model = s.polishModel, token = s.polishToken,
        timeoutMs = clampTimeoutS(s.polishTimeoutS) * MILLIS_PER_SECOND,
        systemPrompt = systemPrompt(builtInPrompt(s.polishPrompt, s.capitalizeFirstWord), s.sttPrompt),
    )
}

/**
 * One received voice message in, a typed [CaptionOutcome] out: bounded acquisition and fingerprint, Ogg/Opus check,
 * decode, 16 kHz WAV, STT with the no-speech gate, cleanup. Writes nothing but its own spool, and caches nothing.
 */
class CaptionProcessor internal constructor(
    private val spoolDir: File,
    private val decoder: CaptionDecoder,
    private val transcriber: (CaptionSettings, () -> Boolean) -> Transcriber,
    private val polisher: (CaptionSettings) -> Polisher?,
    /** Milliseconds on a monotonic clock. */
    private val now: () -> Long,
) {

    private val latest = AtomicLong()

    /** The running request, if any, ends [CaptionOutcome.Cancelled] at its next check and sends nothing more. */
    fun cancel() {
        latest.incrementAndGet()
    }

    /** Deletes every spool in the directory no running request owns; runs at open ([openCaptionProcessor]). */
    fun sweep(): Boolean = CaptionSpools.sweep(spoolDir)

    /**
     * Blocking: call it off the main thread. Supersedes any earlier request on this processor. Reads [source] once and
     * closes it. Past [budgetMs] the request is [CaptionOutcome.TimedOut]: no request is started after it, and a reply that
     * arrives after it is dropped. A caption comes back only while the request is still current and inside its budget.
     */
    fun process(
        source: InputStream,
        settings: CaptionSettings,
        budgetMs: Long,
        lookup: (CaptionKey) -> Caption? = { null },
        onAcquired: () -> Unit = {},
        /** Owner lifetime also applies before this processor has started (for example while opening a provider stream). */
        isCurrent: () -> Boolean = { true },
    ): CaptionOutcome {
        require(budgetMs > 0) { "budget must be positive" }
        val id = latest.incrementAndGet()
        val deadline = now() + budgetMs
        val current = { latest.get() == id && isCurrent() && now() < deadline }
        val check = {
            if (latest.get() != id || !isCurrent()) throw CaptionStop(CaptionOutcome.Cancelled)
            if (now() >= deadline) throw CaptionStop(CaptionOutcome.TimedOut)
        }
        val spool = CaptionSpools.create(spoolDir)
        return try {
            val sha256 = captionAcquire(source, spool, check)
            // The provider stream is now closed. The receiving Activity can return to its caller;
            // decoding uses only our private spool, never the expired external URI.
            onAcquired()
            val head = spool.inputStream().use { it.readNBytes(CAPTION_HEAD_BYTES) }
            if (!captionIsOggOpus(head)) return CaptionOutcome.Unsupported
            val key = captionProfile(settings)?.let { CaptionKey(CAPTION_FINGERPRINT, sha256, it) }
            check()
            val cached = key?.let(lookup)
            check()
            if (cached != null) {
                if (!CaptionSpools.release(spool)) return CaptionOutcome.ReadFailed
                return CaptionOutcome.Captioned(key, cached.raw, cached.displayed, cached.cleanup, cached.writtenAtMs)
            }
            val normalizer = CaptionNormalizer()
            decoder.decode(spool, normalizer, check)
            val wav = normalizer.toWav()
            if (!CaptionSpools.release(spool)) return CaptionOutcome.ReadFailed
            check()
            caption(wav, sha256, settings, current, check)
        } catch (stop: CaptionStop) {
            stop.outcome
        } catch (failed: IOException) {
            CaptionOutcome.ReadFailed
        } finally {
            CaptionSpools.release(spool)
        }
    }

    private fun caption(wav: ByteArray, sha256: String, settings: CaptionSettings, current: () -> Boolean, check: () -> Unit): CaptionOutcome {
        val language = SttLanguage.Auto(settings.languages)
        val heard = dictate(transcriber(settings, current), wav, language, settings.sttPrompt, settings.noSpeechThreshold)
        check()
        val raw = when (heard) {
            is SttOutcome.Text -> heard.text.trim()
            SttOutcome.Silence -> return CaptionOutcome.Silence
            is SttOutcome.Failure -> return CaptionOutcome.SttFailed(heard.bar, heard.detail)
        }
        // A blank transcript is silence: it never goes to the cleanup server.
        if (raw.isEmpty()) return CaptionOutcome.Silence
        val polisher = polisher(settings)
        val (chosen, cleanup) = if (polisher == null) {
            raw to CaptionCleanup.Off
        } else {
            check()
            when (val polished = polish(polisher, raw, settings.polishMinWords, !settings.capitalizeFirstWord)) {
                is PolishOutcome.Text -> polished.text to CaptionCleanup.Cleaned
                is PolishOutcome.SkippedShort -> polished.raw to CaptionCleanup.SkippedShort
                is PolishOutcome.Fallback -> polished.raw to CaptionCleanup.Fallback
            }
        }
        check()
        val displayed = finish(chosen, settings.stripTerminalPeriod).text
        if (displayed.isEmpty()) return CaptionOutcome.Silence
        val key = captionProfile(settings)?.let { CaptionKey(CAPTION_FINGERPRINT, sha256, it) }
        return CaptionOutcome.Captioned(key, raw, displayed, cleanup)
    }
}

internal fun openCaptionProcessor(
    filesDir: File,
    decoder: CaptionDecoder,
    transcriber: (CaptionSettings, () -> Boolean) -> Transcriber = ::captionTranscriber,
    polisher: (CaptionSettings) -> Polisher? = ::captionPolisher,
    now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
): CaptionProcessor = CaptionProcessor(captionSpoolDir(filesDir), decoder, transcriber, polisher, now).also { it.sweep() }

/** Opens the processing component and sweeps spools abandoned by a dead process. */
fun openCaptionProcessor(context: Context): CaptionProcessor = openCaptionProcessor(context.filesDir, CaptionMediaDecoder())

private const val NANOS_PER_MILLI = 1_000_000L
