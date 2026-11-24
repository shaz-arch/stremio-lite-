package app.stremiolite

import android.widget.EditText

/**
 * Settings › Arrange your Index: move rows between folders (or onto the front page), make and
 * rename folders, rename rows and put everything in order. Saved with the shared settings,
 * so the stick, the phone and Linux all show the same Index.
 */
object Arrange {
    private fun entries(app: MainActivity) = Index.entries(app, Home.rowAddons())
    private fun keyOf(e: Folders.Entry) = Folders.rowKey(e.addon, e.catalog)
    private fun save(app: MainActivity, d: Layout.Data) { Settings.saveLayout(app, d); Home.redraw() }

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val e = entries(app)
            if (e.isEmpty()) return listOf(Row("Your rows are still loading. Go back and try again in a moment."))
            val d = Settings.layout(app)
            val root = Layout.tree(e, d)
            return listOf(
                Row("Choose a folder, then a row to move or rename it."),
                Row("Front page", "${Layout.front(e, d).size}  ►", opens = { folder(app, Layout.FRONT) }),
                Row("──── Index ────"),
            ) + root.folders.map { n -> Row(n.name, "${n.count}  ►", opens = { folder(app, n.path) }) } + listOf(
                Row("────────────────────"),
                Row("Back to the automatic Index", "►", opens = {
                    confirmScreen(app, "Reset the Index?", "Your folders, names and order go back to how the app files them. Hidden rows stay hidden.",
                        "Yes, reset it") {
                        val dd = Settings.layout(app); Layout.reset(dd); save(app, dd)
                        app.toast("The Index is back to automatic"); app.back()
                    }
                }),
            )
        }
        s = Screen("Arrange your Index", build())
        s.position = 1
        s.onShow = { s.rows = build(); app.refresh(s) }
        return s
    }

    /** A folder's sub-folders and rows; [path] is a folder path, or [Layout.FRONT]. */
    private fun folder(app: MainActivity, path: String): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val e = entries(app)
            val d = Settings.layout(app)
            val rows = mutableListOf<Row>()
            if (path == Layout.FRONT) {
                Layout.front(e, d).forEach { r -> rows += Row(r.name, Catalogs.typeLabel(r.catalog.type) + "  ►", opens = { row(app, keyOf(r), path) }) }
                if (rows.isEmpty()) rows += Row("No rows here. Move one here from a folder.")
                return rows
            }
            val n = Layout.find(Layout.tree(e, d), path) ?: return listOf(Row("This folder is empty now."))
            rows += Row("Folder: move, rename or empty it", "►", opens = { folderOptions(app, path) })
            rows += Row("New folder inside this one", "►", action = {
                ask(app, "New folder", "") { name -> app.toast("Now move a row into \"$name\" to create it"); pendingFolder = Layout.join(Layout.parts(path) + name) }
            })
            rows += Row("────────────────────")
            n.folders.forEach { f -> rows += Row("▸ " + f.name, "${f.count}  ►", opens = { folder(app, f.path) }) }
            n.rows.forEach { r -> rows += Row(r.name, Catalogs.typeLabel(r.catalog.type) + "  ►", opens = { row(app, keyOf(r), path) }) }
            return rows
        }
        s = Screen(if (path == Layout.FRONT) "Front page" else Layout.parts(path).joinToString(" · "), build(), tag = "arrange")
        s.onShow = { s.rows = build(); app.refresh(s) }
        return s
    }

    /** A folder made with "New folder", offered first when moving a row. */
    private var pendingFolder: String? = null

    /** Items shown in [path] (rows and folders) in their current order. */
    private fun items(app: MainActivity, path: String): List<String> {
        val e = entries(app); val d = Settings.layout(app)
        return if (path == Layout.FRONT) Layout.front(e, d).map { Layout.rowItem(keyOf(it)) }
        else Layout.find(Layout.tree(e, d), path)?.items ?: emptyList()
    }

    private fun mover(app: MainActivity, path: String, item: String, where: Row, title: String): List<Row> {
        fun place() {
            val l = items(app, path)
            where.label = "$title: position ${l.indexOf(item) + 1} of ${l.size}"
            app.refreshTop()
        }
        fun go(by: Int, edge: String) {
            val d = Settings.layout(app)
            if (Layout.shift(d, path, items(app, path), item, by)) save(app, d) else app.toast("Already at the $edge")
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

    private fun row(app: MainActivity, key: String, path: String): Screen {
        val e = entries(app).firstOrNull { keyOf(it) == key }
        val name = e?.name ?: key
        val where = Row("")
        val s = Screen(name, emptyList())
        s.rows = listOf(where) + mover(app, path, Layout.rowItem(key), where, "In this folder") + listOf(
            Row("────────────────────"),
            Row("Move to another folder", "►", opens = { destinations(app, key, path) }),
            Row("Rename", "►", action = {
                ask(app, "Rename row", name) { n ->
                    val d = Settings.layout(app); Layout.rename(d, key, n); save(app, d)
                    app.toast("Renamed"); app.back()
                }
            }),
            Row("Hide it everywhere", action = { Settings.setHidden(app, key, true); Home.redraw(); app.toast("Hidden. Settings › Hide rows brings it back."); app.back() }),
            Row("Done", action = { app.back() }),
        )
        s.position = 1
        return s
    }

    private fun destinations(app: MainActivity, key: String, from: String): Screen {
        val e = entries(app); val d = Settings.layout(app)
        val here = if (from == Layout.FRONT) "" else from
        fun to(path: String, label: String) = Row(label, if (path == here) "here now" else "", action = {
            val dd = Settings.layout(app); Layout.moveRow(dd, entries(app), key, path); save(app, dd)
            pendingFolder = null
            app.toast("Moved to " + (if (path.isEmpty()) "the front page" else path.replace(" > ", " · ")))
            app.back(); app.back()
        })
        val rows = mutableListOf<Row>()
        pendingFolder?.let { rows += to(it, "▸ " + it.replace(" > ", " · ") + "  (new)") }
        rows += Row("New folder", "►", action = {
            ask(app, "New folder", "") { n -> to(n, n).action?.invoke() }
        })
        rows += to("", "Front page")
        rows += Row("────────────────────")
        Layout.allPaths(Layout.tree(e, d)).forEach { p ->
            val depth = Layout.parts(p).size - 1
            rows += to(p, "  ".repeat(depth) + "▸ " + Layout.parts(p).last())
        }
        return Screen("Move to…", rows)
    }

    private fun folderOptions(app: MainActivity, path: String): Screen {
        val parent = Layout.join(Layout.parts(path).dropLast(1))
        val title = Layout.parts(path).last()
        val where = Row("")
        val s = Screen("Folder: $title", emptyList())
        s.rows = listOf(where) + mover(app, parent, Layout.folderItem(title), where, "Among its neighbours") + listOf(
            Row("────────────────────"),
            Row("Rename this folder", "►", action = {
                ask(app, "Rename folder", title) { n ->
                    val d = Settings.layout(app); Layout.renameFolder(d, entries(app), path, n); save(app, d)
                    app.toast("Renamed"); app.back(); app.back()
                }
            }),
            Row("Move all its rows to the front page", action = {
                val d = Settings.layout(app); Layout.emptyFolder(d, entries(app), path, ""); save(app, d)
                app.toast("Folder emptied"); app.back(); app.back()
            }),
            Row("Done", action = { app.back() }),
        )
        s.position = 1
        return s
    }

    private fun ask(app: MainActivity, title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(app).apply { setText(initial); setSingleLine(); setSelection(initial.length) }
        android.app.AlertDialog.Builder(app)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().trim().replace(">", "›")
                if (v.isNotEmpty()) done(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
