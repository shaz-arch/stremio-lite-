package app.stremiolite

/** Searching what you've played: every word of the search in the name, best first. */
object HistoryMatch {
    fun find(all: List<LibraryItems.Info>, q: String): List<LibraryItems.Info> {
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all.filter { (it.type == "movie" || it.type == "series") && (it.lastWatched > 0 || it.watchedMs > 0 || it.timesWatched > 0 || it.flaggedWatched == 1 || it.offset > 0) }
            .filter { i -> val n = i.name.lowercase(); words.all { it in n } }
            .sortedWith(compareByDescending<LibraryItems.Info> { it.name.lowercase().startsWith(words[0]) }.thenByDescending { it.lastWatched })
            .distinctBy { it.id }.take(60)
    }
}
