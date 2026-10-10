package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageTest {

    @Test
    fun anExplicitLanguageSendsTheLanguageFieldAndNoCandidates() {
        val fields = SttLanguage.Explicit("he").formFields()

        assertEquals(listOf("language" to "he"), fields)
    }

    @Test
    fun autoSendsTheCandidateListAndNoLanguageField() {
        val fields = SttLanguage.Auto(listOf("en", "ru")).formFields()

        assertEquals(listOf("language_candidates" to "en,ru"), fields)
    }

    @Test
    fun autoWithAnEmptyCandidateListSendsNeitherField() {
        val fields = SttLanguage.Auto(emptyList()).formFields()

        assertEquals(emptyList<Pair<String, String>>(), fields)
    }

    @Test
    fun aRepeatedCodeIsOneEntry() {
        assertEquals(listOf("he", "ru"), languageCodes("he,he,ru", warn = { }))
    }

    @Test
    fun aCodeThatIsNotIsoSixThreeNineOneIsSkippedWithAWarning() {
        val warnings = mutableListOf<String>()

        val codes = languageCodes("he, XX ,eng,e1,,ru", warnings::add)

        assertEquals(listOf("he", "ru"), codes)
        assertEquals(
            listOf("XX", "eng", "e1"),
            warnings.map { it.substringAfterLast(": ") },
        )
    }

    @Test
    fun aLanguageRowIsTitledWithItsOwnScriptNameAndItsCode() {
        assertEquals("he is titled in Hebrew", "עברית (he)", languageRowTitle("he"))
        assertEquals("en is titled in English", "English (en)", languageRowTitle("en"))
    }

    @Test
    fun anExplicitLanguageWithALaneSaysSoAndNamesTheLanesHost() {
        val summary = languageRowSummary(
            explicit = true,
            laneUrl = "http://whisper.example.ts.net:8770/he/v1",
            sentAs = "Sent as he",
            autoDetect = "Auto-detect",
            notSet = "Not set",
        )

        assertEquals("the switch's state, then the lane's host", "Sent as he · whisper.example.ts.net", summary)
    }

    @Test
    fun aLanguageThatIsNotExplicitAndHasNoLaneSaysBoth() {
        val summary = languageRowSummary(
            explicit = false,
            laneUrl = "",
            sentAs = "Sent as ru",
            autoDetect = "Auto-detect",
            notSet = "Not set",
        )

        assertEquals("auto-detect, then no lane", "Auto-detect · Not set", summary)
    }

    @Test
    fun theDefaultListIsTheDevicesLanguageThenEnglish() {
        assertEquals("he,en", defaultLanguageCodes(Locale.forLanguageTag("he-IL")))
        assertEquals("he,en", defaultLanguageCodes(Locale("iw")))
        assertEquals("ru,en", defaultLanguageCodes(Locale.forLanguageTag("ru")))
    }

    @Test
    fun englishIsNotListedTwice() {
        assertEquals("en", defaultLanguageCodes(Locale.US))
    }

    @Test
    fun aLocaleWithNoValidLanguageDefaultsToEnglish() {
        assertEquals("en", defaultLanguageCodes(Locale.ROOT))
        assertEquals("en", defaultLanguageCodes(Locale.forLanguageTag("fil-PH")))
    }
}
