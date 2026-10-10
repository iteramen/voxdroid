package io.github.iteramen.voxdroid

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings.Secure

/** Debug builds only: with `WRITE_SECURE_SETTINGS` granted over adb, the status card turns the service back on itself. */
internal object SelfEnable {
    fun available(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun turnOn(context: Context, component: String) {
        val resolver = context.contentResolver
        for (key in listOf(Secure.ENABLED_ACCESSIBILITY_SERVICES, BUTTON_TARGETS)) {
            Secure.putString(resolver, key, withService(Secure.getString(resolver, key), component))
        }
        Secure.putInt(resolver, Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val BUTTON_TARGETS = "accessibility_button_targets"
}
