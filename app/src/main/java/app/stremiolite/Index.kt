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

    fun openable(c: Addon.Catalog) = Folders.openable(c)

    /** Every openable, not hidden catalogue, with its folder (null = front page). */
    fun entries(app: MainActivity, list: List<Addon>): List<Entry> {
        val hidden = Settings.hiddenRows(app)
        val pairs = Home.rowAddons(list).filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { openable(it) && Settings.rowKey(a, it) !in hidden }.map { a to it }
        }
        // A prefix used by only one row is part of a title (e.g. "UK TV: BBC, ITV…"), not a folder.
        val prefixCount = pairs.mapNotNull { Folders.split(it.second.name).first }.groupingBy { it }.eachCount()
        return pairs.map { (a, c) ->
            val (prefix, rest) = Folders.split(c.name).let { (p, r) -> if (p != null && (prefixCount[p] ?: 0) >= 2) p to r else null to c.name }
            val folder = prefix ?: if (c.required.isEmpty()) null else Folders.autoFolder(c.name)
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
        return Screen(title, subs + direct, tag = "Index").also { it.key = "index:$title" }
    }
}
