package app.stremiolite.linux

import app.stremiolite.Folders
import app.stremiolite.Layout
import app.stremiolite.MetaParse

/**
 * Settings › Arrange your Index: move rows between folders (or onto the front page), make and
 * rename folders, rename rows and put everything in order. Saved with the shared settings,
 * so the stick, the phone and Linux all show the same Index.
 */
object Arrange {
    private fun entries() = Index.entries(Home.all())
    private fun keyOf(e: Folders.Entry) = Folders.rowKey(e.addon, e.catalog)
    private fun save(d: Layout.Data) { Prefs.layout = Layout.json(d); Prefs.syncOut(); Home.redraw() }
    private fun layout() = Layout.parse(Prefs.layout)

    fun screen(): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val e = entries()
            if (e.isEmpty()) return listOf(Row("Your rows are still loading. Go back and try again in a moment."))
            val d = layout()
            val root = Layout.tree(e, d)
            return listOf(
                Row("Choose a folder, then a row to move or rename it."),
                Row("Front page", "${Layout.front(e, d).size}  ►", opens = { folder(Layout.FRONT) }),
                Row("──── Index ────"),
            ) + root.folders.map { n -> Row(n.name, "${n.count}  ►", opens = { folder(n.path) }) } + listOf(
                Row("────────────────────"),
                Row("Back to the automatic Index", "►", opens = {
                    confirmScreen("Reset the Index?", "Your folders, names and order go back to how the app files them. Hidden rows stay hidden.",
                        "Yes, reset it") {
                        val dd = layout(); Layout.reset(dd); save(dd)
                        App.toast("The Index is back to automatic"); App.back()
                    }
                }),
            )
        }
        s = Screen("Arrange your Index", build())
        s.pos = 1
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    /** A folder's sub-folders and rows; [path] is a folder path, or [Layout.FRONT]. */
    private fun folder(path: String): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val e = entries()
            val d = layout()
            val rows = mutableListOf<Row>()
            if (path == Layout.FRONT) {
                Layout.front(e, d).forEach { r -> rows += Row(r.name, MetaParse.typeLabel(r.catalog.type) + "  ►", opens = { row(keyOf(r), path) }) }
                if (rows.isEmpty()) rows += Row("No rows here. Move one here from a folder.")
                return rows
            }
            val n = Layout.find(Layout.tree(e, d), path) ?: return listOf(Row("This folder is empty now."))
            rows += Row("Folder: move, rename or empty it", "►", opens = { folderOptions(path) })
            rows += Row("New folder inside this one", "►", action = {
                ask("New folder", "") { name -> App.toast("Now move a row into \"$name\" to create it"); pendingFolder = Layout.join(Layout.parts(path) + name) }
            })
            rows += Row("────────────────────")
            n.folders.forEach { f -> rows += Row("▸ " + f.name, "${f.count}  ►", opens = { folder(f.path) }) }
            n.rows.forEach { r -> rows += Row(r.name, MetaParse.typeLabel(r.catalog.type) + "  ►", opens = { row(keyOf(r), path) }) }
            return rows
        }
        s = Screen(if (path == Layout.FRONT) "Front page" else Layout.parts(path).joinToString(" · "), build(), tag = "arrange")
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    /** A folder made with "New folder", offered first when moving a row. */
    private var pendingFolder: String? = null

    /** Items shown in [path] (rows and folders) in their current order. */
    private fun items(path: String): List<String> {
        val e = entries(); val d = layout()
        return if (path == Layout.FRONT) Layout.front(e, d).map { Layout.rowItem(keyOf(it)) }
        else Layout.find(Layout.tree(e, d), path)?.items ?: emptyList()
    }

    private fun mover(path: String, item: String, where: Row, title: String): List<Row> {
        fun place() {
            val l = items(path)
            where.label = "$title: position ${l.indexOf(item) + 1} of ${l.size}"
            App.refresh()
        }
        fun go(by: Int, edge: String) {
            val d = layout()
            if (Layout.shift(d, path, items(path), item, by)) save(d) else App.toast("Already at the $edge")
            place()
        }
        place()
        return listOf(
            Row("Move up", action = { go(-1, "top") }),
            Row("Move down", action = { go(1, "bottom") }),
            Row("Move to the top", action = { go(Int.MIN_VALUE, "top") }),
            Row("Move to the bottom", action = { go(Int.MAX_VALUE, "bottom") }),
        )
    }

    private fun row(key: String, path: String): Screen {
        val e = entries().firstOrNull { keyOf(it) == key }
        val name = e?.name ?: key
        val where = Row("")
        val s = Screen(name, emptyList())
        s.rows = listOf(where) + mover(path, Layout.rowItem(key), where, "In this folder") + listOf(
            Row("────────────────────"),
            Row("Move to another folder", "►", opens = { destinations(key, path) }),
            Row("Rename", "►", action = {
                ask("Rename row", name) { n ->
                    val d = layout(); Layout.rename(d, key, n); save(d)
                    App.toast("Renamed"); App.back()
                }
            }),
            Row("Hide it everywhere", action = { Prefs.hiddenRows = Prefs.hiddenRows + key; Prefs.syncOut(); Home.redraw(); App.toast("Hidden. Settings › Hide rows brings it back."); App.back() }),
            Row("Done", action = { App.back() }),
        )
        s.pos = 1
        return s
    }

    private fun destinations(key: String, from: String): Screen {
        val e = entries(); val d = layout()
        val here = if (from == Layout.FRONT) "" else from
        fun to(path: String, label: String) = Row(label, if (path == here) "here now" else "", action = {
            val dd = layout(); Layout.moveRow(dd, key, path); save(dd)
            pendingFolder = null
            App.toast("Moved to " + (if (path.isEmpty()) "the front page" else path.replace(" > ", " · ")))
            App.back(); App.back()
        })
        val rows = mutableListOf<Row>()
        pendingFolder?.let { rows += to(it, "▸ " + it.replace(" > ", " · ") + "  (new)") }
        rows += Row("New folder", "►", action = {
            ask("New folder", "") { n -> to(n, n).action?.invoke() }
        })
        rows += to("", "Front page")
        rows += Row("────────────────────")
        Layout.allPaths(Layout.tree(e, d)).forEach { p ->
            val depth = Layout.parts(p).size - 1
            rows += to(p, "  ".repeat(depth) + "▸ " + Layout.parts(p).last())
        }
        return Screen("Move to…", rows)
    }

    private fun folderOptions(path: String): Screen {
        val parent = Layout.join(Layout.parts(path).dropLast(1))
        val title = Layout.parts(path).last()
        val where = Row("")
        val s = Screen("Folder: $title", emptyList())
        s.rows = listOf(where) + mover(parent, Layout.folderItem(title), where, "Among its neighbours") + listOf(
            Row("────────────────────"),
            Row("Rename this folder", "►", action = {
                ask("Rename folder", title) { n ->
                    val d = layout(); Layout.renameFolder(d, entries(), path, n); save(d)
                    App.toast("Renamed"); App.back(); App.back()
                }
            }),
            Row("Move all its rows to the front page", action = {
                val d = layout(); Layout.emptyFolder(d, entries(), path, ""); save(d)
                App.toast("Folder emptied"); App.back(); App.back()
            }),
            Row("Done", action = { App.back() }),
        )
        s.pos = 1
        return s
    }

    private fun ask(title: String, initial: String, done: (String) -> Unit) {
        App.ask(title, initial) { v ->
            val t = v?.trim()?.replace(">", "›").orEmpty()
            if (t.isNotEmpty()) done(t)
        }
    }
}
