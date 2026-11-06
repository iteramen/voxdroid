package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSelectionTest {
    @Test fun telegramReportsTheSelectedSuffixInItsNativeToolbar() {
        assertEquals(1, captionTelegramSelectionCount("1 Selected"))
        assertEquals(2, captionTelegramSelectionCount("2 Selected"))
    }

    @Test fun bareCountsRemainSupported() {
        assertEquals(1, captionTelegramSelectionCount("1"))
        assertEquals(12, captionTelegramSelectionCount(" 12 "))
    }

    @Test fun unknownOrAmbiguousLabelsDoNotAuthorizeASelection() {
        listOf(null, "", "Selected", "0", "-1", "1 of 2 Selected", "1 voice message", "999999999999 Selected")
            .forEach { assertNull(it, captionTelegramSelectionCount(it)) }
    }

    @Test fun selectionCountCannotReplaceTheConversationHeader() {
        listOf("1 Selected", "2 Selected", "1 selected", "1", "0 Selected")
            .forEach { assertNull(it, captionTelegramHeader(it)) }
        assertEquals("Synthetic fixture", captionTelegramHeader("Synthetic fixture"))
    }
}
