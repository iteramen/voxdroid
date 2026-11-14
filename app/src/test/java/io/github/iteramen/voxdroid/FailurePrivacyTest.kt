package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class FailurePrivacyTest {
    private val failure = IllegalStateException("PRIVATE_TRANSCRIPT OTHER_CREDENTIAL https://private.invalid")
    private val transcriber = object : Transcriber {
        override fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?) =
            Result.failure<Transcription>(failure)
    }

    @Test
    fun unknownTranscriberFailureCannotBecomeALogOrPillText() {
        assertEquals(
            SttOutcome.Failure("stt transport", "stt transport: transport failure"),
            dictate(transcriber, ByteArray(0), SttLanguage.Explicit("en"), null, 0f),
        )
    }

    @Test
    fun aLaneDoesNotPromoteAnUnknownExceptionMessageToATrustedError() {
        val routed = Routed(transcriber, mapOf("en" to transcriber), emptySet(), true, { true }) {}
        assertEquals(
            SttOutcome.Failure("stt[en] transport", "stt[en] transport: transport failure"),
            dictate(routed, ByteArray(0), SttLanguage.Explicit("en"), null, 0f),
        )
    }

    @Test
    fun unknownCleanupFailureKeepsRawDictationButNotExceptionContent() {
        val polisher = object : Polisher {
            override fun polish(raw: String) = Result.failure<String>(failure)
        }
        assertEquals(
            PolishOutcome.Fallback("original dictation", "polish transport: transport failure"),
            polish(polisher, "original dictation", 0),
        )
    }
}
