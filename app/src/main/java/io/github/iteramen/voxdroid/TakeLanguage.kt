package io.github.iteramen.voxdroid

/** The keyboard's language when the user checked it, auto between the candidates otherwise. */
internal fun takeLanguage(
    keyboardTag: String?,
    isExplicit: (String) -> Boolean,
    candidates: List<String>,
): SttLanguage {
    val code = normalizeTag(keyboardTag) ?: return SttLanguage.Auto(candidates)
    return if (isExplicit(code)) SttLanguage.Explicit(code) else SttLanguage.Auto(candidates)
}

/** Explicit languages with no lane are left out: detection across them steals takes from the others. */
internal fun candidatesFor(
    listed: List<String>,
    isExplicit: (String) -> Boolean,
    laneCodes: Collection<String>,
): List<String> = listed.filter { !isExplicit(it) || it in laneCodes }

internal fun takeFor(
    keyboardTag: String?,
    isExplicit: (String) -> Boolean,
    listed: List<String>,
    laneCodes: Collection<String>,
): SttLanguage = takeLanguage(keyboardTag, isExplicit, candidatesFor(listed, isExplicit, laneCodes))

/** Android still reports Hebrew, Indonesian and Yiddish as `iw`, `in` and `ji` on some paths. */
internal fun normalizeTag(tag: String?): String? {
    val primary = tag?.trim()?.lowercase()?.split('-', '_')?.firstOrNull().orEmpty()
    val code = when (primary) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> primary
    }
    return code.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
}
