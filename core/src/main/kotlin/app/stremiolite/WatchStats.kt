package app.stremiolite

/**
 * Settings-free "Your stats": what the account's library says you've watched (all devices).
 * Time is Stremio's own count of time spent playing each title; the week/month/year lines count
 * titles last played in that time.
 */
object WatchStats {
    class Top(val id: String, val type: String, val name: String, val detail: String)
    class Stats(val lines: List<Pair<String, String>>, val shows: List<Top>, val films: List<Top>)

    fun hours(ms: Long): String {
        val h = ms / 3_600_000.0
        return when {
            ms <= 0 -> "0 hours"
            h < 1 -> "${ms / 60_000} min"
            h < 10 -> "%.1f hours".format(h)
            else -> "%,d hours".format(Math.round(h))
        }
    }

    fun compute(all: List<LibraryItems.Info>, now: Long = System.currentTimeMillis()): Stats {
        val played = all.filter { it.type == "movie" || it.type == "series" }.filter { it.lastWatched > 0 || it.watchedMs > 0 || it.timesWatched > 0 || it.flaggedWatched == 1 }
        val films = played.filter { it.filmWatched }
        val shows = played.filter { it.type == "series" }
        val eps = shows.associateWith { Sync.watchedCount(it.watchedField) }
        val day = 86_400_000L
        fun since(ms: Long) = played.count { it.lastWatched >= now - ms }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val yearStart = java.util.Calendar.getInstance().apply { clear(); set(cal.get(java.util.Calendar.YEAR), 0, 1) }.timeInMillis
        val lines = listOf(
            "Time watching" to hours(played.sumOf { it.watchedMs }),
            "Films watched" to "${films.size}",
            "Episodes watched" to "${eps.values.sum()} (${shows.count { (eps[it] ?: 0) > 0 }} shows)",
            "This week" to "${since(7 * day)} titles played",
            "Last 30 days" to "${since(30 * day)} titles played",
            "This year" to "${played.count { it.lastWatched >= yearStart }} titles played",
        )
        val topShows = shows.sortedWith(compareByDescending<LibraryItems.Info> { it.watchedMs }.thenByDescending { eps[it] ?: 0 })
            .filter { it.watchedMs > 0 || (eps[it] ?: 0) > 0 }.take(10)
            .map { Top(it.id, it.type, it.name, listOfNotNull((eps[it] ?: 0).takeIf { n -> n > 0 }?.let { n -> "$n ep" }, it.watchedMs.takeIf { m -> m > 0 }?.let { m -> hours(m) }).joinToString(" · ")) }
        val topFilms = films.sortedWith(compareByDescending<LibraryItems.Info> { it.timesWatched }.thenByDescending { it.lastWatched })
            .filter { it.timesWatched > 1 }.take(10).map { Top(it.id, it.type, it.name, "${it.timesWatched} times") }
        return Stats(lines, topShows, topFilms)
    }
}
