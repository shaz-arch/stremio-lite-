package app.stremiolite

/**
 * Sorts search results into what people look for: best matches first, then collections,
 * films and shows in their own lists. Shared by the phone/stick and Linux apps.
 */
object SearchRank {
    private val notWord = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    private fun norm(s: String) = s.lowercase().replace('&', ' ').replace(notWord, " ").replace(spaces, " ").trim()

    /** 0 = the same title, 1 = starts with it, 2 = has it as whole words, 3 = has it somewhere, 4 = other. */
    fun score(name: String, query: String): Int {
        val n = norm(name)
        val q = norm(query)
        if (q.isEmpty()) return 4
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            " $n ".contains(" $q ") -> 2
            n.contains(q) -> 3
            else -> 4
        }
    }

    fun isCollection(type: String) = type.startsWith("collection")

    class Groups<T>(val best: List<T>, val collections: List<T>, val films: List<T>, val shows: List<T>, val other: List<T>)

    /** [items] in the order the add-ons gave them (their own relevance), each with its [Meta]. */
    fun <T> group(items: List<T>, query: String, meta: (T) -> Meta): Groups<T> {
        val ranked = items.withIndex()
            .sortedWith(compareBy({ score(meta(it.value).name, query) }, { it.index }))
            .map { it.value }
        val collections = ranked.filter { isCollection(meta(it).type) }
        val films = ranked.filter { meta(it).type == "movie" }
        val shows = ranked.filter { meta(it).type == "series" }
        val listed = (collections + films + shows).toSet()
        val other = ranked.filter { it !in listed }
        val titles = ranked.filter { !isCollection(meta(it).type) }
        // Close matches; if there are none, the add-ons' own top few.
        val best = titles.filter { score(meta(it).name, query) <= 1 }.take(6).ifEmpty { titles.take(4) }
        return Groups(best, collections, films, shows, other)
    }

    /** Sorts a collection's parts by release date (undated last), keeping the add-on's order otherwise. */
    fun parts(videos: List<Video>): List<Video> =
        videos.withIndex().sortedWith(compareBy({ it.value.released.isEmpty() }, { it.value.released }, { it.index })).map { it.value }
}
