package app.stremiolite

/** Whether a film is out to stream yet, and if not, when and where it will be. */
object FilmOut {
    /**
     * (out?, note). With a TMDB key its digital and cinema dates decide ("in cinemas · Netflix 23 Dec");
     * without one, the release day the add-ons give. Blocking.
     */
    fun check(addons: List<Addon>, tmdbKey: String, region: String, id: String, now: Long = System.currentTimeMillis()): Pair<Boolean, String> {
        val today = ComingSoon.today(now)
        if (tmdbKey.isNotBlank()) runCatching { WhereToWatch.load(tmdbKey, "movie", id, "", region) }.getOrNull()?.let { w ->
            return if (w.notOutYet(today)) false to w.notOutLabel(today) else true to ""
        }
        val meta = runCatching { MetaParse.fetch(addons, "movie", id) }.getOrNull()
        val day = ComingSoon.day(meta?.releasedDay.orEmpty())
        if (day > today) return false to "out " + ComingSoon.label(day)
        return true to ""
    }
}
