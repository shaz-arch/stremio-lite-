package app.stremiolite

/**
 * The Index: catalogue rows grouped into folders.
 *
 * A row named "Folder: Name" goes into that folder. Any other row that is not on the
 * add-on's home (discover-only) is sorted by keywords in its name, or into "More".
 * Rows on the home with no folder prefix stay on the Home front page.
 */
object Index {
    fun openable(c: Addon.Catalog) = Folders.openable(c)

    /** Every openable, not hidden catalogue, with its folder (null = front page). Rules shared with Linux. */
    fun entries(app: MainActivity, list: List<Addon>): List<Folders.Entry> = Folders.entries(list, Settings.hiddenRows(app))

    /** One row per top-level folder ("Streaming > Movies" belongs to "Streaming"), in first-seen order. */
    fun folderRows(app: MainActivity, entries: List<Folders.Entry>): List<Row> = groupRows(app, entries.filter { it.folder != null }, 0)

    private fun parts(e: Folders.Entry) = e.folder!!.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }

    /** Rows for the folders at [depth], with "More" last. */
    private fun groupRows(app: MainActivity, list: List<Folders.Entry>, depth: Int): List<Row> {
        val groups = LinkedHashMap<String, MutableList<Folders.Entry>>()
        list.forEach { e -> parts(e).getOrNull(depth)?.let { groups.getOrPut(it) { mutableListOf() } += e } }
        groups.remove("More")?.let { groups["More"] = it }
        return groups.map { (name, l) -> Row(name, "${l.size}  ►", opens = { folder(app, name, l, depth + 1) }) }
    }

    private fun folder(app: MainActivity, name: String, list: List<Folders.Entry>, depth: Int): Screen {
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
