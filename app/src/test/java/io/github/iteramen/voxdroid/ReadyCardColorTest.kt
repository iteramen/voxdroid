package io.github.iteramen.voxdroid

import java.awt.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyCardColorTest {

    private val primaries = intArrayOf(0xFF8C8C8C.toInt(), 0xFF6750A4.toInt(), 0xFFB3261E.toInt(), 0xFF006A6A.toInt(), 0xFFFFD000.toInt())

    private fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = luminance(a).let { x -> luminance(b).let { y -> maxOf(x, y) to minOf(x, y) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun hueDegrees(argb: Int): Float =
        Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)[0] * 360f

    private fun hex(argb: Int) = "#%08X".format(argb)

    @Test
    fun theTextIsReadableOnTheCardInLightAndDark() {
        for (primary in primaries) for (light in listOf(true, false)) {
            val roles = readyCardRoles(primary, light)
            val ratio = contrast(roles.onAccentContainer, roles.accentContainer)
            assertTrue("primary ${hex(primary)} light=$light: contrast $ratio of ${hex(roles.onAccentContainer)} on ${hex(roles.accentContainer)} is under 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun theCardIsGreenWhateverThePrimary() {
        for (primary in primaries) for (light in listOf(true, false)) {
            val container = readyCardRoles(primary, light).accentContainer
            val hue = hueDegrees(container)
            assertTrue("primary ${hex(primary)} light=$light: hue $hue of ${hex(container)} is outside 75..170", hue in 75f..170f)
        }
    }

    @Test
    fun theGreenIsHarmonizedWithThePrimary() {
        val purple = 0xFF6750A4.toInt()
        val red = 0xFFB3261E.toInt()
        assertNotEquals(
            "a purple and a red primary give the same container ${hex(readyCardRoles(purple, true).accentContainer)}: the seed is not harmonized with the primary",
            readyCardRoles(purple, true).accentContainer,
            readyCardRoles(red, true).accentContainer,
        )
    }

    @Test
    fun lightAndDarkCardsDiffer() {
        for (primary in primaries) {
            assertNotEquals(
                "primary ${hex(primary)}: light and dark containers are the same",
                readyCardRoles(primary, true).accentContainer,
                readyCardRoles(primary, false).accentContainer,
            )
        }
    }
}
