package io.github.iteramen.voxdroid

import android.content.res.Resources
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal const val CAPITALISE_FIRST_RULE = """1. Add punctuation and capitalisation where the speech pauses or clauses end."""

/** Rule 1 when the first word stays lower-case: a prompt rule because only the model can tell a name, acronym or "I" from an ordinary word. */
internal const val LOWERCASE_FIRST_RULE = """1. Add punctuation where the speech pauses or clauses end, and capitalisation inside the sentence. The English pronoun "I" is always written I, alone or in "I'm", "I've", "I'll" — never i, in any position, including the very first word. Every other first word is lower-cased, even where the transcript capitalises it: a dictation usually lands mid-sentence, so "They can wait until Monday" comes back as "they can wait until Monday". A proper noun, a personal name and an acronym keep their capital: "Anna is on her way", "IEEE approved the draft", "Москва подождёт"."""

internal const val GLOSSARY_RULE = """10. Technical terms from the glossary below (tools, products, companies, UI controls) are written in Latin exactly as listed, even when the transcript spelled them in Hebrew or Cyrillic letters: קומבובוקס → combobox, טייל סקייל → Tailscale. People's names from the glossary are the opposite and stay in the sentence's own script: Noa → נועה in Hebrew, Olga → Ольга in Russian; never Latin. A common word that only sounds like a name is not a name: אריה in a Hebrew sentence is a lion and stays אריה."""

internal fun readBuiltInPrompt(resources: Resources): String =
    resources.openRawResource(R.raw.polish_prompt).use { it.readBytes().toString(Charsets.UTF_8) }

internal fun builtInPrompt(prompt: String, capitalizeFirstWord: Boolean): String =
    if (capitalizeFirstWord) prompt else prompt.replaceFirst(CAPITALISE_FIRST_RULE, LOWERCASE_FIRST_RULE)

internal fun systemPrompt(base: String, glossary: String?): String {
    val terms = glossary?.trim().orEmpty()
    return if (terms.isEmpty()) base else "${base.trimEnd()}\n\n$GLOSSARY_RULE\n$terms"
}

internal fun wordCount(text: String): Int = text.split(WHITESPACE).count { it.isNotEmpty() }

/** [message] is the whole line for logcat and never carries the token; [blocked] marks a refused insecure endpoint. */
class PolishError(message: String, val blocked: Boolean = false) : Exception(message)

interface Polisher {
    fun polish(raw: String): Result<String>
}

sealed interface PolishOutcome {
    data class Text(val text: String) : PolishOutcome

    data class Fallback(val raw: String, val detail: String, val blocked: Boolean = false) : PolishOutcome
}

/** Restores the capital the model took off a first-word "i", "i'm", "i've", "i'll" or "i'd"; nothing else is touched. */
internal fun restoreFirstI(text: String): String {
    val rest = text.trimStart()
    val lead = text.substring(0, text.length - rest.length)
    val word = rest.split(Regex("\\s")).first()
    val core = word.trimEnd(',', '.', ';', ':', '!', '?').replace('\u2019', '\'')
    return if (core in FIRST_PERSON) lead + "I" + rest.substring(1) else text
}

private val FIRST_PERSON = setOf("i", "i'm", "i've", "i'll", "i'd")

/** Fewer than [minWords] words skips the stage; any failure falls back to the raw transcript. */
fun polish(polisher: Polisher, raw: String, minWords: Int, lowercaseRule: Boolean = false): PolishOutcome {
    if (wordCount(raw) < minWords) return PolishOutcome.Text(raw)
    return polisher.polish(raw).fold(
        onSuccess = { PolishOutcome.Text(if (lowercaseRule) restoreFirstI(it) else it) },
        onFailure = { PolishOutcome.Fallback(raw, it.message.orEmpty(), blocked = (it as? PolishError)?.blocked == true) },
    )
}

class PolishClient(
    baseUrl: String,
    private val allowInsecureHttp: Boolean,
    private val model: String,
    private val token: String,
    private val timeoutMs: Int,
    private val systemPrompt: String,
) : Polisher {

    private val endpoint = baseUrl.trim().trimEnd('/')

    override fun polish(raw: String): Result<String> {
        if (endpoint.isEmpty()) return fail(NO_ENDPOINT)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", "<transcription>$raw</transcription>"))
        val request = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", TEMPERATURE)
            .put("max_tokens", MAX_TOKENS)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val attempt = boundedPost(endpoint + PATH, allowInsecureHttp, CONTENT_TYPE, token, request, timeoutMs, WATCHDOG_THREAD)
        val answered = when (attempt) {
            is Attempt.Answered -> attempt
            is Attempt.Unreadable ->
                return fail("polish HTTP ${attempt.status} body unreadable", attempt.cause)
            is Attempt.SendFailed -> return fail("polish transport", attempt.cause)
            Attempt.Blocked -> return Result.failure(PolishError(INSECURE_BLOCKED, blocked = true))
        }
        if (answered.status !in HTTP_OK_FIRST..HTTP_OK_LAST) {
            return fail("polish HTTP ${answered.status}", bodyPrefix(answered.body, token))
        }
        val fields = try {
            JSONTokener(answered.body).nextValue() as? JSONObject
                ?: return fail("polish response is not an object", bodyPrefix(answered.body, token))
        } catch (malformed: JSONException) {
            return fail("polish JSON", bodyPrefix(answered.body, token))
        }
        val choice = (fields.opt("choices") as? JSONArray)?.opt(0) as? JSONObject
            ?: return fail(NO_CONTENT, bodyPrefix(answered.body, token))
        val content = (choice.opt("message") as? JSONObject)?.opt("content") as? String
            ?: return fail(NO_CONTENT, bodyPrefix(answered.body, token))
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return fail("polish returned empty content")
        return Result.success(trimmed)
    }

    private fun <T> fail(head: String, detail: String? = null): Result<T> =
        Result.failure(PolishError(if (detail.isNullOrEmpty()) head else "$head: $detail"))

    private companion object {
        const val PATH = "/chat/completions"
        const val CONTENT_TYPE = "application/json"
        const val NO_ENDPOINT = "polish: no endpoint configured"
        const val INSECURE_BLOCKED = "polish: insecure endpoint blocked"
        const val NO_CONTENT = "polish response has no content field"
        const val TEMPERATURE = 0.3
        const val MAX_TOKENS = 1024
        const val WATCHDOG_THREAD = "voxdroid-polish-watchdog"
    }
}

// Unicode White_Space: Java's `\s` is ASCII-only and misses e.g. U+00A0 NBSP.
private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")
