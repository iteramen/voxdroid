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
        val node = service.getRootInActiveWindow(0)?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!node.isEditable) return null
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
}

object FieldAccessTag {
    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        val subtype = context.getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype ?: return null
        return subtype.languageTag.ifBlank { subtype.locale }.takeIf { it.isNotBlank() }
    }
}
