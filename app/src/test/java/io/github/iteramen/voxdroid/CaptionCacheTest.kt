package io.github.iteramen.voxdroid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CaptionCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var time = T0
    private lateinit var filesDir: File
    private lateinit var dir: File
    private lateinit var cache: CaptionCache

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        dir = captionDir(filesDir)
        cache = CaptionCache(dir, { time })
    }

    private fun on(): CaptionTicket {
        assertTrue(cache.setEnabled(true))
        return cache.begin()!!
    }

    private fun save(ticket: CaptionTicket, key: CaptionKey = KEY, raw: String = "hello there") =
        cache.put(ticket, key, raw, raw, CaptionCleanup.Off)

    private fun entries() = dir.listFiles().orEmpty().filter { it.name != CaptionCache.STATE }.map { it.name }.toSet()

    @Test
    fun isOffByDefaultAndWritesNothing() {
        assertFalse(cache.enabled)
        assertNull(cache.begin())
        assertEquals(CaptionLookup.Disabled, cache.get(KEY))
        assertTrue(cache.prune())
        assertFalse("no directory is created while off", dir.exists())
    }

    @Test
    fun roundTripsUnicodeAndTheCleanupOutcome() {
        val ticket = on()
        val raw = "שלום, Ольга 👩‍👩‍👧 ‏\"quoted\" \\ back\nslash\t\u0000 𝄞 é"
        val displayed = "Shalom, Ольга 👩‍👩‍👧."
        assertEquals(CaptionWrite.Saved, cache.put(ticket, KEY, raw, displayed, CaptionCleanup.Cleaned))
        assertEquals(CaptionLookup.Hit(Caption(raw, displayed, CaptionCleanup.Cleaned, T0)), cache.get(KEY))

        val other = KEY.copy(audioSha256 = SHA_B)
        assertEquals(CaptionWrite.Saved, cache.put(ticket, other, raw, raw, CaptionCleanup.Fallback))
        assertEquals("a raw-text fallback keeps its outcome", CaptionCleanup.Fallback, (cache.get(other) as CaptionLookup.Hit).caption.cleanup)
    }

    @Test
    fun aCompatibleKeyReopensTheResultAndAnyOtherPartMisses() {
        assertEquals(CaptionWrite.Saved, save(on()))
        val reopened = CaptionCache(dir, { time })
        assertTrue(reopened.get(CaptionKey(ALG, SHA_A, PROFILE)) is CaptionLookup.Hit)
        assertEquals(CaptionLookup.Miss, reopened.get(KEY.copy(audioSha256 = SHA_B)))
        assertEquals(CaptionLookup.Miss, reopened.get(KEY.copy(profile = "$PROFILE;cleanup=other")))
        assertEquals(CaptionLookup.Miss, reopened.get(KEY.copy(algorithm = "pcm16-sha256.v2")))
    }

    @Test
    fun aKeyRefusesWhatIsNotAVersionedFingerprintAndProfile() {
        val bad = listOf(
            { CaptionKey("", SHA_A, PROFILE) },
            { CaptionKey("PCM", SHA_A, PROFILE) },
            { CaptionKey("v1\nx", SHA_A, PROFILE) },
            { CaptionKey(ALG, SHA_A.uppercase(), PROFILE) },
            { CaptionKey(ALG, SHA_A.dropLast(1), PROFILE) },
            { CaptionKey(ALG, SHA_A, " ") },
            { CaptionKey(ALG, SHA_A, "x".repeat(MAX_PROFILE_CHARS + 1)) },
            { CaptionKey(ALG, SHA_A, "a\uD800b") },
        )
        bad.forEachIndexed { i, make ->
            try {
                make()
                fail("key $i was accepted")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertNotNull(CaptionKey(ALG, SHA_A, "x".repeat(MAX_PROFILE_CHARS)))
    }

    @Test
    fun expiresExactlyThirtyDaysAfterTheWrite() {
        save(on())
        time = T0 + CAPTION_TTL_MS - 1
        assertTrue(cache.get(KEY) is CaptionLookup.Hit)
        time = T0 + CAPTION_TTL_MS
        assertEquals(CaptionLookup.Miss, cache.get(KEY))
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun readsDoNotRenewTheLifetime() {
        save(on())
        for (day in 1..29) {
            time = T0 + day * DAY_MS
            assertTrue("day $day", cache.get(KEY) is CaptionLookup.Hit)
        }
        time = T0 + CAPTION_TTL_MS
        assertEquals(CaptionLookup.Miss, cache.get(KEY))
    }

    @Test
    fun opensPrunedWithoutAnyLookup() {
        save(on())
        time = T0 + CAPTION_TTL_MS
        assertEquals(1, entries().size)
        openCaptionCache(filesDir) { time }
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun aWriteOfAnotherKeyPrunes() {
        val ticket = on()
        save(ticket)
        time = T0 + CAPTION_TTL_MS
        assertEquals(CaptionWrite.Saved, save(ticket, KEY.copy(audioSha256 = SHA_B)))
        assertEquals(setOf(KEY.copy(audioSha256 = SHA_B).digest + CaptionCache.ENTRY_SUFFIX), entries())
    }

    @Test
    fun aLookupOfAnotherKeyPrunes() {
        save(on())
        time = T0 + CAPTION_TTL_MS
        assertEquals(CaptionLookup.Miss, cache.get(KEY.copy(audioSha256 = SHA_B)))
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun aPruneRemovesALeftOverSidecarAndAnyStrangerInTheDirectory() {
        save(on())
        File(dir, "${KEY.digest}${CaptionCache.ENTRY_SUFFIX}.tmp").writeText("half a transcript")
        File(dir, "notes.txt").writeText("x")
        File(dir, "sub").mkdir()
        assertTrue(cache.prune())
        assertEquals(setOf(KEY.digest + CaptionCache.ENTRY_SUFFIX), entries())
    }

    @Test
    fun deleteAllClearsEveryCaptionStaysOnAndFencesEarlierTickets() {
        val early = on()
        save(early)
        save(early, KEY.copy(audioSha256 = SHA_B))
        assertTrue(cache.deleteAll())
        assertTrue(cache.enabled)
        assertEquals(emptySet<String>(), entries())
        assertEquals(CaptionWrite.Stale, save(early))
        assertEquals(CaptionLookup.Miss, cache.get(KEY))
        assertEquals(CaptionWrite.Saved, save(cache.begin()!!))
    }

    @Test
    fun turningOffDeletesEverythingAndAnInFlightWriteCannotRecreateIt() {
        val inFlight = on()
        save(inFlight)
        assertTrue(cache.setEnabled(false))
        assertFalse(dir.exists() && dir.listFiles().orEmpty().isNotEmpty())
        assertEquals(CaptionWrite.Disabled, save(inFlight))
        assertFalse(dir.exists() && dir.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun aTicketFromBeforeAnOffOnCycleIsStale() {
        val inFlight = on()
        assertTrue(cache.setEnabled(false))
        assertTrue(cache.setEnabled(true))
        assertEquals(CaptionWrite.Stale, save(inFlight))
        assertEquals(CaptionLookup.Miss, cache.get(KEY))
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun turningOnTwiceKeepsTheTicketsValid() {
        val ticket = on()
        assertTrue(cache.setEnabled(true))
        assertEquals(CaptionWrite.Saved, save(ticket))
    }

    @Test
    fun aRequestThatNeverCompletesLeavesAMiss() {
        on()
        assertEquals(CaptionLookup.Miss, cache.get(KEY))
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun refusesABlankOrUnencodableTranscriptAndNeverTruncates() {
        val ticket = on()
        assertEquals(CaptionWrite.Invalid, save(ticket, raw = "  "))
        assertEquals(CaptionWrite.Invalid, save(ticket, raw = "a\uDC00b"))
        assertEquals(CaptionWrite.TooLarge, save(ticket, raw = "ж".repeat(MAX_CAPTION_RECORD_BYTES / 4)))
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun aCorruptOversizedForeignOrFutureRecordIsAMissAndIsDropped() {
        on()
        val file = File(dir, KEY.digest + CaptionCache.ENTRY_SUFFIX)
        val good = JSONObject()
            .put("v", CAPTION_FORMAT).put("key", KEY.digest).put("raw", "r").put("displayed", "d")
            .put("cleanup", "Off").put("written_at", T0)
        val cases = mapOf(
            "not json" to "{\"v\":1,\"raw\":\"secr",
            "future format" to JSONObject(good.toString()).put("v", CAPTION_FORMAT + 1).toString(),
            "another key" to JSONObject(good.toString()).put("key", SHA_B).toString(),
            "unknown outcome" to JSONObject(good.toString()).put("cleanup", "Sideways").toString(),
            "dated after the clock" to JSONObject(good.toString()).put("written_at", T0 + 1).toString(),
            "oversized" to JSONObject(good.toString()).put("raw", "x".repeat(MAX_CAPTION_RECORD_BYTES)).toString(),
        )
        file.writeText(good.toString())
        assertTrue("the control record must hit", cache.get(KEY) is CaptionLookup.Hit)
        val unrelated = File(filesDir, "unrelated.txt").apply { writeText("keep") }
        for ((case, text) in cases) {
            file.writeText(text)
            assertEquals(case, CaptionLookup.Miss, cache.get(KEY))
            assertFalse(case, file.exists())
        }
        assertEquals("keep", unrelated.readText())
        assertTrue(cache.enabled)
    }

    @Test
    fun aCorruptStateReadsAsOffAndDropsTheCaptions() {
        save(on())
        File(dir, CaptionCache.STATE).writeText("enabled")
        assertFalse(cache.enabled)
        assertEquals(CaptionLookup.Disabled, cache.get(KEY))
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun aFailedRenameKeepsTheOldRecordAndLeavesNoSidecar() {
        save(on())
        val failing = CaptionCache(dir, { time }) { _, _ -> throw IOException("disk full") }
        assertEquals(CaptionWrite.Failed, failing.put(cache.begin()!!, KEY, "new", "new", CaptionCleanup.Off))
        assertEquals("hello there", (cache.get(KEY) as CaptionLookup.Hit).caption.raw)
        assertEquals(setOf(KEY.digest + CaptionCache.ENTRY_SUFFIX), entries())
    }

    @Test
    fun aFailedEpochWriteLeavesRememberingOff() {
        save(on())
        val failing = CaptionCache(dir, { time }) { _, _ -> throw IOException("disk full") }
        assertFalse(failing.deleteAll())
        assertFalse(cache.enabled)
        assertEquals(emptySet<String>(), entries())
    }

    @Test
    fun anUnusableDirectoryFailsVisibly() {
        dir.writeText("not a directory")
        assertFalse(cache.setEnabled(true))
        assertFalse(cache.enabled)
        assertEquals("not a directory", dir.readText())
    }

    @Test
    fun aSuccessfulWriteLeavesNoSidecar() {
        save(on())
        assertEquals(setOf(CaptionCache.STATE, KEY.digest + CaptionCache.ENTRY_SUFFIX), dir.list()!!.toSet())
    }

    @Test
    fun aRecordHoldsTheTextOutcomeAndTimeAndNoAudioProfileOrCredential() {
        val profile = "stt=https://h/v1;model=whisper-1;sentinel-profile-value"
        val key = CaptionKey(ALG, SHA_A, profile)
        assertEquals(CaptionWrite.Saved, cache.put(on(), key, "raw", "shown", CaptionCleanup.SkippedShort))
        val file = File(dir, key.digest + CaptionCache.ENTRY_SUFFIX)
        val json = JSONObject(file.readText())
        assertEquals(setOf("v", "key", "raw", "displayed", "cleanup", "written_at"), json.keySet())
        val bytes = dir.listFiles()!!.joinToString("") { it.readText() }
        assertFalse("profile on disk", bytes.contains("sentinel-profile-value"))
        assertFalse("audio hash on disk", bytes.contains(SHA_A))
    }

    private companion object {
        const val T0 = 1_800_000_000_000L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val ALG = "pcm16-sha256.v1"
        const val PROFILE = "stt=whisper-1;cleanup=on"
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)
        val KEY = CaptionKey(ALG, SHA_A, PROFILE)
    }
}
