package io.github.iteramen.voxdroid

/** Explicit takes go to their lane; auto takes try [default] first, then the detected language's lane. */
class Routed(
    private val default: Transcriber,
    private val lanes: Map<String, Transcriber>,
    private val tokenlessLanes: Set<String>,
    private val sttHasToken: Boolean,
    private val current: () -> Boolean,
    private val note: (String) -> Unit,
) : Transcriber {

    override fun transcribe(
        wav: ByteArray,
        language: SttLanguage,
        prompt: String?,
    ): Result<Transcription> {
        if (!current()) return notCurrent()
        if (language is SttLanguage.Explicit) return explicit(wav, language, prompt)
        val first = default.transcribe(wav, language, prompt).getOrElse { failed ->
            return Result.failure(failed)
        }
        val code = resendTo(first.detected, lanes.keys) ?: return Result.success(first)
        if (!current()) return notCurrent()
        return explicit(wav, SttLanguage.Explicit(code), prompt)
    }

    private fun explicit(wav: ByteArray, language: SttLanguage.Explicit, prompt: String?): Result<Transcription> {
        val code = language.code
        val lane = lanes[code] ?: return default.transcribe(wav, language, prompt)
        val transcription = lane.transcribe(wav, language, prompt).getOrElse { failed ->
            return Result.failure(named(code, failed))
        }
        // A lane can return nothing where the default transcribes (one-word clips), so retry the default.
        if (transcription.text.isNotBlank()) return Result.success(transcription)
        if (!current()) return notCurrent()
        note("stt[$code] lane returned nothing; retrying the default endpoint")
        return default.transcribe(wav, language, prompt)
    }

    /** A lane failure renamed `stt[he] HTTP 502` so a dead lane is loud; a 401 gains [NO_TOKEN_SENT] when the lane was sent no token. */
    private fun named(code: String, failed: Throwable): SttError {
        val detail = failed.message.orEmpty()
        val bar = (failed as? SttError)?.bar ?: detail.substringBefore(':').trim()
        if (!lane401Hint(bar, laneSendsToken = code !in tokenlessLanes, sttHasToken = sttHasToken)) {
            return SttError(lane(code, bar), lane(code, detail))
        }
        return SttError(lane(code, bar + NO_TOKEN_SENT), lane(code, bar + NO_TOKEN_SENT + detail.removePrefix(bar)))
    }

    private fun lane(code: String, line: String): String = when {
        line.startsWith(STAGE) -> "$STAGE[$code]" + line.removePrefix(STAGE)
        line.isBlank() -> "$STAGE[$code]"
        else -> "$STAGE[$code] $line"
    }

    private companion object {
        const val STAGE = "stt"
    }
}

internal const val ROUTE_P = 0.5f

internal fun resendTo(detected: Pair<String, Float>?, laneCodes: Set<String>): String? =
    detected?.takeIf { (code, p) -> p >= ROUTE_P && code in laneCodes }?.first

private const val STT_401 = "stt HTTP 401"

private const val NO_TOKEN_SENT = ": no token sent"

internal fun lane401Hint(bar: String, laneSendsToken: Boolean, sttHasToken: Boolean): Boolean =
    bar == STT_401 && !laneSendsToken && sttHasToken
