package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.io.PrintWriter

/** Shell inspection for the opt-in isolated APK. Only known controls in our own windows; no text fields. */
internal object HandsetTestControls {
    fun dump(service: AccessibilityService, writer: PrintWriter) {
        if (!service.packageName.endsWith(".captiontest")) return
        val roots = (service.windows.mapNotNull { it.root } + listOfNotNull(service.getRootInActiveWindow(0)))
            .filter { it.packageName?.toString() == service.packageName }.distinctBy { it.windowId }
        val controls = linkedMapOf<String, MutableSet<String>>()
        fun add(key: String, node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser || !node.isEnabled) return
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.isEmpty) return
            controls.getOrPut(key) { linkedSetOf() }.add("${rect.left},${rect.top},${rect.right},${rect.bottom}")
        }
        for (root in roots) {
            root.findAccessibilityNodeInfosByViewId("${service.packageName}:id/row_voice_messages")
                .forEach { add("voice-settings", it) }
            root.findAccessibilityNodeInfosByViewId("${service.packageName}:id/try_field")
                .forEach { add("try-field", it) }
            val titles = mapOf(
                service.getString(R.string.caption_remember) to "remember",
                service.getString(R.string.caption_delete_all) to "delete-all",
            )
            root.findAccessibilityNodeInfosByViewId("android:id/title").forEach { node ->
                titles[node.text?.toString()]?.let { add(it, node) }
            }
            root.findAccessibilityNodeInfosByViewId("android:id/button1")
                .filter { it.text?.toString() == service.getString(R.string.caption_delete_all) }
                .forEach { add("confirm-delete", it) }
            root.findAccessibilityNodeInfosByViewId("android:id/button2").forEach { add("cancel-dialog", it) }
            for ((id, key) in listOf(R.string.caption_close to "close-caption",
                R.string.caption_copy to "copy-caption", R.string.caption_retranscribe to "refresh-caption")) {
                val label = service.getString(id)
                root.findAccessibilityNodeInfosByText(label)
                    .filter { it.className?.toString() == "android.widget.Button" && it.text?.toString() == label }
                    .forEach { add(key, it) }
            }
        }
        writer.println("test-controls ownWindows=${roots.size}")
        controls.forEach { (key, bounds) -> if (bounds.size == 1) writer.println("test-control $key=${bounds.single()}") }
    }
}
