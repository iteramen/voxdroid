package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Android's accessibility button, handed to the main service. Its own service because Android fixes the button
 * request at install and keeps the button on screen for as long as a requesting service is on.
 */
class ButtonService : AccessibilityService() {
    private val button = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            ButtonBridge.tap?.invoke()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        accessibilityButtonController.registerAccessibilityButtonCallback(button)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}

/** Both services run on the app's main thread; the main service sets this while it is connected. */
internal object ButtonBridge {
    @Volatile var tap: (() -> Unit)? = null
}
