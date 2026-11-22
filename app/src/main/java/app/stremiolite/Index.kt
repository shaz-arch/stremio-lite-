package app.stremiolite

/**
 * The Index: catalogue rows grouped into folders.
 *
 * A row named "Folder: Name" goes into that folder. Any other row that is not on the
 * add-on's home (discover-only) is sorted by keywords in its name, or into "More".
 * Rows on the home with no folder prefix stay on the Home front page.
 */
object Index {
    class Entry(val addon: Addon, val catalog: Addon.Catalog, val folder: String?, val name: String)

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

    private fun autoFolder(name: String): String {
        val n = " ${name.lowercase()} "
        return auto.firstOrNull { (_, words) -> words.any { it in n } }?.first ?: "More"
    }

    /** Splits "Folder: Name" (short folder part only, so titles with colons are left alone). */
    private fun split(name: String): Pair<String?, String> {
        val i = name.indexOf(": ")
        if (i in 1..24) return name.substring(0, i).trim() to name.substring(i + 2).trim()
        return null to name
    }

    /** Every openable, not hidden catalogue, with its folder (null = front page). */
    fun entries(app: MainActivity, list: List<Addon>): List<Entry> {
        val hidden = Settings.hiddenRows(app)
        val pairs = Home.rowAddons(list).filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { openable(it) && Settings.rowKey(a, it) !in hidden }.map { a to it }
        }
        // A prefix used by only one row is part of a title (e.g. "UK TV: BBC, ITV…"), not a folder.
        val prefixCount = pairs.mapNotNull { split(it.second.name).first }.groupingBy { it }.eachCount()
        return pairs.map { (a, c) ->
            val (prefix, rest) = split(c.name).let { (p, r) -> if (p != null && (prefixCount[p] ?: 0) >= 2) p to r else null to c.name }
            val folder = prefix ?: if (c.required.isEmpty()) null else autoFolder(c.name)
            Entry(a, c, folder, rest)
        }
    }

    /** One row per top-level folder ("Streaming > Movies" belongs to "Streaming"), in first-seen order. */
    fun folderRows(app: MainActivity, entries: List<Entry>): List<Row> = groupRows(app, entries.filter { it.folder != null }, 0)

    private fun parts(e: Entry) = e.folder!!.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }

    /** Rows for the folders at [depth], with "More" last. */
    private fun groupRows(app: MainActivity, list: List<Entry>, depth: Int): List<Row> {
        val groups = LinkedHashMap<String, MutableList<Entry>>()
        list.forEach { e -> parts(e).getOrNull(depth)?.let { groups.getOrPut(it) { mutableListOf() } += e } }
        groups.remove("More")?.let { groups["More"] = it }
        return groups.map { (name, l) -> Row(name, "${l.size}  ►", opens = { folder(app, name, l, depth + 1) }) }
    }

    private fun folder(app: MainActivity, name: String, list: List<Entry>, depth: Int): Screen {
        val main = list.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
        val subs = groupRows(app, list, depth)
        val direct = list.filter { parts(it).size <= depth }.map { e ->
            val tag = if (e.addon === main) "" else e.addon.name.substringBefore('|').trim().take(18) + " · "
            Row(e.name, tag + Catalogs.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(app, e.addon, e.catalog, e.name) })
        }
        val title = if (depth > 1) list.first().let { parts(it).take(depth).joinToString(" · ") } else name
        return Screen(title, subs + direct, tag = "Index")
    }
}
