package io.github.iteramen.voxdroid

data class Transcription(
    val text: String,
    /** Duration-weighted segment mean (equal weights if timings are unusable); null when unscored. */
    val noSpeechProb: Float?,
    val detected: Pair<String, Float>? = null,
)

/** Called off the main thread; failures come back as a [Result], from [SttClient] an [SttError]. */
interface Transcriber {
    fun transcribe(wav: ByteArray, language: SttLanguage, prompt: String?): Result<Transcription>
}

sealed interface SttOutcome {
    data class Text(val text: String) : SttOutcome

    data object Silence : SttOutcome

    data class Failure(val bar: String, val detail: String) : SttOutcome
}

/** The no-speech score decides, never the words; a threshold of 0 or an unscored reply gates nothing. */
fun dictate(
    transcriber: Transcriber,
    wav: ByteArray,
    language: SttLanguage,
    prompt: String?,
    noSpeechThreshold: Float,
): SttOutcome {
    val transcription = transcriber.transcribe(wav, language, prompt).getOrElse { failed ->
        val error = failed as? SttError
        val detail = error?.message ?: "stt transport: ${causeDetail(failed)}"
        val bar = error?.bar ?: "stt transport"
        return SttOutcome.Failure(bar, detail)
    }
    val prob = transcription.noSpeechProb
    return if (prob != null && noSpeechThreshold > 0f && prob > noSpeechThreshold) {
        SttOutcome.Silence
    } else {
        SttOutcome.Text(transcription.text)
    }
}
