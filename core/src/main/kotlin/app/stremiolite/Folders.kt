package app.stremiolite

/** How catalogue rows are sorted into the Index: "Folder: Name" prefixes first, then keywords. */
object Folders {
    /** Rows the app can open: nothing required, or only a genre (AIOMetadata fills a default). */
    fun openable(c: Addon.Catalog) = c.required.isEmpty() || c.required.all { it == "genre" }

    private val auto = listOf(
        "Streaming" to listOf("netflix", "prime video", "disney+", "apple tv", "hbo", "paramount", "hulu", "peacock", "starz", "crunchyroll", "hayu", "sky ", "now tv", "itv", "bbc", "channel 4"),
        "Kids & Family" to listOf("kids", "family", "nick", "cartoon", "pixar", "dreamworks", "disney"),
        "Documentaries" to listOf("documentar", "docs", "nature"),
        "Decades" to listOf("70s", "80s", "90s", "2000s", "2010s", "2020s", "decade"),
        "Studios & Directors" to listOf("studio", "pictures", "director", "a24", "marvel", "lucasfilm", "blumhouse"),
        "Collections" to listOf("collection", "film series"),
        "Indian" to listOf("bollywood", "india", "hindi", "tamil", "telugu"),
        "Anime" to listOf("anime", "mal "),
        "Charts" to listOf("top 250", "top rated", "imdb", "anticipated", "certified", "chart", "award"),
        "Genres" to listOf("action", "adventure", "comedy", "crime", "drama", "fantasy", "horror", "mystery", "romance", "sci-fi", "science fiction", "thriller", "war", "western", "history", "music", "animat", "sitcom"),
    )

    fun autoFolder(name: String): String {
        val n = " ${name.lowercase()} "
        return auto.firstOrNull { (_, words) -> words.any { it in n } }?.first ?: "More"
    }

    /** Splits "Folder: Name" (short folder part only, so titles with colons are left alone). */
    fun split(name: String): Pair<String?, String> {
        val i = name.indexOf(": ")
        if (i in 1..24) return name.substring(0, i).trim() to name.substring(i + 2).trim()
        return null to name
    }

}
