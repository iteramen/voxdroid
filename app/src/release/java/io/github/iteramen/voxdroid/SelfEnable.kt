package io.github.iteramen.voxdroid

import android.content.Context

/** The release build cannot turn its own service on: the status card opens Accessibility settings instead. */
internal object SelfEnable {
    fun available(context: Context): Boolean = false

    fun turnOn(context: Context, component: String) = Unit
}
