package io.github.iteramen.voxdroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldWalkTest {

    private val tree = mapOf(
        "root" to listOf("toolbar", "chat"),
        "toolbar" to listOf("title"),
        "chat" to listOf("list", "composer"),
        "composer" to listOf("field", "send"),
    )
    private val children = { node: String -> tree[node].orEmpty() }

    @Test
    fun findsADeepNode() {
        assertEquals("field", firstMatch("root", 100, children) { it == "field" })
    }

    @Test
    fun returnsTheFirstMatchInChildOrder() {
        assertEquals("title", firstMatch("root", 100, children) { it == "title" || it == "field" })
    }

    @Test
    fun noMatchIsNull() {
        assertNull(firstMatch("root", 100, children) { it == "missing" })
    }

    @Test
    fun stopsAtTheCap() {
        // Order: root, toolbar, title, chat, list, composer, field.
        assertEquals("field", firstMatch("root", 7, children) { it == "field" })
        assertNull(firstMatch("root", 6, children) { it == "field" })
    }
}
