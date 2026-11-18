package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The controller's picker close paths, as the calls they make on its PickerWindow. */
class ReadAloudPickerTest {
    private class Screen {
        var pill: String? = null
        var removed = 0
        var hides = 0
        val picker = PickerWindow<String>({ removed++ }, { hides++; pill = null })

        /** chooseOrStop: the overlay goes up, then the pick hint is posted. */
        fun openPicker() { picker.open("overlay"); pill = PICK_HINT }

        /** hidePicker, reached directly or through cancel. */
        fun close() = picker.close()
    }

    @Test
    fun everyCloseOfAPickerThatWasUpWithdrawsTheHintExactlyOnce() {
        val paths = mapOf(
            // onTouchEvent hides the picker, then speak's worker cancels when it ends.
            "pick a choice" to { s: Screen -> s.close(); s.close() },
            // onTouchEvent hides the picker, then cancels.
            "tap outside every choice" to { s: Screen -> s.close(); s.close() },
            // chooseOrStop finds the controller busy and cancels.
            "tap the speaker again" to { s: Screen -> s.close() },
            // observe finds the source invalidated and cancels.
            "the app changes" to { s: Screen -> s.close() })
        for ((path, closes) in paths) {
            val screen = Screen()
            screen.openPicker()

            closes(screen)

            assertEquals(path, 1, screen.removed)
            assertEquals(path, 1, screen.hides)
            assertNull(path, screen.pill)
            assertFalse(path, screen.picker.up)
        }
    }

    @Test
    fun aFailureNoticePostedAfterThePickSurvivesTheCancelThatFollows() {
        val screen = Screen()
        screen.openPicker()
        screen.close()

        screen.pill = FAILURE
        screen.close()

        assertEquals(FAILURE, screen.pill)
        assertEquals(1, screen.hides)
        assertEquals(1, screen.removed)
    }

    @Test
    fun aCancelWithNoPickerUpLeavesANoticeAlone() {
        val screen = Screen()
        screen.pill = FAILURE

        screen.close()

        assertEquals(FAILURE, screen.pill)
        assertEquals(0, screen.hides)
        assertEquals(0, screen.removed)
    }

    private companion object {
        const val PICK_HINT = "Tap the text to read"
        const val FAILURE = "Read aloud failed"
    }
}
