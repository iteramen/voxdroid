package io.github.iteramen.voxdroid

/** These providers were measured on controlled fixtures; neither URI paths nor labels identify audio. */
internal enum class CaptionApp(val packageName: String, val authority: String, val label: String) {
    Telegram("org.telegram.messenger", "org.telegram.messenger.provider", "Telegram"),
    WhatsApp("com.whatsapp", "com.whatsapp.provider.media", "WhatsApp"),
    ;

    companion object {
        fun forPackage(name: CharSequence?): CaptionApp? = entries.firstOrNull { it.packageName == name?.toString() }
        fun forProvider(authority: String?, providerPackage: String?): CaptionApp? =
            entries.firstOrNull { it.authority == authority && it.packageName == providerPackage }
    }
}

/** In-memory only. Deliberately has no generated toString: the header can contain someone's name. */
internal class CaptionOrigin(val source: CaptionApp, val window: Int, val header: String) {
    fun same(other: CaptionOrigin?): Boolean = other != null && source == other.source && window == other.window && header == other.header
}

internal enum class CaptionPhase { Selected, Sharing, Reading, Returning, Processing, Shown }

/** A single visible origin; invalidation is permanent, including when its old chat returns later. */
internal class CaptionSession(private val now: () -> Long) {
    @Volatile private var generation = 0L
    @Volatile private var origin: CaptionOrigin? = null
    private var expiresAt = 0L
    var phase: CaptionPhase? = null
        private set

    val active: Boolean get() = origin != null
    val currentOrigin: CaptionOrigin? get() = origin

    fun select(value: CaptionOrigin): Long {
        generation++
        origin = value
        phase = CaptionPhase.Selected
        expiresAt = now() + HANDOFF_MS
        return generation
    }

    fun current(id: Long): Boolean = generation == id && origin != null
    fun id(): Long = generation

    /** Losing the selection before sharing must revoke eligibility to receive an audio grant. */
    fun selectionLost() { if (phase == CaptionPhase.Selected) invalidate() }

    fun share(id: Long): Boolean {
        if (!current(id) || phase != CaptionPhase.Selected || now() >= expiresAt) return false
        phase = CaptionPhase.Sharing
        return true
    }

    fun receive(source: CaptionApp): Long? {
        if (origin?.source != source || now() >= expiresAt || phase !in setOf(CaptionPhase.Selected, CaptionPhase.Sharing)) return null
        phase = CaptionPhase.Reading
        return generation
    }

    fun returning(id: Long): Boolean {
        if (!current(id) || phase != CaptionPhase.Reading) return false
        phase = CaptionPhase.Returning
        expiresAt = now() + RETURN_MS
        return true
    }

    fun waitingForReturn(id: Long): Boolean = current(id) && phase == CaptionPhase.Returning && now() < expiresAt

    fun returned(id: Long, value: CaptionOrigin?): Boolean {
        if (!current(id) || phase != CaptionPhase.Returning || now() >= expiresAt || !origin!!.same(value)) return false
        phase = CaptionPhase.Processing
        return true
    }

    fun shown(id: Long): Boolean {
        if (!current(id) || phase !in setOf(CaptionPhase.Processing, CaptionPhase.Shown)) return false
        phase = CaptionPhase.Shown
        return true
    }

    fun stableSource(value: CaptionOrigin?, sourceViewChanged: Boolean): Boolean =
        origin?.same(value) == true && !sourceViewChanged

    fun invalidate() {
        generation++
        origin = null
        phase = null
    }

    companion object {
        const val HANDOFF_MS = 30_000L
        const val RETURN_MS = 3_000L
    }
}

/** Intent metadata only: no raw URI, filename, sender or chat is needed to decide this boundary. */
internal fun captionShareAllowed(send: Boolean, content: Boolean, grant: Boolean, clipCount: Int?, clipMatches: Boolean, dataMatches: Boolean): Boolean =
    send && content && grant && (clipCount == null || clipCount == 1) && clipMatches && dataMatches

/** Measured English selection chrome: accept the whole label, never a number embedded in other text. */
internal fun captionTelegramSelectionCount(label: CharSequence?): Int? {
    val match = Regex("([1-9][0-9]*)(?: Selected)?", RegexOption.IGNORE_CASE)
        .matchEntire(label?.toString()?.trim() ?: return null) ?: return null
    return match.groupValues[1].toIntOrNull()
}

internal fun captionTelegramHeader(label: CharSequence?): String? = label?.toString()?.trim()?.takeIf {
    it.isNotBlank() && it.length <= 512 && !it.matches(Regex("\\d+(?:\\s+selected)?", RegexOption.IGNORE_CASE))
}


/** Read metadata only for a selected, recognized Telegram message node. */
internal fun captionTelegramVoiceNode(selected: Boolean, className: CharSequence?, metadata: () -> String): Boolean =
    selected && className?.toString() in setOf("android.view.ViewGroup", "android.widget.LinearLayout") &&
        metadata().contains("Voice message", ignoreCase = true)
