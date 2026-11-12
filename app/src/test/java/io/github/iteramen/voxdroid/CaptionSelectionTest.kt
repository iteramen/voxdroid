package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionSelectionTest {
    @Test fun selectedTelegramVoiceWaveformIsASeekBar() {
        assertTrue(captionTelegramVoiceNode(true, "android.widget.SeekBar") { "Voice message, 4 seconds" })
    }

    @Test fun existingVoiceContainersRemainSupported() {
        for (role in listOf("android.view.ViewGroup", "android.widget.LinearLayout")) {
            assertTrue(captionTelegramVoiceNode(true, role) { "Voice message, 4 seconds" })
        }
    }

    @Test fun unselectedOrUnknownNodesDoNotReadAnyMessageMetadata() {
        for ((selected, role) in listOf(false to "android.widget.SeekBar", false to "android.view.ViewGroup",
            true to "android.widget.EditText", true to "android.widget.TextView", true to null)) {
            assertFalse(captionTelegramVoiceNode(selected, role) { error("Unselected or unknown metadata was read") })
        }
    }

    @Test fun genericSeekBarsAndNonVoiceMessagesAreNotVoiceNotes() {
        assertFalse(captionTelegramVoiceNode(true, "android.widget.SeekBar") { "Playback position" })
        assertFalse(captionTelegramVoiceNode(true, "android.view.ViewGroup") { "Synthetic ordinary message" })
    }

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
