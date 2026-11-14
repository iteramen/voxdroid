package io.github.iteramen.voxdroid

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class LogViewSessionTest {
    @Test
    fun anOpenDetailCannotCopyItsSnapshotAfterExpiry() {
        var time = 1_800_000_000_000L
        val session = LogViewSession { time }
        session.replace(listOf(DictationEntry("fixture", time, "test", LogOutcome.Cleaned, "synthetic", "Synthetic")))
        time += DICTATION_LOG_TTL_MS
        assertNull(session.entry("fixture"))
        assertTrue(session.entries.isEmpty())
    }

    @Test
    fun deletionAndNewLoadsFenceOldResultsEvenAfterReenabling() {
        val session = LogViewSession { 1000L }
        val record = DictationEntry("fixture", 900L, "test", LogOutcome.Cleaned, "synthetic", "Synthetic")
        session.resume()
        val oldRequest = session.invalidate()
        val newRequest = session.invalidate()
        assertFalse(session.accept(oldRequest, "old", listOf(record), "new"))
        assertTrue(session.entries.isEmpty())
        assertTrue(session.accept(newRequest, "new", listOf(record), "new"))
        assertEquals(record, session.currentEntry("fixture", "new"))
        assertNull(session.currentEntry("fixture", "deleted-epoch"))
        session.pause()
        assertTrue(session.entries.isEmpty())
        assertFalse(session.accept(newRequest, "new", listOf(record), "new"))
    }

    @Test
    fun noConsentCannotExposeRowsAndExpiryHasAnExactDeadline() {
        var time = 1000L
        val session = LogViewSession { time }
        val record = DictationEntry("fixture", time, "test", LogOutcome.Silence)
        session.resume()
        assertTrue(session.accept(session.invalidate(), null, listOf(record), null))
        assertTrue(session.entries.isEmpty())
        assertTrue(session.accept(session.invalidate(), "on", listOf(record), "on"))
        assertEquals(DICTATION_LOG_TTL_MS, session.nextExpiryDelay())
        time += DICTATION_LOG_TTL_MS - 1
        assertEquals(1L, session.nextExpiryDelay())
        time++
        assertNull(session.currentEntry("fixture", "on"))
        assertTrue(session.entries.isEmpty())
        assertNull(session.nextExpiryDelay())
    }
}
