package io.github.iteramen.voxdroid

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a configured server, or anyone on the path of a plain-http one, can send back to bring the service down. */
class HostileReplyTest {

    /** Android's org.json has no nesting limit; the test classpath's has one, so the overflow here is made by hand. */
    @Test
    fun aParseThatOverflowsTheStackIsMalformedJson() {
        fun endless(depth: Int): Int = 1 + endless(depth + 1)
        assertThrows(JSONException::class.java) { parseJson("[[[[") { endless(0) } }
    }

    @Test
    fun anOrdinaryReplyParsesAsItAlwaysDid() {
        assertEquals("hi [[[", (parseJson("""{"text":"hi [[["}""") as JSONObject).getString("text"))
        assertThrows(JSONException::class.java) { parseJson("{") }
    }

    @Test
    fun aReplyLongerThanAnyTakeIsCutBeforeItIsTypedOrCopied() {
        val typed = finish("word ".repeat(1_000_000), stripTerminalPeriod = true).text
        assertEquals(MAX_TYPED_CHARS - 1, typed.length)
        assertTrue(typed.endsWith("word"))
        assertEquals("a five-minute take is nowhere near the limit", 40_000 - 1, finish("word ".repeat(8_000), true).text.length)
    }

    @Test
    fun aPeriodTheCutLandsOnIsNotTheTerminalOne() {
        val typed = finish("a".repeat(MAX_TYPED_CHARS - 1) + ". and more.", stripTerminalPeriod = true).text
        assertEquals(MAX_TYPED_CHARS, typed.length)
        assertTrue(typed.endsWith("a."))
    }

    /** The body limit is the only bound on what a shallow reply costs to parse: millions of empty objects fit in ten megabytes. */
    @Test
    fun theBodyLimitKeepsAShallowReplySmallEnoughToParse() {
        assertTrue(BODY_LIMIT_BYTES <= 1024 * 1024)
    }

    @Test
    fun theCutNeverLeavesHalfASurrogatePair() {
        val typed = finish("a".repeat(MAX_TYPED_CHARS - 1) + "🎤🎤", stripTerminalPeriod = false).text
        assertEquals(MAX_TYPED_CHARS - 1, typed.length)
        assertFalse(typed.last().isSurrogate())
    }
}
