package io.github.iteramen.voxdroid

import android.content.Context
import android.content.SharedPreferences

internal class ConfigBackupStore(context: Context) {
    private val settings = context.getSharedPreferences("voxdroid", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    fun snapshot(): ConfigSnapshot = ConfigBackup.snapshot(settings.all, secrets.all)

    fun restore(snapshot: ConfigSnapshot) = restoreConfig(snapshot, ::snapshot,
        { replace(settings, it, false) }, { replace(secrets, it, true) })

    private fun replace(prefs: SharedPreferences, values: Map<String, Any>, secret: Boolean): Boolean {
        val edit = prefs.edit()
        prefs.all.keys.filter { ConfigBackup.allowed(it, secret) }.forEach(edit::remove)
        for ((key, value) in values) when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Float -> edit.putFloat(key, value)
        }
        return edit.commit()
    }
}
