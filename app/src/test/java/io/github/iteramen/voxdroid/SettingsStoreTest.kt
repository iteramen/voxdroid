package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun theTwoNamedTokensAreSecrets() {
        assertEquals(StoredAs.Secret, storedAs("stt_token"))
        assertEquals(StoredAs.Secret, storedAs("polish_token"))
    }

    @Test
    fun theThreeCountsAreWholeNumbers() {
        assertEquals(StoredAs.Whole, storedAs("stt_timeout_s"))
        assertEquals(StoredAs.Whole, storedAs("polish_timeout_s"))
        assertEquals(StoredAs.Whole, storedAs("polish_min_words"))
    }

    @Test
    fun theHoldDurationIsAWholeNumber() {
        assertEquals(StoredAs.Whole, storedAs("volume_hold_ms"))
    }

    @Test
    fun theThresholdIsTheOnlyFraction() {
        assertEquals(StoredAs.Fraction, storedAs("no_speech_threshold"))
    }

    @Test
    fun everythingElseIncludingALaneUrlIsText() {
        assertEquals(StoredAs.Text, storedAs("stt_base_url"))
        assertEquals(StoredAs.Text, storedAs("stt_model"))
        assertEquals(StoredAs.Text, storedAs("stt_prompt"))
        assertEquals(StoredAs.Text, storedAs("polish_base_url"))
        assertEquals(StoredAs.Text, storedAs("polish_model"))
        assertEquals(StoredAs.Text, storedAs("chip_languages"))
        assertEquals(StoredAs.Text, storedAs("lane_base_url_he"))
    }

    @Test
    fun aLanesOwnTokenIsASecret() {
        assertEquals(StoredAs.Secret, storedAs("lane_token_he"))
        assertEquals(StoredAs.Secret, storedAs("lane_token_ru"))
        assertEquals(StoredAs.Secret, storedAs("lane_token_zh-CN"))
    }

    @Test
    fun aKeyNearTheLaneTokenPrefixIsNotASecret() {
        assertEquals(StoredAs.Text, storedAs("lane_tokenhe"))
        assertEquals(StoredAs.Text, storedAs("lane_token"))
        assertEquals(StoredAs.Text, storedAs("Lane_token_he"))
        assertEquals(StoredAs.Text, storedAs("xlane_token_he"))
        assertEquals(StoredAs.Text, storedAs("lane_inherit_token_he"))
        assertEquals(StoredAs.Text, storedAs("lane_base_url_token_he"))
    }

    @Test
    fun theLaneSwitchIsStoredInTheOrdinaryFile() {
        assertEquals(StoredAs.Text, storedAs(LANE_INHERIT_PREFIX + "he"))
        assertEquals("lane_token_", LANE_TOKEN_PREFIX)
        assertEquals("lane_inherit_token_", LANE_INHERIT_PREFIX)
    }

    @Test
    fun aWholeNumberBoxTakesAWholeNumberAndNothingElse() {
        assertNull(validateNumber("stt_timeout_s", "30"))
        assertNull(validateNumber("polish_min_words", "0"))
        assertEquals(NumberProblem.NotANumber, validateNumber("stt_timeout_s", "abc"))
        assertEquals(NumberProblem.NotANumber, validateNumber("stt_timeout_s", ""))
        assertEquals(NumberProblem.NotANumber, validateNumber("stt_timeout_s", "1.5"))
        assertEquals(NumberProblem.Negative, validateNumber("stt_timeout_s", "-1"))
    }

    @Test
    fun theHoldDurationHasAFloorOf300Ms() {
        assertEquals(300, MIN_HOLD_MS)
        assertEquals(NumberProblem.BelowMinimum, validateNumber("volume_hold_ms", "299"))
        assertEquals(NumberProblem.BelowMinimum, validateNumber("volume_hold_ms", "0"))
        assertEquals(NumberProblem.BelowMinimum, validateNumber("volume_hold_ms", "-1"))
        assertNull(validateNumber("volume_hold_ms", "300"))
        assertNull(validateNumber("volume_hold_ms", "500"))
    }

    @Test
    fun theBoxShowsTheHoldNoLowerThanTheFloor() {
        assertEquals(300, shownWhole("volume_hold_ms", 100))
        assertEquals(300, shownWhole("volume_hold_ms", -5))
        assertEquals(500, shownWhole("volume_hold_ms", 500))
        assertEquals(0, shownWhole("stt_timeout_s", 0))
    }

    @Test
    fun theFloorIsNotTheOtherWholeNumbersAndDoesNotHideAMalformedEntry() {
        assertEquals(NumberProblem.NotANumber, validateNumber("volume_hold_ms", "abc"))
        assertEquals(NumberProblem.NotANumber, validateNumber("volume_hold_ms", ""))
        assertEquals(NumberProblem.NotANumber, validateNumber("volume_hold_ms", "299.5"))
        assertNull(validateNumber("volume_hold_ms", " 300 "))
        assertNull(validateNumber("stt_timeout_s", "0"))
        assertNull(validateNumber("polish_min_words", "0"))
        assertNull(validateNumber("polish_timeout_s", "299"))
    }

    @Test
    fun theThresholdBoxTakesZeroToOne() {
        assertNull(validateNumber("no_speech_threshold", "0.3"))
        assertNull(validateNumber("no_speech_threshold", "0"))
        assertNull(validateNumber("no_speech_threshold", "1"))
        assertEquals(NumberProblem.NotANumber, validateNumber("no_speech_threshold", "abc"))
        assertEquals(NumberProblem.Negative, validateNumber("no_speech_threshold", "-0.1"))
        assertEquals(NumberProblem.NotAFraction, validateNumber("no_speech_threshold", "1.5"))
    }

    @Test
    fun aTextKeyRefusesNothing() {
        assertNull(validateNumber("stt_base_url", "not a number"))
        assertNull(validateNumber("stt_token", "not a number"))
    }

    @Test
    fun anAcceptedEntryParsesToWhatIsStored() {
        assertEquals(30, wholeOrNull(" 30 ")!!)
        assertNull(wholeOrNull("abc"))
        assertEquals(0.3f, fractionOrNull("0.3")!!, 0f)
        assertNull(fractionOrNull("abc"))
        assertNull(fractionOrNull("NaN"))
    }

    @Test
    fun theStoredValueFormatsBackToTheSameText() {
        assertEquals("0.3", fractionOrNull("0.3").toString())
        assertEquals("30", wholeOrNull("30").toString())
    }

    @Test
    fun aTypedValueLosesTheKeyboardsOuterWhitespace() {
        assertEquals("dictate", typed("dictate "))
        assertEquals("dictate", typed(" dictate"))
        assertEquals("dictate", typed("\tdictate\n"))
        assertEquals("", typed(null))
        assertEquals("", typed("  "))
        assertEquals("Grafana\nRedis", typed("Grafana\nRedis\n"))
    }
}
