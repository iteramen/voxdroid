package io.github.iteramen.voxdroid

import android.accessibilityservice.AccessibilityService
import java.io.PrintWriter

/** No handset inspection in release builds. */
internal object HandsetTestControls {
    @Suppress("UNUSED_PARAMETER")
    fun dump(service: AccessibilityService, writer: PrintWriter) = Unit
}
