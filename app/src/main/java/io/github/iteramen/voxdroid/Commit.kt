package io.github.iteramen.voxdroid

internal data class Sanitized(val text: String, val dropped: Int)

data class Finished(val text: String, val dropped: Int)

/** True for control characters and format characters, except the bidi marks and joiners real Hebrew, Arabic and emoji text needs. */
private fun isForbidden(cp: Int): Boolean =
    Character.isISOControl(cp) || (isFormat(cp) && cp !in 0x200C..0x200F)

/** Unicode 16.0 category `Cf`, copied because [Character.getType] follows the device's ICU version. */
private fun isFormat(cp: Int): Boolean =
    cp == 0x00AD ||
        cp in 0x0600..0x0605 ||
        cp == 0x061C ||
        cp == 0x06DD ||
        cp == 0x070F ||
        cp in 0x0890..0x0891 ||
        cp == 0x08E2 ||
        cp == 0x180E ||
        cp in 0x200B..0x200F ||
        cp in 0x202A..0x202E ||
        cp in 0x2060..0x2064 ||
        cp in 0x2066..0x206F ||
        cp == 0xFEFF ||
        cp in 0xFFF9..0xFFFB ||
        cp == 0x110BD ||
        cp == 0x110CD ||
        cp in 0x13430..0x1343F ||
        cp in 0x1BCA0..0x1BCA3 ||
        cp in 0x1D173..0x1D17A ||
        cp == 0xE0001 ||
        cp in 0xE0020..0xE007F

/** Removes [isForbidden] characters, counting them, and turns line breaks into spaces; an unpaired surrogate is dropped too. */
internal fun sanitize(text: String): Sanitized {
    val kept = StringBuilder(text.length)
    var dropped = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\n'.code || cp == 0x2028 || cp == 0x2029 -> kept.append(' ')
            isForbidden(cp) || cp in 0xD800..0xDFFF -> dropped++
            else -> kept.appendCodePoint(cp)
        }
    }
    return Sanitized(kept.toString(), dropped)
}

internal fun droppedNote(dropped: Int): String? =
    if (dropped > 0) "commit: removed $dropped control or format characters" else null

/** [sanitize], trim, then exactly one terminal `.` when [stripTerminalPeriod]; `..` and `?` stay. */
fun finish(text: String, stripTerminalPeriod: Boolean): Finished {
    val sanitized = sanitize(text)
    var typed = sanitized.text.trim()
    if (stripTerminalPeriod && typed.endsWith('.') && !typed.endsWith("..")) {
        typed = typed.dropLast(1).trimEnd()
    }
    return Finished(typed, sanitized.dropped)
}
