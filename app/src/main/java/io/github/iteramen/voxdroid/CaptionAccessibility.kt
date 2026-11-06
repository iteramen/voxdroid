package io.github.iteramen.voxdroid

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Temporary nodes only, used synchronously on the service thread. No screen text leaves this class. */
internal object CaptionAccessibility {
    private const val MAX_NODES = 600
    private val lists = setOf("androidx.recyclerview.widget.RecyclerView", "android.widget.ListView")

    data class Selection(val count: Int, val voice: AccessibilityNodeInfo?, val share: AccessibilityNodeInfo?, val overflow: AccessibilityNodeInfo?)

    fun source(root: AccessibilityNodeInfo?): CaptionApp? = CaptionApp.forPackage(root?.packageName)

    /** Routine observation never enters the message list, reads the composer or retains its contents. */
    fun origin(root: AccessibilityNodeInfo?, density: Float): CaptionOrigin? {
        val source = source(root) ?: return null
        root ?: return null
        if (source == CaptionApp.WhatsApp) {
            if (whatsappNodes(root, "entry").none { it.className?.toString() == "android.widget.EditText" }) return null
            val title = whatsappNodes(root, "conversation_contact_name").singleOrNull()?.text?.toString()?.trim().orEmpty()
            if (title.isBlank() || title.length > 512) return null
            return CaptionOrigin(source, root.windowId, title)
        }
        val nodes = nodes(root, skipLists = true)
        val editors = nodes.filter { it.className?.toString() == "android.widget.EditText" }
        if (editors.isEmpty()) return null
        val title = run {
            val heading = nodes.filter { it.className?.toString() == "android.widget.TextView" && top(it) < (160 * density).toInt() }
                .sortedBy(::top).firstOrNull() ?: return null
            val headingBottom = Rect().also(heading::getBoundsInScreen).bottom
            // A toolbar search field is not a conversation composer.
            if (editors.none { top(it) > headingBottom }) return null
            heading.text?.toString()
        }?.trim().orEmpty()
        if (title.isBlank() || title.length > 512 || title.matches(Regex("\\d+(?:\\s+selected)?"))) return null
        return CaptionOrigin(source, root.windowId, title)
    }

    /** The toolbar is checked before visiting selected message metadata. Unselected message text is never read. */
    fun selection(root: AccessibilityNodeInfo): Selection {
        val source = source(root) ?: return Selection(0, null, null, null)
        if (source == CaptionApp.WhatsApp) return whatsappSelection(root)
        val chrome = nodes(root, skipLists = true)
        val selectionMode = chrome.any { it.contentDescription?.toString() == "Forward" } &&
            chrome.any { it.contentDescription?.toString() == "Delete" }
        if (!selectionMode) return Selection(0, null, null, null)
        val count = chrome.filter { it.className?.toString() == "android.widget.TextView" }
            .mapNotNull { it.text?.toString()?.trim()?.toIntOrNull() }.singleOrNull()
            ?: -1 // An unreadable selected-mode count must refuse, never fall through to microphone dictation.
        if (count != 1) return Selection(count, null, null, null)
        val selected = nodes(root).filter { it.isSelected && it.className?.toString() in setOf("android.view.ViewGroup", "android.widget.LinearLayout") }
        val voices = selected.filter { node ->
            (node.contentDescription?.toString().orEmpty() + " " + node.text?.toString().orEmpty()).contains("Voice message", ignoreCase = true)
        }
        val voice = voices.singleOrNull() ?: return Selection(1, null, null, null)
        val share = chrome.filter { it.contentDescription?.toString() == "Share" && it.isVisibleToUser && it.isClickable }
                .filter { node ->
                    val siblings = node.parent?.let { nodes(it, skipLists = true) }.orEmpty()
                    siblings.any { it.contentDescription?.toString() == "Forward" } &&
                        siblings.any { it.contentDescription?.toString() == "Delete" }
                }.singleOrNull()
        return Selection(1, voice, share, null)
    }

    private fun whatsappNodes(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/$id").filter { it.isVisibleToUser }

    private fun whatsappSelection(root: AccessibilityNodeInfo): Selection {
        if (whatsappNodes(root, "action_mode_close_button").isEmpty()) return Selection(0, null, null, null)
        val count = listOf("action_mode_title", "action_bar_title").firstNotNullOfOrNull { id ->
            whatsappNodes(root, id).singleOrNull()?.text?.toString()?.trim()?.toIntOrNull()
        } ?: -1
        if (count != 1) return Selection(count, null, null, null)
        val voice = whatsappNodes(root, "conversation_row_voice_note").filter { it.isSelected }.singleOrNull()
            ?: return Selection(1, null, null, null)
        // The normal toolbar remains in the tree at identical bounds beneath selection mode.
        // Only the selected-message toolbar's overflow owns this action.
        val overflow = whatsappNodes(root, "menuitem_overflow").filter { node ->
            node.isClickable && generateSequence(node) { it.parent }.take(6).any {
                it.viewIdResourceName == "com.whatsapp:id/action_mode_bar"
            }
        }.distinctBy(::bounds).singleOrNull()
        return Selection(1, voice, null, overflow)
    }

    fun whatsappShare(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val title = whatsappNodes(root, "title").filter { it.text?.toString() == "Share" }.singleOrNull() ?: return null
        // Current WhatsApp exposes the label inside non-clickable containers, then a Button.
        // Never fall back to clicking the popup/list itself or another menu entry.
        return generateSequence(title) { it.parent }.take(4).firstOrNull {
            it.packageName == title.packageName && it.isVisibleToUser && it.isClickable &&
                (it == title || it.className?.toString() == "android.widget.Button")
        }
    }

    private fun top(node: AccessibilityNodeInfo): Int = Rect().also(node::getBoundsInScreen).top
    private fun bounds(node: AccessibilityNodeInfo): String = Rect().also(node::getBoundsInScreen).toShortString()

    private fun nodes(root: AccessibilityNodeInfo, skipLists: Boolean = false): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty() && result.size < MAX_NODES) {
            val node = pending.removeFirst()
            result += node
            if (skipLists && node.className?.toString() in lists) continue
            for (index in 0 until node.childCount.coerceAtMost(100)) node.getChild(index)?.let(pending::addLast)
        }
        return result
    }
}
