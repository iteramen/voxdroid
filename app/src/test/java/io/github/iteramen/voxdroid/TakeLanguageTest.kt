package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakeLanguageTest {

    private val candidates = listOf("en", "ru")
    private val hebrewOnly: (String) -> Boolean = { it == "he" }

    @Test
    fun normalizesLanguageTagsToTheTwoLetterCode() {
        assertEquals("he", normalizeTag("he"))
        assertEquals("he", normalizeTag("iw"))
        assertEquals("he", normalizeTag("iw-IL"))
        assertEquals("en", normalizeTag("en-US"))
        assertEquals("ru", normalizeTag("ru_RU"))
        assertEquals("en", normalizeTag("EN"))
        assertEquals("id", normalizeTag("in"))
        assertEquals("id", normalizeTag("in_ID"))
        assertEquals("yi", normalizeTag("ji"))
    }

    @Test
    fun aMissingOrUnusableTagIsNoLanguage() {
        assertNull(normalizeTag(null))
        assertNull(normalizeTag(""))
        assertNull(normalizeTag("  "))
        assertNull(normalizeTag("und"))
        assertNull(normalizeTag("x"))
    }

    @Test
    fun aCheckedKeyboardLanguageIsSentExplicitly() {
        assertEquals(SttLanguage.Explicit("he"), takeLanguage("iw", hebrewOnly, candidates))
    }

    @Test
    fun anUncheckedKeyboardLanguageAutoDetectsBetweenTheCandidates() {
        assertEquals(SttLanguage.Auto(candidates), takeLanguage("en-US", hebrewOnly, candidates))
    }

    private val listed = listOf("he", "ru", "en")

    @Test
    fun anExplicitLanguageWithNoLaneIsNotACandidate() {
        assertEquals(listOf("ru", "en"), candidatesFor(listed, hebrewOnly, emptyList()))
    }

    @Test
    fun anExplicitLanguageWithALaneIsACandidate() {
        assertEquals(listOf("he", "ru", "en"), candidatesFor(listed, hebrewOnly, listOf("he")))
    }

    @Test
    fun aLanguageWhoseSwitchIsOffIsACandidateWithOrWithoutALane() {
        assertEquals(listOf("he", "ru", "en"), candidatesFor(listed, { false }, emptyList()))
    }

    @Test
    fun aLanguageOutOfTheListIsNotACandidate() {
        assertEquals(listOf("ru", "en"), candidatesFor(listOf("ru", "en"), hebrewOnly, listOf("he")))
    }

    @Test
    fun anEmptyListHasNoCandidates() {
        assertEquals(emptyList<String>(), candidatesFor(emptyList(), hebrewOnly, listOf("he")))
    }

    @Test
    fun noKeyboardLanguageAutoDetects() {
        assertEquals(SttLanguage.Auto(candidates), takeLanguage(null, hebrewOnly, candidates))
        assertEquals(SttLanguage.Auto(candidates), takeLanguage("", hebrewOnly, candidates))
    }
}
