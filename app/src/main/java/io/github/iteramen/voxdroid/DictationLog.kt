package io.github.iteramen.voxdroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Under `filesDir`; `res/xml/data_extraction_rules.xml` excludes the whole directory, sidecars included. */
internal const val DICTATION_LOG_DIR = "dictation_log"

internal const val DICTATION_LOG_TTL_MS = 30L * 24 * 60 * 60 * 1000

internal const val DICTATION_LOG_FORMAT = 1

/** One record, UTF-8 JSON; a longer one is refused at write and dropped at read, never cut. */
internal const val MAX_DICTATION_RECORD_BYTES = 256 * 1024

internal const val MAX_DETAIL_CHARS = 300

enum class LogOutcome {
    Cleaned,
    CleanupFailed,
    /** Cleanup refused an insecure endpoint; the raw transcript was delivered. */
    CleanupBlocked,
    SkippedShort,
    /** Cleanup off or not configured: the raw transcript was delivered. */
    PolishOff,
    Silence,
    SttFailed,
    RecordingFailed,
}

enum class LogEventKind {
    /** The user pressed Retry: a further STT attempt with the same audio. */
    Retry,
    SttFailed,
    /** A connection failed and the request was resent, or the failure was the connection's. */
    Transport,
    /** A protocol resend or a lane fallback. */
    Note,
}

data class LogEvent(val atMs: Long, val kind: LogEventKind, val detail: String)

/** One take. [atMs] is when its first attempt started and dates its expiry; a field is null when that stage did not run or did not say. */
data class DictationEntry(
    val id: String,
    val atMs: Long,
    val appVersion: String,
    val outcome: LogOutcome,
    val raw: String = "",
    val delivered: String = "",
    val detail: String? = null,
    val polishModel: String? = null,
    val polishTimeoutS: Int? = null,
    val sttLanguage: String? = null,
    val sttDetected: String? = null,
    val sttServedBy: String? = null,
    val sttMs: Long? = null,
    val polishMs: Long? = null,
    val events: List<LogEvent> = emptyList(),
)

private const val LOG_LANGUAGE = """[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"""
private const val LOG_STAGE = """(?:stt(?:\[$LOG_LANGUAGE\])?|polish)"""
private const val LOG_CAUSE = """(?:invalid URL|port out of range|request timed out|invalid HTTP response|host unavailable|connection unavailable|transport failure|request cancelled|body over [0-9]{1,9} bytes|timeout after [0-9]{1,6} ms|the token has a character an HTTP header cannot carry)"""
private val SAFE_DETAILS = listOf(
    """$LOG_STAGE transport: $LOG_CAUSE(?:; resent once)?(?: \(lane $LOG_LANGUAGE\))?""",
    """$LOG_STAGE HTTP [1-5][0-9]{2}(?:: no token sent)?(?:: response content omitted)?""",
    """$LOG_STAGE HTTP [1-5][0-9]{2} body unreadable: $LOG_CAUSE""",
    """$LOG_STAGE: (?:no endpoint configured|insecure endpoint blocked)""",
    """$LOG_STAGE (?:JSON|response is not an object|response has no (?:text|content) field|response segments is not an array|response no_speech_prob is not a number): response content omitted""",
    """polish returned (?:incomplete|empty) content""",
    """stt: the server rejected language candidates; resent with standard fields(?: \(lane $LOG_LANGUAGE\))?""",
    """stt: the server refused vad \(HTTP [1-5][0-9]{2}\); resent the take without it(?: \(lane $LOG_LANGUAGE\))?""",
    """stt\[$LOG_LANGUAGE\] lane returned nothing; retrying the default endpoint""",
    """attempt [1-9][0-9]{0,8}""",
    """mic: (?:permission missing|refused by the platform|unavailable)""",
).map(::Regex)

/** Only complete, known diagnostic shapes survive. Unknown text is omitted, never guessed safe by redaction. */
internal fun safeDetail(text: String): String =
    if (text.length <= MAX_DETAIL_CHARS && SAFE_DETAILS.any { it.matches(text) }) text else "diagnostic detail omitted"

internal fun DictationEntry.toJson(): JSONObject = JSONObject()
    .put("v", DICTATION_LOG_FORMAT)
    .put("id", id)
    .put("at", atMs)
    .put("app", appVersion)
    .put("outcome", outcome.name)
    .put("raw", raw)
    .put("delivered", delivered)
    .putOpt("detail", detail?.let(::safeDetail))
    .putOpt("polish_model", polishModel)
    .putOpt("polish_timeout_s", polishTimeoutS)
    .putOpt("stt_language", sttLanguage)
    .putOpt("stt_detected", sttDetected)
    .putOpt("stt_served_by", sttServedBy)
    .putOpt("stt_ms", sttMs)
    .putOpt("polish_ms", polishMs)
    .put("events", JSONArray(events.map { JSONObject().put("at", it.atMs).put("kind", it.kind.name).put("detail", safeDetail(it.detail)) }))

/** `null` for anything but a well-formed current-format record. */
internal fun dictationEntryOf(json: JSONObject): DictationEntry? = try {
    if (json.getInt("v") != DICTATION_LOG_FORMAT) {
        null
    } else {
        val events = json.getJSONArray("events")
        DictationEntry(
            id = json.getString("id"),
            atMs = json.getLong("at"),
            appVersion = json.getString("app"),
            outcome = LogOutcome.valueOf(json.getString("outcome")),
            raw = json.getString("raw"),
            delivered = json.getString("delivered"),
            detail = json.text("detail"),
            polishModel = json.text("polish_model"),
            polishTimeoutS = if (json.has("polish_timeout_s")) json.getInt("polish_timeout_s") else null,
            sttLanguage = json.text("stt_language"),
            sttDetected = json.text("stt_detected"),
            sttServedBy = json.text("stt_served_by"),
            sttMs = if (json.has("stt_ms")) json.getLong("stt_ms") else null,
            polishMs = if (json.has("polish_ms")) json.getLong("polish_ms") else null,
            events = (0 until events.length()).map {
                val event = events.getJSONObject(it)
                LogEvent(event.getLong("at"), LogEventKind.valueOf(event.getString("kind")), event.getString("detail"))
            },
        )
    }
} catch (e: JSONException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

private fun JSONObject.text(key: String): String? = if (has(key)) getString(key) else null

/** Issued by [DictationLog.begin]; Delete all or switching the log off invalidates every earlier ticket. */
class DictationLogTicket internal constructor(internal val epoch: String)

enum class LogWrite {
    Saved,
    Disabled,
    Stale,
    /** An invalid id or a timestamp outside the current retention window. */
    Invalid,
    TooLarge,
    Failed,
}

/**
 * The opt-in dictation log, off until switched on in settings. The directory holds `state` (present while on: the current
 * ticket epoch) and one `<entry id>.json` per take; anything else in it is pruned, and so is every entry [DICTATION_LOG_TTL_MS]
 * after its [DictationEntry.atMs].
 */
class DictationLog internal constructor(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    /** The sidecar-to-target rename; a test makes it fail. */
    private val move: (File, File) -> Unit = ::replaceAtomically,
) {

    val enabled: Boolean get() = begin() != null

    /** Off deletes every entry and the epoch, and makes every open [DictationCapture] drop the take it holds. */
    fun setEnabled(on: Boolean): Boolean {
        val done = synchronized(LOCK) {
            try {
                when {
                    !on -> wipe()
                    epoch() != null -> true
                    else -> wipe() && writeState()
                }
            } catch (e: IOException) {
                false
            }
        }
        if (!on) forgetOpenTakes()
        return done
    }

    /** Ends on a fresh epoch while on; a failed epoch write leaves the log off, never the old epoch. */
    fun deleteAll(): Boolean {
        val done = synchronized(LOCK) {
            try {
                val wasOn = epoch() != null
                wipe() && (!wasOn || writeState())
            } catch (e: IOException) {
                false
            }
        }
        forgetOpenTakes()
        return done
    }

    /** `null` while the log is off or its state cannot be read. Ask for it when the take starts, not when its result arrives. */
    fun begin(): DictationLogTicket? = synchronized(LOCK) {
        try {
            epoch()?.let(::DictationLogTicket)
        } catch (e: IOException) {
            null
        }
    }

    /** Writes or replaces the entry with [DictationEntry.id], so a take's later attempt updates its own record. */
    fun put(ticket: DictationLogTicket, entry: DictationEntry): LogWrite = synchronized(LOCK) {
        try {
            val time = now()
            prune(time)
            val epoch = epoch() ?: return LogWrite.Disabled
            if (epoch != ticket.epoch) return LogWrite.Stale
            if (!UUID_TEXT.matches(entry.id)) return LogWrite.Invalid
            if (entry.atMs < 0 || entry.atMs > time || time - entry.atMs >= DICTATION_LOG_TTL_MS) return LogWrite.Invalid
            val bytes = entry.toJson().toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_DICTATION_RECORD_BYTES) return LogWrite.TooLarge
            writeAtomically(File(dir, entry.id + ENTRY_SUFFIX), bytes)
            LogWrite.Saved
        } catch (e: IOException) {
            LogWrite.Failed
        }
    }

    /** Oldest first; empty while off. */
    fun entries(): List<DictationEntry> = synchronized(LOCK) {
        try {
            val time = now()
            prune(time)
            dir.listFiles().orEmpty().mapNotNull { read(it, time) }.sortedBy { it.atMs }
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Runs at open ([openDictationLog]), [put] and [entries]; `false` when a file could not be read or deleted. */
    fun prune(): Boolean = synchronized(LOCK) {
        try {
            prune(now())
        } catch (e: IOException) {
            false
        }
    }

    // shortcut: reads every entry on each call, fine for one person's month of dictation; index by date if that grows.
    private fun prune(time: Long): Boolean {
        if (epoch() == null) return wipe()
        val files = dir.listFiles() ?: return !dir.exists()
        return files.filter { it.name != STATE && read(it, time) == null }.map { it.deleteRecursively() }.all { it }
    }

    private fun read(file: File, time: Long): DictationEntry? {
        val name = file.name
        if (!file.isFile || !name.endsWith(ENTRY_SUFFIX) || file.length() > MAX_DICTATION_RECORD_BYTES) return null
        val entry = try {
            dictationEntryOf(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: JSONException) {
            null
        }
        if (entry == null || entry.id + ENTRY_SUFFIX != name) return null
        // An entry dated after the clock has an age nobody can trust.
        if (entry.atMs > time || time - entry.atMs >= DICTATION_LOG_TTL_MS) return null
        return entry
    }

    private fun epoch(): String? {
        val state = File(dir, STATE)
        if (!state.isFile || state.length() > STATE_MAX_BYTES) return null
        return state.readText(Charsets.UTF_8).takeIf { UUID_TEXT.matches(it) }
    }

    /** A random epoch, not a counter: a lost or corrupt state file can never bring an old ticket's epoch back. */
    private fun writeState(): Boolean {
        writeAtomically(File(dir, STATE), UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
        return true
    }

    /** Deletes every file in the directory, all attempted even after one fails. */
    private fun wipe(): Boolean {
        val files = dir.listFiles() ?: return !dir.exists()
        return files.map { it.deleteRecursively() }.all { it }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("dictation log directory unavailable")
        val sidecar = sidecarOf(target)
        try {
            FileOutputStream(sidecar).use {
                it.write(bytes)
                it.fd.sync()
            }
            move(sidecar, target)
        } catch (e: IOException) {
            sidecar.delete()
            throw e
        }
    }

    internal companion object {
        /** One lock for every instance, so the settings page and the service cannot interleave a ticket check and its write. */
        private val LOCK = Any()
        private val openTakes = CopyOnWriteArrayList<() -> Unit>()
        const val STATE = "state"
        const val ENTRY_SUFFIX = ".json"
        private const val STATE_MAX_BYTES = 64L
        private val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** In the log's own directory, so the backup exclusion covers an interrupted write. */
        fun sidecarOf(target: File) = File(target.parentFile, target.name + ".tmp")

        fun onDelete(forget: () -> Unit) {
            openTakes += forget
        }

        fun removeOnDelete(forget: () -> Unit) {
            openTakes -= forget
        }

        private fun forgetOpenTakes() = openTakes.forEach { it() }
    }
}

internal fun dictationLogDir(filesDir: File) = File(filesDir, DICTATION_LOG_DIR)

internal fun openDictationLog(filesDir: File, now: () -> Long = System::currentTimeMillis): DictationLog =
    DictationLog(dictationLogDir(filesDir), now).also { it.prune() }

fun openDictationLog(context: Context): DictationLog = openDictationLog(context.filesDir)

private fun replaceAtomically(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}
