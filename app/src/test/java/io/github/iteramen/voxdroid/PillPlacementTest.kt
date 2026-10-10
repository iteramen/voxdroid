package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PillPlacementTest {

    private val h = 2424
    private val ph = 140
    private val margin = 24

    @Test
    fun aTapWobbleIsNotADrag() {
        assertFalse(isDrag(dx = 10f, dy = 10f, touchSlop = 21f))
        assertTrue(isDrag(dx = 30f, dy = 35f, touchSlop = 21f))
    }

    @Test
    fun theFirstPlacementIsJustAboveTheKeyboard() {
        assertEquals(1500 - margin - ph, place(null, ph, h, keyboardTop = 1500, margin = margin))
    }

    @Test
    fun aRememberedPositionRidesWithTheKeyboardTop() {
        val saved = remember(pillTop = 1200, pillH = ph, screenH = h, keyboardTop = 1500)
        assertEquals(160, saved.aboveKeyboard)
        assertEquals(1400, place(saved, ph, h, keyboardTop = 1700, margin = margin))
    }

    @Test
    fun aTallerPillKeepsItsBottomAboveTheKeyboard() {
        val saved = remember(pillTop = 1200, pillH = ph, screenH = h, keyboardTop = 1500)
        val taller = place(saved, 2 * ph, h, keyboardTop = 1500, margin = margin)
        assertEquals(1200 + ph, taller + 2 * ph)
    }

    @Test
    fun noKeyboardMeasuresFromTheScreenBottom() {
        assertEquals(h - margin - ph, place(null, ph, h, null, margin))
    }

    @Test
    fun thePillNeverLeavesTheScreen() {
        val far = Saved(aboveKeyboard = 9000)
        assertEquals(0, place(far, ph, h, keyboardTop = 1500, margin = margin))
        val below = Saved(aboveKeyboard = -9000)
        assertEquals(h - ph, place(below, ph, h, keyboardTop = 1500, margin = margin))
    }
}
