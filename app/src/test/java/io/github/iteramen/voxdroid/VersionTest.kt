package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {

    @Test
    fun theVersionIsTheVersionNameThenTheCommitInParentheses() {
        assertEquals("version name, then the short commit", "0.1.0 (8e3d00f)", versionLine("0.1.0", "8e3d00f"))
    }

    @Test
    fun aCommitGitCouldNotAnswerReadsUnknown() {
        assertEquals("the unknown fallback keeps the shape", "0.1.0 (unknown)", versionLine("0.1.0", "unknown"))
    }
}
