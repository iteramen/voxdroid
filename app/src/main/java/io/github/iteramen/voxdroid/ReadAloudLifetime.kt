package io.github.iteramen.voxdroid

import android.media.AudioManager
import android.view.accessibility.AccessibilityEvent

/** Event policy separated from Android node access so lifecycle regressions run on the JVM. */
internal fun readAloudInvalidated(
    sourceMatches: Boolean,
    eventFromSource: Boolean,
    eventType: Int?,
    picking: Boolean,
    selectionMatches: Boolean,
): Boolean = !sourceMatches || (!picking && !selectionMatches) || (eventFromSource && when (eventType) {
    AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> picking
    else -> false
})

/** A notification sound asks only to duck; speech plays on beneath it. */
internal fun readAloudStopsOnFocusChange(change: Int): Boolean =
    change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
