package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class DictateTest {

    @Test
    fun aScoreAboveTheThresholdDropsTheTake() {
        val outcome = dictate(fake(Transcription("Thank you for watching!", 0.5f)), WAV, LANGUAGE, null, 0.3f)

        assertEquals(SttOutcome.Silence, outcome)
    }

    @Test
    fun aScoreBelowTheThresholdIsCommitted() {
        val outcome = dictate(fake(Transcription("hello world", 0.2f)), WAV, LANGUAGE, null, 0.3f)

        assertEquals(SttOutcome.Text("hello world"), outcome)
    }

    @Test
    fun aScoreAtTheThresholdIsCommitted() {
        val outcome = dictate(fake(Transcription("hello", 0.3f)), WAV, LANGUAGE, null, 0.3f)

        assertEquals(SttOutcome.Text("hello"), outcome)
    }

    @Test
    fun aThresholdOfZeroNeverDrops() {
        val outcome = dictate(fake(Transcription("hello", 0.99f)), WAV, LANGUAGE, null, 0f)

        assertEquals(SttOutcome.Text("hello"), outcome)
    }

    @Test
    fun anUnscoredReplyIsNeverDropped() {
        val outcome = dictate(fake(Transcription("hello", null)), WAV, LANGUAGE, null, 0.3f)

        assertEquals(SttOutcome.Text("hello"), outcome)
    }

    @Test
    fun theBarGetsTheHeadOfAFailureAndLogcatTheWholeLine() {
        val failing = object : Transcriber {
            override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?) =
                Result.failure<Transcription>(SttError("stt HTTP 502", "stt HTTP 502: <html>gateway</html>"))
        }

        val outcome = dictate(failing, WAV, LANGUAGE, null, 0.3f)

        assertEquals(SttOutcome.Failure("stt HTTP 502", "stt HTTP 502: <html>gateway</html>"), outcome)
    }

    @Test
    fun theRequestCarriesTheLanguageAndThePrompt() {
        var seen: Pair<SttLanguage, String?>? = null
        val recording = object : Transcriber {
            override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?): Result<Transcription> {
                seen = language to prompt
                return Result.success(Transcription("x", null))
            }
        }

        dictate(recording, WAV, SttLanguage.Explicit("he"), "Glossary: Acme", 0.3f)

        assertEquals(SttLanguage.Explicit("he") to "Glossary: Acme", seen)
    }

    private fun fake(transcription: Transcription) = object : Transcriber {
        override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?) =
            Result.success(transcription)
    }

    private companion object {
        val WAV = ByteArray(0)
        val LANGUAGE = SttLanguage.Auto(listOf("en", "ru"))
    }
}
