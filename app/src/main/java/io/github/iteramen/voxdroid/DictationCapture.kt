package io.github.iteramen.voxdroid

import java.net.MalformedURLException
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Builds the [DictationLog] record of the take in flight. The main thread and the worker both call in; every call names its
 * take, and a call for any take but the open one is dropped, so a late answer cannot touch another take's record. A failed
 * attempt is written at once and the same record is rewritten by a later Retry, keeping the failure among its events.
 */
class DictationCapture(
    private val log: DictationLog,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Runs a write; the service hands it to its own thread. */
    private val write: (() -> Unit) -> Unit = { it() },
    /** A write the log refused or could not make, by its fixed classification. */
    private val unsaved: (LogWrite) -> Unit = {},
) {

    private var open: Draft? = null
    private val forget: () -> Unit = { drop() }

    init {
        DictationLog.onDelete(forget)
    }

    /** True while a take's record is held in memory. */
    internal val holding: Boolean get() = synchronized(this) { open != null }

    fun close() {
        DictationLog.removeOnDelete(forget)
        drop()
    }

    fun drop() = synchronized(this) { open = null }

    /** Each STT attempt, before it is sent: a new take opens a record while the log is on, the open take again is a Retry. */
    fun attempt(take: Int, sttLanguage: String) = synchronized(this) {
        val draft = open?.takeIf { it.take == take }
        if (draft == null) {
            open = opened(take)?.also { it.sttLanguage = sttLanguage }
            return@synchronized
        }
        draft.attempts++
        draft.events += LogEvent(now(), LogEventKind.Retry, "attempt ${draft.attempts}")
        draft.sttLanguage = sttLanguage
        draft.sttDetected = null
        draft.sttServedBy = null
        draft.sttMs = null
    }

    fun recordingFailed(take: Int, detail: String) = synchronized(this) {
        open = opened(take)
        finish(take, LogOutcome.RecordingFailed, "", "", detail)
    }

    /** An STT client or routing note: a connection failure and its resend, a protocol resend, a lane fallback. */
    fun note(take: Int, line: String) = edit(take) {
        events += LogEvent(now(), if (TRANSPORT.containsMatchIn(line)) LogEventKind.Transport else LogEventKind.Note, line)
    }

    fun stt(take: Int, detected: String?, servedBy: String?, ms: Long) = edit(take) {
        sttDetected = detected
        sttServedBy = servedBy
        sttMs = ms
    }

    /** [ms] is null when the transcript was too short to be sent. */
    fun polished(take: Int, model: String, timeoutS: Int, ms: Long?) = edit(take) {
        polishModel = model
        polishTimeoutS = timeoutS
        polishMs = ms
    }

    /** A failed STT attempt: saved now, kept open for a Retry. */
    fun failed(take: Int, detail: String) = synchronized(this) {
        val draft = open?.takeIf { it.take == take } ?: return@synchronized
        draft.events += LogEvent(now(), LogEventKind.SttFailed, detail)
        save(draft, LogOutcome.SttFailed, "", "", detail)
    }

    fun finish(take: Int, outcome: LogOutcome, raw: String, delivered: String, detail: String? = null) = synchronized(this) {
        val draft = open?.takeIf { it.take == take } ?: return@synchronized
        save(draft, outcome, raw, delivered, detail)
        open = null
    }

    private fun edit(take: Int, change: Draft.() -> Unit) {
        synchronized(this) { open?.takeIf { it.take == take }?.change() }
    }

    private fun opened(take: Int): Draft? = log.begin()?.let { Draft(take, it, UUID.randomUUID().toString(), now()) }

    private fun save(draft: Draft, outcome: LogOutcome, raw: String, delivered: String, detail: String?) {
        val entry = DictationEntry(
            id = draft.id, atMs = draft.atMs, appVersion = appVersion, outcome = outcome, raw = raw, delivered = delivered,
            detail = detail, polishModel = draft.polishModel, polishTimeoutS = draft.polishTimeoutS,
            sttLanguage = draft.sttLanguage, sttDetected = draft.sttDetected, sttServedBy = draft.sttServedBy,
            sttMs = draft.sttMs, polishMs = draft.polishMs, events = draft.events.toList(),
        )
        val ticket = draft.ticket
        write {
            val result = log.put(ticket, entry)
            if (result != LogWrite.Saved) unsaved(result)
        }
    }

    private class Draft(val take: Int, val ticket: DictationLogTicket, val id: String, val atMs: Long) {
        var attempts = 1
        val events = mutableListOf<LogEvent>()
        var sttLanguage: String? = null
        var sttDetected: String? = null
        var sttServedBy: String? = null
        var sttMs: Long? = null
        var polishModel: String? = null
        var polishTimeoutS: Int? = null
        var polishMs: Long? = null
    }

    private companion object {
        val TRANSPORT = Regex("""\btransport\b""")
    }
}

/** The take's language as asked: a code, `auto`, or `auto:` and the candidates. */
internal fun languageSent(language: SttLanguage): String = when (language) {
    is SttLanguage.Explicit -> language.code
    is SttLanguage.Auto -> if (language.candidates.isEmpty()) "auto" else "auto:" + language.candidates.joinToString(",")
}

/** The host alone: never the userinfo, path or query of an endpoint. */
internal fun endpointHost(url: String): String? = try {
    URL(url.trim()).host.ifBlank { null }
} catch (malformed: MalformedURLException) {
    null
}

/** Which endpoint answered last and what language the first scored reply heard; set on the worker, read after `dictate`. */
internal class SttTrace {
    @Volatile var detected: String? = null
        private set

    @Volatile var servedBy: String? = null
        private set

    fun wrap(name: String, inner: Transcriber): Transcriber = object : Transcriber {
        override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?): Result<Transcription> =
            inner.transcribe(wav, language, prompt).onSuccess { heard ->
                servedBy = name
                val code = heard.detected
                if (detected == null && code != null) detected = String.format(Locale.ROOT, "%s %.2f", code.first, code.second)
            }
    }
}
