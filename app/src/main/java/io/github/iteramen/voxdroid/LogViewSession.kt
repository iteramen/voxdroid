package io.github.iteramen.voxdroid

/** UI-owned records and generations. Old asynchronous loads and expired selections cannot repopulate the viewer. */
internal class LogViewSession(private val now: () -> Long = System::currentTimeMillis) {
    private var records: List<DictationEntry> = emptyList()
    private var generation = 0L
    private var epoch: String? = null
    var active = false
        private set

    val entries: List<DictationEntry> get() {
        records = records.filter { fresh(it) }
        return records
    }

    fun replace(records: List<DictationEntry>) { this.records = newestFirst(records.filter { fresh(it) }) }
    fun entry(id: String): DictationEntry? = entries.firstOrNull { it.id == id }
    fun resume() { active = true }
    fun pause() { active = false; invalidate() }
    fun currentRequest(request: Long): Boolean = active && request == generation

    fun invalidate(): Long {
        generation++
        records = emptyList()
        epoch = null
        return generation
    }

    fun accept(request: Long, readEpoch: String?, records: List<DictationEntry>, currentEpoch: String?): Boolean {
        if (!currentRequest(request) || readEpoch != currentEpoch) return false
        epoch = readEpoch
        replace(if (readEpoch == null) emptyList() else records)
        return true
    }

    fun currentEntry(id: String, currentEpoch: String?): DictationEntry? =
        if (active && epoch != null && epoch == currentEpoch) entry(id) else null

    fun nextExpiryDelay(): Long? = entries.minOfOrNull { it.atMs + DICTATION_LOG_TTL_MS - now() }?.coerceAtLeast(1L)

    private fun fresh(entry: DictationEntry): Boolean {
        val time = now()
        return entry.atMs >= 0 && entry.atMs <= time && time - entry.atMs < DICTATION_LOG_TTL_MS
    }
}
