package io.github.iteramen.voxdroid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.security.MessageDigest

class CaptionAudioTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exactlyTheByteLimitIsSpooledAndHashedWhateverTheStreamClaims() {
        val spool = temp.newFile("spool.ogg")
        val source = CaptionSource(CAPTION_MAX_ENCODED_BYTES, claims = 0)

        val sha = captionAcquire(source, spool, {})

        assertEquals(CAPTION_MAX_ENCODED_BYTES, spool.length())
        assertEquals(CaptionSource(CAPTION_MAX_ENCODED_BYTES).sha256(), sha)
        assertTrue("the source is closed", source.closed)
    }

    @Test
    fun oneByteOverTheLimitIsTooLargeAndNothingPastItIsRead() {
        val spool = temp.newFile("spool.ogg")
        val endless = CaptionSource(Long.MAX_VALUE, claims = 1)

        assertEquals(CaptionOutcome.TooLarge, stopOf { captionAcquire(endless, spool, {}) })

        assertEquals(CAPTION_MAX_ENCODED_BYTES + 1, endless.served)
        assertTrue("the source is closed", endless.closed)
    }

    @Test
    fun aStreamThatClaimsMoreThanTheLimitButHoldsLessIsAccepted() {
        val spool = temp.newFile("spool.ogg")
        val source = CaptionSource(1000, claims = Int.MAX_VALUE)

        captionAcquire(source, spool, {})

        assertEquals(1000L, spool.length())
    }

    @Test
    fun aReadFailureClosesTheSourceAndIsNotSwallowed() {
        val source = CaptionSource(Long.MAX_VALUE, failAfter = 100_000)

        try {
            captionAcquire(source, temp.newFile("spool.ogg"), {})
            fail("the read failure must reach the caller")
        } catch (expected: IOException) {
        }

        assertTrue("the source is closed", source.closed)
    }

    @Test
    fun theCheckRunsBeforeEveryReadAndStopsTheCopy() {
        val source = CaptionSource(Long.MAX_VALUE)
        var reads = 0

        val outcome = stopOf {
            captionAcquire(source, temp.newFile("spool.ogg"), { if (++reads > 3) throw CaptionStop(CaptionOutcome.Cancelled) })
        }

        assertEquals(CaptionOutcome.Cancelled, outcome)
        assertEquals("three chunks read before the stop", 3L * 64 * 1024, source.served)
        assertTrue("the source is closed", source.closed)
    }

    @Test
    fun onlyAnOggStreamWhoseFirstPageIsTheOpusIdHeaderIsOggOpus() {
        assertTrue(captionIsOggOpus(captionOgg().copyOf(CAPTION_HEAD_BYTES)))
        assertTrue("a short file", captionIsOggOpus(captionOgg(ByteArray(0))))

        val vorbis = captionOgg().also { "\u0001vorbis\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(it, 28) }
        val notBos = captionOgg().also { it[5] = 0 }
        val version1 = captionOgg().also { it[4] = 1 }
        val noSegments = captionOgg().also { it[26] = 0 }
        val wav = wav(ByteArray(3200))
        val cases = mapOf(
            "vorbis" to vorbis, "no BOS flag" to notBos, "version 1" to version1, "no segment" to noSegments,
            "a WAV" to wav, "empty" to ByteArray(0), "truncated header" to captionOgg().copyOf(30),
        )
        for ((name, bytes) in cases) assertFalse(name, captionIsOggOpus(bytes))
    }

    @Test
    fun opusIdMustBeTheSingleCompleteFirstPagePacket() {
        val valid = captionOgg(ByteArray(0))
        val twoPackets = valid.copyOfRange(0, 27).also { it[26] = 2 } +
            byteArrayOf(19, 10) + valid.copyOfRange(28, 47) + ByteArray(10)
        val unfinished = captionOgg().also { it[27] = 255.toByte() }
        val shortId = valid.copyOf(46).also { it[27] = 18 }
        val continued = valid.copyOf().also { it[5] = 3 }
        for ((name, head) in mapOf("two packets" to twoPackets, "unfinished" to unfinished,
            "short ID" to shortId, "continued first page" to continued)) {
            assertFalse(name, captionIsOggOpus(head))
        }
    }

    @Test
    fun stereo48kHzBecomesMono16kHzByAveraging() {
        val normalizer = CaptionNormalizer()
        normalizer.format(48_000, 2, true)

        // Three stereo frames per output sample: (300 + 100) / 2 averaged over three frames, then -200 the same way.
        normalizer.samples(ShortBuffer.wrap(shortArrayOf(300, 100, 300, 100, 300, 100, -100, -300, -100, -300, -100, -300, 7, 7)))

        val wav = normalizer.toWav()
        assertEquals(7L, normalizer.frames)
        assertArrayEquals("the trailing partial run is dropped", shortArrayOf(200, -200), pcmOf(wav))
    }

    @Test
    fun runsSpanCallsAndMono16kHzPassesThroughUnchanged() {
        val split = CaptionNormalizer().apply { format(48_000, 1, true) }
        split.samples(ShortBuffer.wrap(shortArrayOf(30, 60)))
        split.samples(ShortBuffer.wrap(shortArrayOf(90, 1, 2, 3)))
        assertArrayEquals(shortArrayOf(60, 2), pcmOf(split.toWav()))

        val same = CaptionNormalizer().apply { format(16_000, 1, true) }
        same.samples(ShortBuffer.wrap(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)))
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE), pcmOf(same.toWav()))
    }

    @Test
    fun theOutputIsTheSixteenKilohertzMonoWavContract() {
        val normalizer = CaptionNormalizer().apply { format(48_000, 2, true) }
        normalizer.samples(ShortBuffer.wrap(ShortArray(48_000 * 2)))

        val out = normalizer.toWav()

        val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals("PCM", 1, header.getShort(20).toInt())
        assertEquals("mono", 1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals("bits", 16, header.getShort(34).toInt())
        assertEquals("data", String(out, 36, 4, Charsets.US_ASCII))
        assertEquals(16_000 * 2, header.getInt(40))
        assertEquals(44 + 16_000 * 2, out.size)
    }

    @Test
    fun fiveMinutesOfDecodedFramesIsTheLastAcceptedFrame() {
        val normalizer = CaptionNormalizer().apply { format(48_000, 1, true) }
        val second = ShortArray(48_000)
        repeat(CAPTION_MAX_SECONDS) { normalizer.samples(ShortBuffer.wrap(second)) }

        assertEquals(CaptionOutcome.TooLong, stopOf { normalizer.samples(ShortBuffer.wrap(ShortArray(1))) })

        val wav = normalizer.toWav()
        assertEquals(CAPTION_MAX_SECONDS * 48_000L, normalizer.frames)
        assertEquals(44 + CAPTION_MAX_SECONDS * 16_000 * 2, wav.size)
    }

    @Test
    fun aStereoFrameCountsOnceTowardTheLimit() {
        val normalizer = CaptionNormalizer(maxSeconds = 1).apply { format(16_000, 2, true) }

        normalizer.samples(ShortBuffer.wrap(ShortArray(16_000 * 2)))

        assertEquals(CaptionOutcome.TooLong, stopOf { normalizer.samples(ShortBuffer.wrap(ShortArray(2))) })
    }

    @Test
    fun unsupportedFormatsAndMalformedStreamsAreRefused() {
        val unsupported = listOf(
            Triple(44_100, 1, true), Triple(8_000, 1, true), Triple(24_000, 1, true), Triple(0, 1, true),
            Triple(48_000, 0, true), Triple(48_000, 3, true), Triple(48_000, 1, false),
        )
        for ((rate, channels, pcm16) in unsupported) {
            assertEquals("$rate Hz, $channels ch, pcm16 $pcm16", CaptionOutcome.Unsupported, stopOf { CaptionNormalizer().format(rate, channels, pcm16) })
        }

        val changed = CaptionNormalizer().apply { format(48_000, 1, true) }
        changed.format(48_000, 1, true)
        assertEquals("a format change mid-stream", CaptionOutcome.Unsupported, stopOf { changed.format(48_000, 2, true) })

        assertEquals("samples before a format", CaptionOutcome.Malformed, stopOf { CaptionNormalizer().samples(ShortBuffer.wrap(ShortArray(2))) })
        val stereo = CaptionNormalizer().apply { format(48_000, 2, true) }
        assertEquals("half a stereo frame", CaptionOutcome.Malformed, stopOf { stereo.samples(ShortBuffer.wrap(ShortArray(3))) })
        val empty = CaptionNormalizer().apply { format(48_000, 1, true) }
        assertEquals("no frame at all", CaptionOutcome.Malformed, stopOf { empty.toWav() })
    }

    @Test
    fun theSweepDeletesOnlyFilesNoRunningRequestOwns() {
        val dir = temp.newFolder("caption_spool")
        val live = CaptionSpools.create(dir).also { it.writeText("live") }
        val stray = File(dir, "abandoned.ogg").also { it.writeText("old") }

        assertTrue(CaptionSpools.sweep(dir))

        assertTrue("a running request's spool stays", live.exists())
        assertFalse("an abandoned spool goes", stray.exists())
        assertTrue(CaptionSpools.release(live))
        assertFalse(live.exists())
        assertTrue("a missing directory sweeps clean", CaptionSpools.sweep(File(dir, "missing")))
    }

    @Test
    fun aDecodedStreamFedThroughTheInterfaceReadsFromPositionToLimit() {
        val normalizer = CaptionNormalizer().apply { format(16_000, 1, true) }
        val buffer = ShortBuffer.wrap(shortArrayOf(9, 1, 2, 9))
        buffer.position(1)
        buffer.limit(3)

        normalizer.samples(buffer)

        assertArrayEquals(shortArrayOf(1, 2), pcmOf(normalizer.toWav()))
    }
}

internal fun stopOf(block: () -> Unit): CaptionOutcome {
    try {
        block()
    } catch (stop: CaptionStop) {
        return stop.outcome
    }
    throw AssertionError("expected a CaptionStop")
}

internal fun pcmOf(wav: ByteArray): ShortArray {
    val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    return ShortArray(pcm.remaining()).also { pcm.get(it) }
}

/** A synthetic Ogg/Opus file: a first page carrying only a 19-byte `OpusHead`, then [body]; no real audio. */
internal fun captionOgg(body: ByteArray = ByteArray(64) { it.toByte() }): ByteArray {
    val page = ByteArray(27)
    "OggS".toByteArray(Charsets.US_ASCII).copyInto(page)
    page[5] = 0x02
    page[26] = 1
    val opusHead = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
    return page + byteArrayOf(opusHead.size.toByte()) + opusHead + body
}

/** [size] generated bytes; [claims] is what `available()` says, which acquisition must not trust; [failAfter] throws there. */
internal class CaptionSource(
    private val size: Long,
    private val claims: Int = 0,
    private val failAfter: Long = Long.MAX_VALUE,
) : InputStream() {
    var served = 0L
        private set
    var closed = false
        private set

    override fun available() = claims

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (served >= size) return -1
        if (served >= failAfter) throw IOException("provider went away")
        val n = minOf(len.toLong(), size - served).toInt()
        for (i in 0 until n) b[off + i] = ((served + i) % 251).toByte()
        served += n
        return n
    }

    override fun close() {
        closed = true
    }

    fun sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = read(chunk, 0, chunk.size)
            if (n < 0) break
            digest.update(chunk, 0, n)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
