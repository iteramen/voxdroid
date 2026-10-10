package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeGestureTest {

    @Test
    fun aHoldStartsATakeAndItsReleaseDoesNothingMore() {
        val g = VolumeGesture()
        assertEquals(OnDown.ArmHold, g.down(repeatCount = 0, recording = false))
        assertTrue(g.holdElapsed())
        assertEquals(OnUp.Nothing, g.up())
    }

    @Test
    fun aShortPressIsStillAVolumePress() {
        val g = VolumeGesture()
        assertEquals(OnDown.ArmHold, g.down(0, recording = false))
        assertEquals(OnUp.ReapplyVolume, g.up())
        assertFalse("a timer firing after the release starts nothing", g.holdElapsed())
    }

    @Test
    fun aPlainPressWhileRecordingStops() {
        val g = VolumeGesture()
        assertEquals(OnDown.Stop, g.down(0, recording = true))
        assertEquals(OnUp.Nothing, g.up())
    }

    @Test
    fun repeatsWhileHeldAreSwallowed() {
        val g = VolumeGesture()
        g.down(0, recording = false)
        assertEquals(OnDown.Swallow, g.down(repeatCount = 1, recording = false))
        assertEquals(OnDown.Swallow, g.down(repeatCount = 7, recording = false))
        assertTrue(g.holdElapsed())
        assertEquals(OnUp.Nothing, g.up())
    }

    @Test
    fun eachPressStartsFresh() {
        val g = VolumeGesture()
        g.down(0, recording = false); g.holdElapsed(); g.up()
        assertEquals(OnDown.ArmHold, g.down(0, recording = false))
        assertEquals(OnUp.ReapplyVolume, g.up())
    }
}
