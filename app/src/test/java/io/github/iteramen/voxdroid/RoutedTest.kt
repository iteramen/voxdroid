package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutedTest {

    private val wav = "RIFFxxxx".toByteArray()

    private val calls = mutableListOf<Pair<String, SttLanguage>>()
    private val notes = mutableListOf<String>()

    private var current = true

    @Test
    fun anExplicitCodeWithALaneTakesTheLaneAndNotTheDefault() {
        val routed = routed(lanes = mapOf("he" to text("from the lane")))

        val out = routed.transcribe(wav, HEBREW, "Glossary: Acme")

        assertEquals("from the lane", out.getOrThrow().text)
        assertEquals(listOf("lane[he]" to HEBREW), calls)
    }

    @Test
    fun anExplicitCodeWithNoLaneTakesTheDefault() {
        val routed = routed(lanes = mapOf("he" to text("from the lane")))

        val out = routed.transcribe(wav, SttLanguage.Explicit("ru"), null)

        assertEquals("from the default", out.getOrThrow().text)
        assertEquals(listOf("default" to SttLanguage.Explicit("ru")), calls)
    }

    @Test
    fun aLaneThatComesBackEmptyIsRetriedOnTheDefault() {
        val routed = routed(lanes = mapOf("he" to text("   ")))

        val out = routed.transcribe(wav, HEBREW, null)

        assertEquals("from the default", out.getOrThrow().text)
        assertEquals(listOf("lane[he]" to HEBREW, "default" to HEBREW), calls)
        assertEquals(listOf("stt[he] lane returned nothing; retrying the default endpoint"), notes)
    }

    @Test
    fun aLaneErrorIsNamedForItsLaneAndTheDefaultIsNeverCalled() {
        val gateway = SttError("stt HTTP 502", "stt HTTP 502: <html>gateway</html>")
        val routed = routed(lanes = mapOf("he" to broken(gateway)))

        val failed = routed.transcribe(wav, HEBREW, null).exceptionOrNull() as SttError

        assertEquals("stt[he] HTTP 502", failed.bar)
        assertEquals("stt[he] HTTP 502: <html>gateway</html>", failed.message)
        assertEquals(listOf("lane[he]" to HEBREW), calls)
        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun aLaneFailureWithNoStageStillCarriesTheLaneName() {
        val routed = routed(lanes = mapOf("he" to broken(IllegalStateException("boom"))))

        val failed = routed.transcribe(wav, HEBREW, null).exceptionOrNull() as SttError

        assertEquals("stt[he] boom", failed.bar)
        assertEquals(listOf("lane[he]" to HEBREW), calls)
    }

    @Test
    fun anAutoTakeJustBelowTheCutStaysOnTheDefault() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to text("from the lane")),
            default = detecting("from the default", "he" to 0.49f),
        )

        assertEquals("from the default", routed.transcribe(wav, auto, null).getOrThrow().text)
        assertEquals(listOf("default" to auto), calls)
    }

    @Test
    fun anAutoTakeDetectedAsALanguageWithNoLaneStaysOnTheDefault() {
        val auto = SttLanguage.Auto(listOf("he", "ru"))
        val routed = routed(
            lanes = mapOf("he" to text("from the lane")),
            default = detecting("from the default", "ru" to 0.9f),
        )

        assertEquals("from the default", routed.transcribe(wav, auto, null).getOrThrow().text)
        assertEquals(listOf("default" to auto), calls)
    }

    @Test
    fun anAutoTakeWithNoDetectionStaysOnTheDefault() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to text("from the lane")),
            default = detecting("from the default", null),
        )

        assertEquals("from the default", routed.transcribe(wav, auto, null).getOrThrow().text)
        assertEquals(listOf("default" to auto), calls)
    }

    @Test
    fun anAutoTakeWhoseDefaultFailsReturnsThatErrorAndCallsNoLane() {
        val gateway = SttError("stt HTTP 502", "stt HTTP 502: <html>gateway</html>")
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(lanes = mapOf("he" to text("from the lane")), default = broken(gateway, "default"))

        val failed = routed.transcribe(wav, auto, null).exceptionOrNull() as SttError

        assertEquals("stt HTTP 502", failed.bar)
        assertEquals("stt HTTP 502: <html>gateway</html>", failed.message)
        assertEquals(listOf("default" to auto), calls)
    }

    @Test
    fun aLaneErrorOnAResentAutoTakeIsNamedForItsLane() {
        val gateway = SttError("stt HTTP 502", "stt HTTP 502: <html>gateway</html>")
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to broken(gateway)),
            default = detecting("from the default", "he" to 0.9f),
        )

        val failed = routed.transcribe(wav, auto, null).exceptionOrNull() as SttError

        assertEquals("stt[he] HTTP 502", failed.bar)
        assertEquals(listOf("default" to auto, "lane[he]" to HEBREW), calls)
    }

    @Test
    fun aResentAutoTakeWhoseLaneIsEmptyIsAskedOnTheDefaultAgainAsExplicit() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to text("   ")),
            default = detecting("from the default", "he" to 0.9f),
        )

        val out = routed.transcribe(wav, auto, null)

        assertEquals("from the default", out.getOrThrow().text)
        assertEquals(listOf("default" to auto, "lane[he]" to HEBREW, "default" to HEBREW), calls)
        assertEquals(listOf("stt[he] lane returned nothing; retrying the default endpoint"), notes)
    }

    @Test
    fun theCutIsInclusiveAndTheDetectedCodeMustHaveALane() {
        val lanes = setOf("he")
        assertEquals("he", resendTo("he" to 0.5f, lanes))
        assertEquals(null, resendTo("he" to 0.4999f, lanes))
        assertEquals(null, resendTo("ru" to 0.9f, lanes))
        assertEquals(null, resendTo(null, lanes))
        assertEquals(null, resendTo("HE" to 0.9f, lanes))
        assertEquals(null, resendTo("he" to 0.9f, emptySet()))
    }

    @Test
    fun anAutoTakeDetectedAtTheCutGoesToItsLane() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to text("from the lane")),
            default = detecting("from the default", "he" to 0.5f),
        )

        val out = routed.transcribe(wav, auto, null)

        assertEquals("from the lane", out.getOrThrow().text)
        assertEquals(listOf("default" to auto, "lane[he]" to HEBREW), calls)
    }

    @Test
    fun theHintIsForALanesOwn401WhenItWasSentNoTokenAndTheSttHasOne() {
        assertEquals(true, lane401Hint("stt HTTP 401", laneSendsToken = false, sttHasToken = true))
        assertEquals("not a 401", false, lane401Hint("stt HTTP 403", laneSendsToken = false, sttHasToken = true))
        assertEquals("a token was sent", false, lane401Hint("stt HTTP 401", laneSendsToken = true, sttHasToken = true))
        assertEquals("nothing to have sent", false, lane401Hint("stt HTTP 401", laneSendsToken = false, sttHasToken = false))
        assertEquals("a longer bar", false, lane401Hint("stt HTTP 4010", laneSendsToken = false, sttHasToken = true))
        assertEquals("another stage", false, lane401Hint("polish HTTP 401", laneSendsToken = false, sttHasToken = true))
        assertEquals("a transport bar", false, lane401Hint("stt transport", laneSendsToken = false, sttHasToken = true))
    }

    @Test
    fun aTokenlessLanesUnauthorizedReplyNamesTheMissingToken() {
        val unauthorized = SttError("stt HTTP 401", "stt HTTP 401")
        val routed = routed(lanes = mapOf("he" to broken(unauthorized)), tokenless = setOf("he"), sttHasToken = true)

        val failed = routed.transcribe(wav, HEBREW, null).exceptionOrNull() as SttError

        assertEquals("stt[he] HTTP 401: no token sent", failed.bar)
        assertEquals("stt[he] HTTP 401: no token sent", failed.message)
    }

    @Test
    fun theHintSitsOnTheHeadOfTheLogLineToo() {
        val unauthorized = SttError("stt HTTP 401", "stt HTTP 401: {\"error\":\"bad key\"}")
        val routed = routed(lanes = mapOf("he" to broken(unauthorized)), tokenless = setOf("he"), sttHasToken = true)

        val failed = routed.transcribe(wav, HEBREW, null).exceptionOrNull() as SttError

        assertEquals("stt[he] HTTP 401: no token sent", failed.bar)
        assertEquals("stt[he] HTTP 401: no token sent: {\"error\":\"bad key\"}", failed.message)
    }

    @Test
    fun aLaneThatSendsATokenReadsAsTheServerAnsweredIt() {
        val unauthorized = SttError("stt HTTP 401", "stt HTTP 401")
        val routed = routed(lanes = mapOf("he" to broken(unauthorized)), tokenless = emptySet(), sttHasToken = true)

        val failed = routed.transcribe(wav, HEBREW, null).exceptionOrNull() as SttError

        assertEquals("stt[he] HTTP 401", failed.bar)
        assertEquals("stt[he] HTTP 401", failed.message)
    }

    @Test
    fun theHintIsPerLane() {
        val unauthorized = SttError("stt HTTP 401", "stt HTTP 401")
        val routed = routed(
            lanes = mapOf("he" to broken(unauthorized), "ru" to broken(unauthorized)),
            tokenless = setOf("he"),
            sttHasToken = true,
        )

        val ru = routed.transcribe(wav, SttLanguage.Explicit("ru"), null).exceptionOrNull() as SttError

        assertEquals("stt[ru] HTTP 401", ru.bar)
    }

    @Test
    fun aTakeNoLongerCurrentBeforeItsFirstRequestSendsNothing() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(lanes = mapOf("he" to text("from the lane")))
        current = false

        routed.transcribe(wav, auto, null)

        assertEquals(emptyList<Pair<String, SttLanguage>>(), calls)
    }

    @Test
    fun aTakeCancelledDuringItsDefaultCallIsNotSentToTheLane() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to text("from the lane")),
            default = detecting("from the default", "he" to 0.9f) { current = false },
        )

        routed.transcribe(wav, auto, null)

        assertEquals(listOf("default" to auto), calls)
    }

    @Test
    fun aTakeStillCurrentAfterItsDefaultCallIsSentToTheLane() {
        val auto = SttLanguage.Auto(listOf("he", "en"))
        val routed = routed(
            lanes = mapOf("he" to text("from the lane")),
            default = detecting("from the default", "he" to 0.9f),
        )

        assertEquals("from the lane", routed.transcribe(wav, auto, null).getOrThrow().text)
        assertEquals(listOf("default" to auto, "lane[he]" to HEBREW), calls)
    }

    @Test
    fun aTakeCancelledDuringAnEmptyLaneCallIsNotRetriedOnTheDefault() {
        val routed = routed(lanes = mapOf("he" to fake(null) { current = false; Result.success(Transcription("", null)) }))

        routed.transcribe(wav, HEBREW, null)

        assertEquals(listOf("lane[he]" to HEBREW), calls)
        assertEquals(emptyList<String>(), notes)
    }

    private fun routed(
        lanes: Map<String, Transcriber>,
        tokenless: Set<String> = emptySet(),
        sttHasToken: Boolean = false,
        default: Transcriber = text("from the default", "default"),
    ) = Routed(default, lanes, tokenless, sttHasToken, { current }) { notes += it }

    private fun detecting(text: String, detected: Pair<String, Float>?, during: () -> Unit = {}) =
        fake("default") { during(); Result.success(Transcription(text, null, detected)) }

    private fun text(text: String, name: String? = null) = fake(name) { Result.success(Transcription(text, null)) }

    private fun broken(failure: Throwable, name: String? = null) = fake(name) { Result.failure(failure) }

    private fun fake(name: String?, answer: () -> Result<Transcription>) = object : Transcriber {
        override fun transcribe(
            wav: ByteArray,
            language: SttLanguage,
            prompt: String?,
        ): Result<Transcription> {
            val code = (language as? SttLanguage.Explicit)?.code
            calls += (name ?: "lane[$code]") to language
            return answer()
        }
    }

    private companion object {
        val HEBREW = SttLanguage.Explicit("he")
    }
}
