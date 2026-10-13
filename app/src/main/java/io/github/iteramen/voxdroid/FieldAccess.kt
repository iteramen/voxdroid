package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager

class FieldAccess(private val service: AccessibilityService) {

    fun focused(): Field? = try {
        lookup()
    } finally {
        // A fetch re-arms the platform's forwarding of text and focus events; this switches it off.
        service.setCacheEnabled(false)
    }

    private fun lookup(): Field? {
        val root = service.getRootInActiveWindow(0) ?: return null
        // Compose answers input focus only after its focused node was read; the walk reads it.
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root.takeIf { service.inputMethod?.currentInputStarted == true }
                ?.let { firstMatch(it, WALK_CAP, ::children) { n -> n.isFocused && n.isEditable } }
            ?: return null
        // Compose sets no input type on its nodes but writes the variation into the EditorInfo (API 33).
        val imeType = service.inputMethod?.currentInputEditorInfo?.inputType ?: 0
        val password = node.isPassword || isPasswordInputType(node.inputType) || isPasswordInputType(imeType)
        return Field(identity = node, isPassword = password)
    }

    /** Adds one space when the character before the cursor is not whitespace; false when there is no input connection. */
    fun insert(text: String): Boolean {
        val connection = service.inputMethod?.currentInputConnection ?: return false
        val around = connection.getSurroundingText(1, 0, 0)
        val before = around?.let { it.text.subSequence(0, it.selectionStart.coerceIn(0, it.text.length)).toString() }.orEmpty()
        val spaced = if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
        connection.commitText(spaced, 1, null)
        return true
    }

    /** Clipboard mode; not marked sensitive, so the preview shows the text. */
    fun copy(text: String) {
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("voxdroid", text))
    }

    fun keyboardTag(): String? = FieldAccessTag.current(service)

    private fun children(node: AccessibilityNodeInfo) = (0 until node.childCount).mapNotNull { node.getChild(it) }

    private companion object {
        // One IPC per node with the cache off; a page's whole tree would stall the trigger.
        const val WALK_CAP = 500
    }
}

/** Depth-first from [root] in child order, visiting at most [cap] nodes. */
internal fun <N> firstMatch(root: N, cap: Int, children: (N) -> List<N>, match: (N) -> Boolean): N? {
    val stack = ArrayDeque(listOf(root))
    var visited = 0
    while (stack.isNotEmpty() && visited++ < cap) {
        val node = stack.removeLast()
        if (match(node)) return node
        children(node).asReversed().forEach(stack::addLast)
    }
    return null
}

object FieldAccessTag {
    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        val subtype = context.getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype ?: return null
        return subtype.languageTag.ifBlank { subtype.locale }.takeIf { it.isNotBlank() }
    }
}
