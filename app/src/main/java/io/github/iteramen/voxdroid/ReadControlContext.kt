package io.github.iteramen.voxdroid

internal enum class ReadControlContext { Hidden, DictationOnly, External }

internal fun readControlContext(ownApp: Boolean, tryField: Boolean, locked: Boolean, password: Boolean): ReadControlContext =
    if (locked || password || ownApp) ReadControlContext.Hidden else ReadControlContext.External
