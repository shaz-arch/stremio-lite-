package app.stremiolite

/** The release last played per show, so the next episode keeps the same group, quality and source (binge mode). */
object StreamMemory {
    private const val STORE = "binge"
    /** Keep it small: about the 40 most recent shows. */
    private const val KEEP = 40

    fun lastFor(showId: String): Pref? {
        val p = (Platform.storage.get(STORE, showId) ?: return null).split('|')
        return if (p.size >= 3) Pref(p[0], p[1], p[2]) else null
    }

    fun remember(showId: String, s: Stream) {
        val changes = LinkedHashMap<String, String?>()
        changes[showId] = "${s.group}|${s.quality}|${s.source}"
        val others = Platform.storage.keys(STORE).filter { it != showId }
        if (others.size > KEEP) others.take(others.size - KEEP).forEach { changes[it] = null }
        Platform.storage.edit(STORE, changes)
    }
}
