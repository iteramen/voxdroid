package io.github.iteramen.voxdroid

/** Title-row text: the version, then the short commit; [commit] is `unknown` when git failed at build time. */
internal fun versionLine(versionName: String, commit: String): String = "$versionName ($commit)"
