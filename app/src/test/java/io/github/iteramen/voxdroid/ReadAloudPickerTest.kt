package io.github.iteramen.voxdroid

import android.view.accessibility.AccessibilityEvent as Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Read aloud's close paths, run through the lifecycle the controller itself runs them through. */
class ReadAloudPickerTest {
    private class Screen {
        var pill: String? = null
        var removed = 0
        var hides = 0
        var invalidated = 0
        var released = 0
        var spoken: String? = null
        val life = ReadAloudLifecycle<String, String>({ removed++ }, { hides++; pill = null }, { invalidated++ }, { released++ })

        /** The controller's choose: the overlay goes up with its choices, then the pick hint is posted. */
        fun openPicker() { life.pick("overlay", listOf("first", "second")); pill = PICK_HINT }

        /** The controller's speak: a narration starts. */
        fun speak(choice: String) { spoken = choice; life.active = true }
    }

    @Test
    fun everyCloseOfAPickerThatWasUpWithdrawsTheHintExactlyOnce() {
        val paths = mapOf<String, (Screen) -> Unit>(
            // The pick starts a narration; its worker cancels when the narration ends.
            "pick a choice" to { s -> s.life.touched("first", { true }, s::speak); s.life.cancel() },
            "pick a choice that is no longer current" to { s -> s.life.touched("first", { false }, s::speak) },
            "tap outside every choice" to { s -> s.life.touched(null, { true }, s::speak) },
            "tap the speaker again" to { s -> s.life.speakerTapped { fail("a second picker must not open") } },
            "another app comes in front" to { s -> assertTrue(s.life.observed(false, false, Event.TYPE_WINDOW_STATE_CHANGED) { true }) },
            "the source changes its content" to { s -> assertTrue(s.life.observed(true, true, Event.TYPE_WINDOW_CONTENT_CHANGED) { true }) })
        for ((path, close) in paths) {
            val screen = Screen()
            screen.openPicker()
            assertTrue(path, screen.life.busy)

            close(screen)

            assertEquals(path, 1, screen.removed)
            assertEquals(path, 1, screen.hides)
            assertNull(path, screen.pill)
            assertFalse(path, screen.life.picker.up)
            assertTrue(path, screen.life.choices.isEmpty())
            assertFalse(path, screen.life.busy)
            assertEquals(path, 1, screen.released)
            assertEquals(path, 1, screen.invalidated)
        }
    }

    @Test
    fun aCurrentChoiceIsSpokenAndNothingElseIs() {
        val picked = Screen().apply { openPicker(); life.touched("first", { true }, ::speak) }
        assertEquals("first", picked.spoken)
        assertTrue(picked.life.active)
        assertEquals("a pick is not a cancel", 0, picked.released)

        val changed = Screen().apply { openPicker(); life.touched("first", { false }, ::speak) }
        assertNull(changed.spoken)
        val outside = Screen().apply { openPicker(); life.touched(null, { true }, ::speak) }
        assertNull(outside.spoken)
    }

    @Test
    fun aFailureNoticePostedAfterThePickSurvivesTheCancelThatFollows() {
        val screen = Screen()
        screen.openPicker()
        screen.life.touched("first", { true }, screen::speak)

        screen.pill = FAILURE
        screen.life.cancel()

        assertEquals(FAILURE, screen.pill)
        assertEquals(1, screen.hides)
        assertEquals(1, screen.removed)
        assertEquals(1, screen.released)
    }

    @Test
    fun aCancelWithNoPickerUpLeavesANoticeAlone() {
        val screen = Screen()
        screen.pill = FAILURE

        screen.life.cancel()

        assertEquals(FAILURE, screen.pill)
        assertEquals(0, screen.hides)
        assertEquals(0, screen.removed)
        assertEquals(1, screen.released)
    }

    @Test
    fun theSpeakerStartsOnlyWhenNothingIsUpAndStopsANarration() {
        val screen = Screen()
        var starts = 0

        screen.life.speakerTapped { starts++ }
        assertEquals(1, starts)
        assertEquals(0, screen.released)

        screen.speak("first")
        screen.life.speakerTapped { starts++ }
        assertEquals(1, starts)
        assertEquals(1, screen.released)
        assertFalse(screen.life.active)
    }

    @Test
    fun aNarrationOutlivesAnUnrelatedEventAndEndsWhenItsTextGoes() {
        val screen = Screen()
        screen.speak("first")

        assertFalse(screen.life.observed(true, true, Event.TYPE_WINDOW_CONTENT_CHANGED) { true })
        assertTrue(screen.life.active)
        assertEquals(0, screen.released)

        assertTrue(screen.life.observed(true, false, null) { false })
        assertFalse(screen.life.active)
        assertEquals(1, screen.released)
    }

    @Test
    fun aWindowThatWillNotGoStillTakesTheHintWithIt() {
        var hides = 0
        val window = PickerWindow<String>({ throw IllegalArgumentException("not attached") }, { hides++ })
        window.open("overlay")

        assertThrows(IllegalArgumentException::class.java) { window.close() }

        assertEquals(1, hides)
        assertFalse(window.up)
    }

    private companion object {
        const val PICK_HINT = "Tap the text to read"
        const val FAILURE = "Read aloud failed"
    }
}
