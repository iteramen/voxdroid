package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlPlacementTest {
    private val portrait = ControlSpace(1080, 2200, 104, 48, 1f)
    private val landscape = ControlSpace(2200, 1080, 104, 48, 1f)

    @Test fun allFourCornersKeepTheirEdgeMarginsOnRotation() {
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val before = ControlPosition(if (right) portrait.maxX - 12 else 12,
                if (bottom) portrait.maxY - 24 else 24)
            val anchor = rememberControl(before, portrait)
            assertEquals(ControlPosition(if (right) landscape.maxX - 12 else 12,
                if (bottom) landscape.maxY - 24 else 24), placeControl(anchor, landscape))
            assertEquals(before, placeControl(anchor, portrait))
        }
    }
    @Test fun smallerAreaClampsWithoutDestroyingTheSavedMargins() {
        val before = ControlPosition(portrait.maxX - 200, portrait.maxY - 300)
        val anchor = rememberControl(before, portrait)
        assertEquals(ControlPosition(0, 0), placeControl(anchor, ControlSpace(120, 80, 104, 48, 1f)))
        assertEquals(before, placeControl(anchor, portrait))
    }
    @Test fun aSingleButtonKeepsTheRightMargin() {
        val anchor = rememberControl(ControlPosition(portrait.maxX - 12, 24), portrait)
        val single = portrait.copy(controlWidth = 48)
        assertEquals(ControlPosition(single.maxX - 12, 24), placeControl(anchor, single))
    }
    @Test fun densityChangesKeepDpMargins() {
        val anchor = rememberControl(ControlPosition(portrait.maxX - 12, 24), portrait)
        val denser = ControlSpace(2160, 4400, 208, 96, 2f)
        assertEquals(ControlPosition(denser.maxX - 24, 48), placeControl(anchor, denser))
    }
    @Test fun aNewDragCanSwitchCorners() {
        val first = rememberControl(ControlPosition(12, 24), portrait)
        val dragged = rememberControl(ControlPosition(landscape.maxX - 32, landscape.maxY - 16), landscape)
        assertEquals(ControlPosition(12, 24), placeControl(first, landscape))
        assertEquals(ControlPosition(portrait.maxX - 32, portrait.maxY - 16), placeControl(dragged, portrait))
    }
    @Test fun offscreenLegacyCoordinatesAreClamped() {
        val anchor = rememberControl(ControlPosition(-20, 99999), portrait)
        assertEquals(ControlPosition(0, portrait.maxY), placeControl(anchor, portrait))
    }
}
