package app.stremiolite

/**
 * The Index: catalogue rows grouped into folders.
 *
 * A row named "Folder: Name" goes into that folder. Any other row that is not on the
 * add-on's home (discover-only) is sorted by keywords in its name, or into "More".
 * Rows on the home with no folder prefix stay on the Home front page.
 * Your own arrangement (Settings › Arrange your Index) is applied on top.
 */
object Index {
    fun openable(c: Addon.Catalog) = Folders.openable(c)

    /** Every openable, not hidden catalogue, with its folder (null = front page). Rules shared with Linux. */
    fun entries(app: MainActivity, list: List<Addon>): List<Folders.Entry> =
        Folders.entries(list, Settings.hiddenRows(app), Settings.layout(app))

    /** The front page rows, in your order. */
    fun front(app: MainActivity, entries: List<Folders.Entry>) = Layout.front(entries, Settings.layout(app))

    /** One row per top-level folder, in your order. */
    fun folderRows(app: MainActivity, entries: List<Folders.Entry>): List<Row> =
        Layout.tree(entries, Settings.layout(app)).folders.map { n -> Row(n.name, "${n.count}  ►", opens = { folder(app, n) }) }

    private fun folder(app: MainActivity, n: Layout.Node): Screen {
        val main = n.rows.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
        val subs = n.folders.map { f -> Row(f.name, "${f.count}  ►", opens = { folder(app, f) }) }
        val direct = n.rows.map { e ->
            val tag = if (e.addon === main) "" else e.addon.name.substringBefore('|').trim().take(18) + " · "
            Row(e.name, tag + Catalogs.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(app, e.addon, e.catalog, e.name) })
        }
        val title = Layout.parts(n.path).joinToString(" · ")
        return Screen(title, subs + direct, tag = "Index").also { it.key = "index:$title" }
    }
}
