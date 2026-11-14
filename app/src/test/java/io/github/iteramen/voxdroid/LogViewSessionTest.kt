package io.github.iteramen.voxdroid

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
