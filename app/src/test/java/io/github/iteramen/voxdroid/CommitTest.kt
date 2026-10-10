package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitTest {

    @Test
    fun aLonePeriodLeavesNothingToCommit() {
        assertEquals("", stripped("."))
    }

    @Test
    fun theSpaceAPoppedPeriodUncoversGoesWithIt() {
        assertEquals("Hello", stripped("Hello ."))
    }

    @Test
    fun onlyTheTerminalPeriodGoes() {
        assertEquals("Okay. See you", stripped("Okay. See you."))
    }

    @Test
    fun aDecimalPointIsNotATerminalPeriod() {
        assertEquals("3.14", stripped("3.14"))
    }

    @Test
    fun anEllipsisSurvivesWhole() {
        assertEquals("Wait...", stripped("Wait..."))
    }

    @Test
    fun aQuestionOrExclamationMarkWasAskedForByTone() {
        assertEquals("Really?", stripped("Really?"))
        assertEquals("Go!", stripped("Go!"))
    }

    @Test
    fun aHebrewSentenceLosesItsTerminalPeriodToo() {
        assertEquals("עדיין", stripped("עדיין."))
    }

    @Test
    fun theFlagOffKeepsThePeriodAndLeavesAnEllipsisWhole() {
        assertEquals("Hello.", finish("Hello.", stripTerminalPeriod = false).text)
        assertEquals("Wait...", finish("Wait...", stripTerminalPeriod = false).text)
    }

    @Test
    fun aNewlineBecomesASpaceAndIsNotCounted() {
        val finished = finish("a\nb", stripTerminalPeriod = true)

        assertEquals("a b", finished.text)
        assertEquals(0, finished.dropped)
    }

    @Test
    fun aTabIsDroppedAndCounted() {
        val finished = finish("a\tb", stripTerminalPeriod = true)

        assertEquals("ab", finished.text)
        assertEquals(1, finished.dropped)
    }

    @Test
    fun aBidiOverrideIsDroppedAndCounted() {
        val finished = finish("a\u202Eb", stripTerminalPeriod = true)

        assertEquals("ab", finished.text)
        assertEquals(1, finished.dropped)
    }

    @Test
    fun aRightToLeftMarkInsideTheTextIsContentAndStays() {
        val finished = finish("שלום \u200Fעולם", stripTerminalPeriod = true)

        assertEquals("שלום \u200Fעולם", finished.text)
        assertEquals(0, finished.dropped)
    }

    @Test
    fun aReplyOfNothingButOverridesLeavesNothingToCommit() {
        val finished = finish("\u202E\u202E\u202E", stripTerminalPeriod = true)

        assertEquals("", finished.text)
        assertEquals(3, finished.dropped)
    }

    @Test
    fun anAnswerEndingInANewlineCommitsWithNoStraySpace() {
        val finished = finish("Hello there\n", stripTerminalPeriod = true)

        assertEquals("Hello there", finished.text)
        assertEquals(0, finished.dropped)
    }

    @Test
    fun theDroppedCountIsLoggedOnlyWhenSomethingWasDropped() {
        assertEquals("commit: removed 3 control or format characters", droppedNote(3))
        assertEquals("commit: removed 1 control or format characters", droppedNote(1))
        assertNull(droppedNote(0))
    }

    private val formatRanges = listOf(
        0x00AD..0x00AD, 0x0600..0x0605, 0x061C..0x061C, 0x06DD..0x06DD, 0x070F..0x070F,
        0x0890..0x0891, 0x08E2..0x08E2, 0x180E..0x180E, 0x200B..0x200F, 0x202A..0x202E,
        0x2060..0x2064, 0x2066..0x206F, 0xFEFF..0xFEFF, 0xFFF9..0xFFFB, 0x110BD..0x110BD,
        0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A,
        0xE0001..0xE0001, 0xE0020..0xE007F,
    )

    private fun around(cp: Int) = "a" + String(Character.toChars(cp)) + "b"

    @Test
    fun theFormatTablesEdgesAreDroppedAndCounted() {
        for (r in formatRanges) {
            for (cp in listOf(r.first, r.last)) {
                if (cp in 0x200C..0x200F) continue
                val out = sanitize(around(cp))
                assertEquals("U+%04X kept".format(cp), "ab", out.text)
                assertEquals("U+%04X not counted".format(cp), 1, out.dropped)
            }
        }
    }

    @Test
    fun theCodePointsBesideTheTablesEdgesStay() {
        for (cp in listOf(
            0x00AC, 0x00AE, 0x0606, 0x061B, 0x061D, 0x06DC, 0x06DE, 0x070E, 0x0710, 0x088F,
            0x0892, 0x08E1, 0x08E3, 0x180D, 0x180F, 0x200A, 0x2010, 0x202F, 0x205F, 0x2065,
            0x2070, 0xFEFE, 0xFF00, 0xFFF8, 0xFFFC, 0x110BC, 0x110BE, 0x110CC, 0x110CE,
            0x1342F, 0x13440, 0x1BC9F, 0x1BCA4, 0x1D172, 0x1D17B, 0xE0000, 0xE0002, 0xE001F,
            0xE0080,
        )) {
            val out = sanitize(around(cp))
            assertEquals("U+%04X dropped".format(cp), around(cp), out.text)
            assertEquals("U+%04X counted".format(cp), 0, out.dropped)
        }
    }

    @Test
    fun exactlyTheTablesCodePointsAreRemoved() {
        assertEquals(170, formatRanges.sumOf { it.last - it.first + 1 })
        var removed = 0
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF /* lone surrogates: own test */ || Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) continue
            if (sanitize(around(cp)).dropped == 1) removed++
        }
        assertEquals(166, removed)
    }

    @Test
    fun everyLoneSurrogateIsDroppedAndCounted() {
        for (cp in 0xD800..0xDFFF) {
            val out = sanitize("a" + cp.toChar() + "b")
            assertEquals("U+%04X kept".format(cp), "ab", out.text)
            assertEquals("U+%04X not counted".format(cp), 1, out.dropped)
        }
        assertEquals(Sanitized("ab", 1), sanitize("a\uD800b"))
        assertEquals(Sanitized("ab", 1), sanitize("a\uDC00b"))
        assertEquals(Sanitized("a", 1), sanitize("a\uD800"))
        assertEquals(Sanitized("ab", 2), sanitize("a\uDC00\uD800b"))
    }

    @Test
    fun loneHalvesAroundADroppedCharacterNeverJoinIntoATag() {
        assertEquals(Sanitized("ab", 3), sanitize("a\uDB40\t\uDC01b"))
        // AOSP org.json turns each \uXXXX escape into a raw char with no pairing check, so this
        // is what JSON "x\udb40​\udc41y" delivers.
        val finished = finish("x\uDB40​\uDC41y", stripTerminalPeriod = true)
        assertEquals("xy", finished.text)
        assertEquals(3, finished.dropped)
    }

    @Test
    fun theAuditsFormatCharactersAreDroppedAndCounted() {
        for (cp in listOf(
            0x200B, 0x2060, 0xFEFF, 0x061C, 0x180E, 0x00AD, 0x0600, 0xFFF9, 0x1D173, 0xE0001,
            0xE0067, 0xE007F, 0x202E, 0x2066,
        )) {
            val out = finish(around(cp), stripTerminalPeriod = true)
            assertEquals("U+%04X".format(cp), "ab", out.text)
            assertEquals("U+%04X".format(cp), 1, out.dropped)
        }
    }

    @Test
    fun theMarksAndJoinersSurvive() {
        for (s in listOf(
            "שלום‏ עולם, Acme ‎2026",
            "👩‍💻 ‌x",
        )) {
            val out = finish(s, stripTerminalPeriod = true)
            assertEquals(s, out.text)
            assertEquals(0, out.dropped)
        }
    }

    @Test
    fun lineAndParagraphSeparatorsBecomeASpaceAndAreNotCounted() {
        val out = sanitize("one two three")
        assertEquals("one two three", out.text)
        assertEquals(0, out.dropped)
    }

    @Test
    fun aTagSequenceIsRemovedWholeAndCountedPerCodePoint() {
        val england = "🏴󠁧󠁢󠁥󠁮󠁧󠁿"
        val out = sanitize("x${england}y")
        assertEquals("x🏴y", out.text)
        assertEquals(6, out.dropped)
    }

    @Test
    fun aSurrogatePairOutsideTheTableIsKeptWhole() {
        val out = sanitize("a😀b😀")
        assertEquals("a😀b😀", out.text)
        assertEquals(0, out.dropped)
    }

    private fun stripped(text: String) = finish(text, stripTerminalPeriod = true).text
}
