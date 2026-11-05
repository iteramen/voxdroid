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
        val nodes = nodes(root, skipLists = true)
        val editors = nodes.filter { it.className?.toString() == "android.widget.EditText" }
        if (editors.isEmpty()) return null
        val title = if (source == CaptionApp.WhatsApp) {
            nodes.firstOrNull { it.viewIdResourceName == "com.whatsapp:id/conversation_contact_name" }?.text?.toString()
        } else {
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
        val chrome = nodes(root, skipLists = true)
        val count = if (source == CaptionApp.WhatsApp) {
            chrome.firstOrNull { it.viewIdResourceName == "com.whatsapp:id/action_mode_title" }?.text?.toString()?.trim()?.toIntOrNull()
                ?: if (chrome.any { it.viewIdResourceName == "com.whatsapp:id/action_mode_close_button" }) {
                    chrome.filter { it.className?.toString() == "android.widget.TextView" }
                        .mapNotNull { it.text?.toString()?.trim()?.toIntOrNull() }.singleOrNull()
                } else null
        } else {
            chrome.filter { it.className?.toString() == "android.widget.TextView" }
                .mapNotNull { it.text?.toString()?.trim()?.toIntOrNull() }.singleOrNull()
        } ?: 0
        if (count != 1) return Selection(count, null, null, null)
        val selected = nodes(root).filter { it.isSelected && it.className?.toString() in setOf("android.view.ViewGroup", "android.widget.LinearLayout") }
        val voices = selected.filter { node ->
            if (source == CaptionApp.WhatsApp) {
                node.viewIdResourceName == "com.whatsapp:id/conversation_row_voice_note" ||
                    nodes(node).any { it.viewIdResourceName == "com.whatsapp:id/conversation_row_audio_player_view" }
            } else {
                (node.contentDescription?.toString().orEmpty() + " " + node.text?.toString().orEmpty()).contains("Voice message", ignoreCase = true)
            }
        }
        val voice = voices.singleOrNull() ?: return Selection(1, null, null, null)
        val share = if (source == CaptionApp.Telegram) {
            chrome.filter { it.contentDescription?.toString() == "Share" && it.isVisibleToUser && it.isClickable }
                .filter { node ->
                    val siblings = node.parent?.let { nodes(it, skipLists = true) }.orEmpty()
                    siblings.any { it.contentDescription?.toString() == "Forward" } &&
                        siblings.any { it.contentDescription?.toString() == "Delete" }
                }.singleOrNull()
        } else null
        val overflow = if (source == CaptionApp.WhatsApp) {
            chrome.filter { it.viewIdResourceName == "com.whatsapp:id/menuitem_overflow" && it.isVisibleToUser && it.isClickable }
                .distinctBy { bounds(it) }.singleOrNull()
        } else null
        return Selection(1, voice, share, overflow)
    }

    fun whatsappShare(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = nodes(root, skipLists = false)
        .filter { it.viewIdResourceName == "com.whatsapp:id/title" && it.text?.toString() == "Share" && it.isVisibleToUser }.singleOrNull()

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
