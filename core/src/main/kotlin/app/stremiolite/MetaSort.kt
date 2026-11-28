package app.stremiolite

/** Sorting a list of titles (search results), shared by every front-end. */
object MetaSort {
    enum class Mode(val label: String) { RELEVANCE("Best match"), NAME("Name A–Z"), NEWEST("Newest first"), OLDEST("Oldest first"), RATING("Highest rated") }

    fun next(m: Mode): Mode = Mode.values()[(m.ordinal + 1) % Mode.values().size]

    private fun year(m: Meta): Int = Regex("""\d{4}""").find(m.year)?.value?.toIntOrNull() ?: 0
    private fun rating(m: Meta): Double = m.rating.replace(',', '.').toDoubleOrNull() ?: 0.0

    /** [list] in [mode]'s order; titles without a year or rating go last. Best match keeps the add-ons' order. */
    fun <T> apply(list: List<T>, mode: Mode, meta: (T) -> Meta): List<T> = when (mode) {
        Mode.RELEVANCE -> list
        Mode.NAME -> list.sortedBy { meta(it).name.lowercase().removePrefix("the ") }
        Mode.NEWEST -> list.sortedWith(compareByDescending<T> { year(meta(it)) })
        Mode.OLDEST -> list.sortedWith(compareBy<T> { year(meta(it)).let { y -> if (y == 0) Int.MAX_VALUE else y } })
        Mode.RATING -> list.sortedWith(compareByDescending<T> { rating(meta(it)) })
    }
}
