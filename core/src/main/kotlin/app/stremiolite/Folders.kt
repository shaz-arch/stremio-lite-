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


    /**
     * Add-ons whose rows and search the apps use: an add-on installed as "stick rows" takes the
     * place of the add-on with the same id (e.g. the phone's AIOMetadata) for rows and search.
     */
    fun rowAddons(list: List<Addon>): List<Addon> {
        val replaced = list.filter { it.stickRows }.map { it.id }.toSet()
        return list.filter { it.stickRows || it.id !in replaced }
    }

    /** A row with the folder it belongs in (null = the front page) and its name without the prefix. */
    class Entry(val addon: Addon, val catalog: Addon.Catalog, val folder: String?, val name: String)

    /** "addonName|type|id", the key hidden rows are stored under. */
    fun rowKey(a: Addon, c: Addon.Catalog) = "${a.name}|${c.type}|${c.id}"

    /** Every openable, not hidden row, with its folder. */
    fun entries(list: List<Addon>, hidden: Set<String>): List<Entry> {
        val pairs = rowAddons(list).filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { openable(it) && rowKey(a, it) !in hidden }.map { a to it }
        }
        // A prefix used by only one row is part of a title (e.g. "UK TV: BBC, ITV…"), not a folder.
        val prefixCount = pairs.mapNotNull { split(it.second.name).first }.groupingBy { it }.eachCount()
        return pairs.map { (a, c) ->
            val (prefix, rest) = split(c.name).let { (p, r) -> if (p != null && (prefixCount[p] ?: 0) >= 2) p to r else null to c.name }
            val folder = prefix ?: if (c.required.isEmpty()) null else autoFolder(c.name)
            Entry(a, c, folder, rest)
        }
    }
}
