package io.github.iteramen.voxdroid

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ShortBuffer
import java.security.MessageDigest
import java.util.UUID

/** [CaptionKey.algorithm] for the SHA-256 of the encoded bytes exactly as received; a normalization change moves the profile, never this. */
const val CAPTION_FINGERPRINT = "encoded-sha256-v1"

/** Counted over the bytes actually read: a declared size or type is never asked for or trusted. */
internal const val CAPTION_MAX_ENCODED_BYTES = 32L * 1024 * 1024

/** Counted over the frames the decoder actually produced, at its own rate; container metadata durations are not read. */
internal const val CAPTION_MAX_SECONDS = 5 * 60

/** Under `filesDir`; `res/xml/data_extraction_rules.xml` excludes the whole directory. */
internal const val CAPTION_SPOOL_DIR = "caption_spool"

internal const val CAPTION_SPOOL_SUFFIX = ".ogg"

/** The largest Ogg page: 27-byte header, 255 lacing entries and at most 255 bytes per segment. */
internal const val CAPTION_HEAD_BYTES = 27 + 255 + 255 * 255

private const val COPY_CHUNK_BYTES = 64 * 1024
private const val TARGET_RATE = 16_000
private const val MAX_CHANNELS = 2
private const val OGG_PAGE_HEADER_BYTES = 27
private const val OGG_SEGMENTS_OFFSET = 26
private const val OGG_BOS = 0x02

/** Ends a request with [outcome]; thrown by the checks, the normalizer and the decoder, caught only by [CaptionProcessor.process]. */
internal class CaptionStop(val outcome: CaptionOutcome) : Exception()

internal fun captionSpoolDir(filesDir: File) = File(filesDir, CAPTION_SPOOL_DIR)

/** Spool names of the requests running in this process; any other file in the directory was left by a request that can no longer finish. */
internal object CaptionSpools {
    private val live = mutableSetOf<String>()

    /** Names a new spool and marks it live; the file itself is created by the first write. */
    fun create(dir: File): File = synchronized(this) {
        dir.mkdirs()
        File(dir, UUID.randomUUID().toString() + CAPTION_SPOOL_SUFFIX).also { live += it.name }
    }

    /** `false` when the file is still there; the next [sweep] retries it. */
    fun release(file: File): Boolean = synchronized(this) {
        live -= file.name
        !file.exists() || file.delete()
    }

    /** Deletes every file in [dir] no running request owns, all attempted even after one fails. */
    fun sweep(dir: File): Boolean = synchronized(this) {
        val files = dir.listFiles() ?: return !dir.exists()
        files.filter { it.name !in live }.map { it.deleteRecursively() }.all { it }
    }
}

/**
 * Copies [source] into [spool] in chunks, hashing the bytes on the way, and closes [source] whatever happens. Reads at most
 * [limit] + 1 bytes: one more than [limit] is [CaptionOutcome.TooLarge]. [check] runs before every read. Returns the SHA-256 hex.
 */
internal fun captionAcquire(source: InputStream, spool: File, check: () -> Unit, limit: Long = CAPTION_MAX_ENCODED_BYTES): String {
    val digest = MessageDigest.getInstance("SHA-256")
    source.use { input ->
        FileOutputStream(spool).use { out ->
            val chunk = ByteArray(COPY_CHUNK_BYTES)
            var total = 0L
            while (true) {
                check()
                val read = input.read(chunk, 0, minOf(chunk.size.toLong(), limit + 1 - total).toInt())
                if (read < 0) break
                total += read
                if (total > limit) throw CaptionStop(CaptionOutcome.TooLarge)
                digest.update(chunk, 0, read)
                out.write(chunk, 0, read)
            }
        }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** RFC 7845: the first Ogg page begins the stream (version 0, BOS set) and carries the Opus ID header alone. */
internal fun captionIsOggOpus(head: ByteArray): Boolean {
    if (head.size < OGG_PAGE_HEADER_BYTES || String(head, 0, 4, Charsets.US_ASCII) != "OggS") return false
    if (head[4].toInt() != 0 || head[5].toInt() and OGG_BOS == 0) return false
    val segments = head[OGG_SEGMENTS_OFFSET].toInt() and 0xff
    val at = OGG_PAGE_HEADER_BYTES + segments
    if (segments == 0 || head.size < at || head[5].toInt() and 0x01 != 0) return false
    var packetBytes = 0
    repeat(segments) { index ->
        val lace = head[OGG_PAGE_HEADER_BYTES + index].toInt() and 0xff
        // Exactly one packet, completed on this page; a zero terminator after a 255-byte segment is valid.
        if ((index < segments - 1 && lace != 255) || (index == segments - 1 && lace == 255)) return false
        packetBytes += lace
    }
    return packetBytes >= 19 && head.size >= at + packetBytes &&
        String(head, at, 8, Charsets.US_ASCII) == "OpusHead"
}

/** Where a [CaptionDecoder] puts what it decodes. */
internal interface CaptionPcmSink {
    /** Before the first [samples]; a later call must repeat the same format. */
    fun format(sampleRate: Int, channels: Int, pcm16: Boolean)

    /** Interleaved PCM16 frames, read from position to limit. */
    fun samples(pcm: ShortBuffer)
}

/** Decodes the spooled file into the sink; calls `check` between buffers, which throws to end the decode. */
internal interface CaptionDecoder {
    fun decode(file: File, sink: CaptionPcmSink, check: () -> Unit)
}

/**
 * Decoded PCM16 at a multiple of 16 kHz, mono or stereo, to 16 kHz mono PCM16: channels averaged, then each run of
 * `rate / 16000` frames averaged into one sample; a trailing partial run is dropped. Every decoded frame counts toward
 * [maxSeconds] at the decoder's rate, so the output never exceeds `maxSeconds * 16000` samples (9.6 MB at five minutes).
 */
internal class CaptionNormalizer(private val maxSeconds: Int = CAPTION_MAX_SECONDS) : CaptionPcmSink {

    private var rate = 0
    private var channels = 0
    private var factor = 0
    private var sum = 0L
    private var summed = 0
    private val out = ByteArrayOutputStream()

    var frames = 0L
        private set

    override fun format(sampleRate: Int, channels: Int, pcm16: Boolean) {
        val supported = pcm16 && channels in 1..MAX_CHANNELS && sampleRate >= TARGET_RATE && sampleRate % TARGET_RATE == 0
        val changed = rate != 0 && (sampleRate != rate || channels != this.channels)
        if (!supported || changed) throw CaptionStop(CaptionOutcome.Unsupported)
        rate = sampleRate
        this.channels = channels
        factor = sampleRate / TARGET_RATE
    }

    override fun samples(pcm: ShortBuffer) {
        if (rate == 0 || pcm.remaining() % channels != 0) throw CaptionStop(CaptionOutcome.Malformed)
        val count = pcm.remaining() / channels
        if (frames + count > maxSeconds.toLong() * rate) throw CaptionStop(CaptionOutcome.TooLong)
        frames += count
        // shortcut: a box average is a weak anti-alias filter above 8 kHz; use a windowed-sinc FIR if a handset row shows STT losing words to it.
        val bytes = ByteArray((count / factor + 1) * 2)
        var at = 0
        repeat(count) {
            var frame = 0
            repeat(channels) { frame += pcm.get() }
            sum += frame
            if (++summed == factor) {
                val sample = (sum / (factor * channels)).toInt()
                bytes[at++] = sample.toByte()
                bytes[at++] = (sample shr 8).toByte()
                sum = 0
                summed = 0
            }
        }
        out.write(bytes, 0, at)
    }

    /** The 44-byte-header 16 kHz mono WAV every [Transcriber] takes; no frame at all is malformed audio, not silence. */
    fun toWav(): ByteArray {
        if (frames == 0L) throw CaptionStop(CaptionOutcome.Malformed)
        return wav(out.toByteArray())
    }
}
