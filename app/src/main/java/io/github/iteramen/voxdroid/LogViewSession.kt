package io.github.iteramen.voxdroid

/** The records held by an open viewer, extracted from its list and Copy listeners. */
internal class LogViewSession(private val now: () -> Long = System::currentTimeMillis) {
    var entries: List<DictationEntry> = emptyList()
        private set

    fun replace(records: List<DictationEntry>) { entries = records }

    fun entry(id: String): DictationEntry? = entries.firstOrNull { it.id == id }
}
