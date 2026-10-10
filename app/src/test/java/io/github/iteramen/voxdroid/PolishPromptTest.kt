package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reads the prompt off disk: `android.jar`'s `Resources` is a stub under unit tests. */
class PolishPromptTest {

    private val prompt = promptFile().readText(Charsets.UTF_8)

    @Test
    fun theFileCarriesTheCapitaliseRuleExactlyOnce() {
        assertEquals(prompt, 1, occurrences(prompt, CAPITALISE_FIRST_RULE))
        assertFalse(prompt, prompt.contains(LOWERCASE_FIRST_RULE))
    }

    @Test
    fun theDefaultBaseIsTheFileVerbatim() {
        assertEquals(prompt, builtInPrompt(prompt, capitalizeFirstWord = true))
    }

    @Test
    fun theFlagOffSwapsRuleOneAndChangesNothingElse() {
        val swapped = builtInPrompt(prompt, capitalizeFirstWord = false)

        assertEquals(swapped, 1, occurrences(swapped, LOWERCASE_FIRST_RULE))
        assertFalse(swapped, swapped.contains(CAPITALISE_FIRST_RULE))
        // Cut the one rule out of each and what is left has to be identical, character for
        // character: the swap is a replacement of rule 1 and nothing more.
        assertEquals(
            prompt.replace(CAPITALISE_FIRST_RULE, ""),
            swapped.replace(LOWERCASE_FIRST_RULE, ""),
        )
    }

    @Test
    fun aGlossaryAppendsTheRuleOnceWithItsText() {
        val composed = systemPrompt(prompt, "Grafana, Redis, Tailscale")

        assertEquals("$prompt\n\n$GLOSSARY_RULE\nGrafana, Redis, Tailscale", composed)
        assertEquals(composed, 1, occurrences(composed, GLOSSARY_RULE))
        assertTrue(composed, composed.startsWith(prompt))
    }

    @Test
    fun theRuleIsAppendedToTheSwappedBaseToo() {
        val swapped = builtInPrompt(prompt, capitalizeFirstWord = false)

        val composed = systemPrompt(swapped, "  Grafana  ")

        assertEquals("$swapped\n\n$GLOSSARY_RULE\nGrafana", composed)
    }

    @Test
    fun aBlankGlossaryAppendsNothing() {
        assertEquals(prompt, systemPrompt(prompt, null))
        assertEquals(prompt, systemPrompt(prompt, ""))
        assertEquals(prompt, systemPrompt(prompt, "   \n  "))
    }

    private fun occurrences(text: String, part: String) = text.split(part).size - 1

    private companion object {

        fun promptFile(): File = sequenceOf(
            "src/main/res/raw/polish_prompt.txt",
            "app/src/main/res/raw/polish_prompt.txt",
        ).map(::File).first(File::exists)
    }
}
