package io.github.iteramen.voxdroid

import android.view.accessibility.AccessibilityEvent

/** Event policy separated from Android node access so lifecycle regressions run on the JVM. */
internal fun readAloudInvalidated(
    sourceMatches: Boolean,
    eventFromSource: Boolean,
    eventType: Int?,
    picking: Boolean,
    selectionMatches: Boolean,
): Boolean = !sourceMatches || (eventFromSource && eventType in setOf(
    AccessibilityEvent.TYPE_VIEW_SCROLLED,
    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
))
