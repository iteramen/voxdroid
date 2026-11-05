package io.github.iteramen.voxdroid

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Under `filesDir`; `res/xml/data_extraction_rules.xml` excludes the whole directory, sidecars included. */
internal const val CAPTION_DIR = "captions"

internal const val CAPTION_TTL_MS = 30L * 24 * 60 * 60 * 1000

/** One record, UTF-8 JSON; a longer one is refused at write and dropped at read, never cut. */
internal const val MAX_CAPTION_RECORD_BYTES = 256 * 1024

internal const val MAX_PROFILE_CHARS = 1024

internal const val CAPTION_FORMAT = 1

/**
 * A caption's identity: the producer's fingerprint algorithm and version, the audio's SHA-256 under it, and an opaque
 * processing profile the producer derives without credentials. Nothing else (file name, URI, sender, chat, position) is identity.
 */
data class CaptionKey(val algorithm: String, val audioSha256: String, val profile: String) {
    init {
        require(ALGORITHM.matches(algorithm)) { "fingerprint algorithm must match $ALGORITHM" }
        require(SHA256_HEX.matches(audioSha256)) { "audio SHA-256 must be 64 lower-case hex digits" }
        require(profile.isNotBlank() && profile.length <= MAX_PROFILE_CHARS && encodable(profile)) {
            "profile must be 1 to $MAX_PROFILE_CHARS characters of valid UTF-16"
        }
    }

    /** The algorithm has no newline and the hash a fixed length, so the profile last makes the encoding unambiguous. */
    internal val digest: String get() = sha256Hex("$algorithm\n$audioSha256\n$profile")

    private companion object {
        val ALGORITHM = Regex("[a-z0-9][a-z0-9._-]{0,31}")
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

enum class CaptionCleanup {
    Cleaned,
    SkippedShort,
    /** Cleanup failed and the raw transcript is what was displayed. */
    Fallback,
    Off,
}

data class Caption(val raw: String, val displayed: String, val cleanup: CaptionCleanup, val writtenAtMs: Long)

/** Issued by [CaptionCache.begin]; Delete all or turning remembering off makes every earlier ticket [CaptionWrite.Stale]. */
class CaptionTicket internal constructor(internal val epoch: String)

sealed interface CaptionLookup {
    data class Hit(val caption: Caption) : CaptionLookup
    data object Miss : CaptionLookup
    data object Disabled : CaptionLookup
    data object Failed : CaptionLookup
}

enum class CaptionWrite {
    Saved,
    Disabled,
    Stale,
    /** A blank transcript, or text with an unpaired surrogate that UTF-8 would alter. */
    Invalid,
    TooLarge,
    Failed,
}

/**
 * The private caption cache behind "Remember voice-message transcripts", off until [setEnabled]. No producer calls it yet.
 * Every entry lives [CAPTION_TTL_MS] from its write; reads do not renew it. The directory holds `state` (present while on:
 * the current ticket epoch) and one `<key digest>.json` per caption; anything else in it is pruned.
 */
class CaptionCache internal constructor(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    /** The sidecar-to-target rename; a test makes it fail. */
    private val move: (File, File) -> Unit = ::atomicMove,
) {

    val enabled: Boolean get() = begin() != null

    /** Off deletes every caption and the epoch with them; on starts a new epoch, so no earlier ticket can write. */
    fun setEnabled(on: Boolean): Boolean = synchronized(LOCK) {
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

    /** Ends on a fresh epoch while on; a failed epoch write leaves remembering off, never the old epoch. */
    fun deleteAll(): Boolean = synchronized(LOCK) {
        try {
            val wasOn = epoch() != null
            wipe() && (!wasOn || writeState())
        } catch (e: IOException) {
            false
        }
    }

    /** `null` while remembering is off or its state cannot be read. Ask for it when the request starts, not when its result arrives. */
    fun begin(): CaptionTicket? = synchronized(LOCK) {
        try {
            epoch()?.let(::CaptionTicket)
        } catch (e: IOException) {
            null
        }
    }

    fun put(ticket: CaptionTicket, key: CaptionKey, raw: String, displayed: String, cleanup: CaptionCleanup): CaptionWrite =
        synchronized(LOCK) {
            try {
                val time = now()
                prune(time)
                val epoch = epoch() ?: return CaptionWrite.Disabled
                if (epoch != ticket.epoch) return CaptionWrite.Stale
                if (raw.isBlank() || !encodable(raw) || !encodable(displayed)) return CaptionWrite.Invalid
                val bytes = JSONObject()
                    .put("v", CAPTION_FORMAT)
                    .put("key", key.digest)
                    .put("raw", raw)
                    .put("displayed", displayed)
                    .put("cleanup", cleanup.name)
                    .put("written_at", time)
                    .toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_CAPTION_RECORD_BYTES) return CaptionWrite.TooLarge
                writeAtomically(entry(key.digest), bytes)
                CaptionWrite.Saved
            } catch (e: IOException) {
                CaptionWrite.Failed
            }
        }

    fun get(key: CaptionKey): CaptionLookup = synchronized(LOCK) {
        try {
            val time = now()
            prune(time)
            if (epoch() == null) return CaptionLookup.Disabled
            val file = entry(key.digest)
            if (!file.exists()) return CaptionLookup.Miss
            read(file, time)?.let { CaptionLookup.Hit(it) } ?: CaptionLookup.Miss
        } catch (e: IOException) {
            CaptionLookup.Failed
        }
    }

    /** Runs at open ([openCaptionCache]), [put] and [get]; `false` when a file could not be read or deleted. */
    fun prune(): Boolean = synchronized(LOCK) {
        try {
            prune(now())
        } catch (e: IOException) {
            false
        }
    }

    // shortcut: reads every entry on each call, fine for one person's month of voice messages; index by write time if that grows.
    private fun prune(time: Long): Boolean {
        if (epoch() == null) return wipe()
        val files = dir.listFiles() ?: return !dir.exists()
        return files.filter { it.name != STATE && read(it, time) == null }.map { it.deleteRecursively() }.all { it }
    }

    /** A caption only for a well-formed, current-format, unexpired record under its own key; anything else is dropped. */
    private fun read(file: File, time: Long): Caption? {
        val name = file.name
        if (!file.isFile || !name.endsWith(ENTRY_SUFFIX) || file.length() > MAX_CAPTION_RECORD_BYTES) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.getInt("v") != CAPTION_FORMAT || json.getString("key") + ENTRY_SUFFIX != name) return null
            val writtenAt = json.getLong("written_at")
            // A record dated after the clock has an age nobody can trust.
            if (writtenAt > time || time - writtenAt >= CAPTION_TTL_MS) return null
            val cleanup = CaptionCleanup.entries.firstOrNull { it.name == json.getString("cleanup") } ?: return null
            Caption(json.getString("raw"), json.getString("displayed"), cleanup, writtenAt)
        } catch (e: JSONException) {
            null
        }
    }

    private fun epoch(): String? {
        val state = File(dir, STATE)
        if (!state.isFile) return null
        if (state.length() > STATE_MAX_BYTES) return null
        return state.readText(Charsets.UTF_8).takeIf { EPOCH.matches(it) }
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

    private fun entry(digest: String) = File(dir, digest + ENTRY_SUFFIX)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("caption directory unavailable")
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
        /** One lock for every instance, so two caches on one directory cannot interleave a ticket check and its write. */
        private val LOCK = Any()
        const val STATE = "state"
        const val ENTRY_SUFFIX = ".json"
        private const val STATE_MAX_BYTES = 64L
        private val EPOCH = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** In the cache's own directory, so the backup exclusion covers an interrupted write. */
        fun sidecarOf(target: File) = File(target.parentFile, target.name + ".tmp")
    }
}

internal fun captionDir(filesDir: File) = File(filesDir, CAPTION_DIR)

internal fun openCaptionCache(filesDir: File, now: () -> Long = System::currentTimeMillis): CaptionCache =
    CaptionCache(captionDir(filesDir), now).also { it.prune() }

/** For a future producer: prunes on open. Nothing in the app calls it yet. */
fun openCaptionCache(context: Context): CaptionCache = openCaptionCache(context.filesDir)

private fun atomicMove(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun encodable(text: String) = Charsets.UTF_8.newEncoder().canEncode(text)

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
