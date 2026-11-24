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
        var hidden = emptySet<String>()
        fun tree(): Layout.Node { val d = Settings.layout(app); hidden = d.hidden.toSet(); return Layout.tree(entries(app), d, withHidden = true) }
        lateinit var r: Reorder<Layout.Node>
        r = Reorder(app,
            head = {
                val e = entries(app)
                if (e.isEmpty()) listOf(Row("Your rows are still loading. Go back and try again in a moment."))
                else listOf(
                    Row("Front page", "${Layout.front(e, Settings.layout(app)).size}  ►", opens = { folder(app, Layout.FRONT) }),
                    Row("Back to the automatic Index", "►", opens = {
                        confirmScreen(app, "Reset the Index?", "Your folders, names and order go back to how the app files them. Hidden rows stay hidden.",
                            "Yes, reset it") {
                            val dd = Settings.layout(app); Layout.reset(dd); save(app, dd)
                            app.toast("The Index is back to automatic"); app.back()
                        }
                    }),
                    Row("──── Index folders ────"),
                )
            },
            items = tree().folders.toMutableList(),
            label = { it.name },
            detail = { n -> (if (n.path in hidden) "hidden · " else "") + "${n.count}  ►" },
            open = { n -> folder(app, n.path) },
            commit = { list ->
                val dd = Settings.layout(app); dd.order[""] = list.map { Layout.folderItem(it.name) }.toMutableList(); save(app, dd)
            },
        )
        val s = r.build("Arrange your Index")
        s.onShow = { r.items = tree().folders.toMutableList(); r.redraw() }
        return s
    }

    /** A folder's sub-folders and rows; [path] is a folder path, or [Layout.FRONT]. */
    private fun folder(app: MainActivity, path: String): Screen {
        val front = path == Layout.FRONT
        // Worked out once per redraw: labels are looked up, not recomputed for every row.
        var names = emptyMap<String, String>()
        var details = emptyMap<String, String>()
        fun current(): MutableList<String> {
            val e = entries(app); val d = Settings.layout(app)
            val nm = HashMap<String, String>(); val dt = HashMap<String, String>()
            val list: List<String> = if (front) {
                Layout.front(e, d).map { en -> Layout.rowItem(keyOf(en)).also { nm[it] = en.name; dt[it] = Catalogs.typeLabel(en.catalog.type) + "  ►" } }
            } else {
                val n = Layout.find(Layout.tree(e, d, withHidden = true), path)
                n?.folders?.forEach { f -> val k = Layout.folderItem(f.name); nm[k] = "▸ " + f.name; dt[k] = (if (f.path in d.hidden) "hidden · " else "") + "${f.count}  ►" }
                n?.rows?.forEach { en -> val k = Layout.rowItem(keyOf(en)); nm[k] = en.name; dt[k] = Catalogs.typeLabel(en.catalog.type) + "  ►" }
                n?.items ?: emptyList()
            }
            names = nm; details = dt
            return list.toMutableList()
        }
        lateinit var r: Reorder<String>
        r = Reorder(app,
            head = {
                if (front) emptyList() else listOf(
                    Row("Folder options: rename, hide, empty", "►", opens = { folderOptions(app, path) }),
                    Row("New folder inside this one", "►", action = {
                        ask(app, "New folder", "") { name -> app.toast("Now move a row into \"$name\" to create it"); pendingFolder = Layout.join(Layout.parts(path) + name) }
                    }),
                )
            },
            items = current(),
            label = { item -> names[item] ?: item.substring(2) },
            detail = { item -> details[item] ?: "►" },
            open = { item ->
                if (item.startsWith("f:")) folder(app, Layout.join(Layout.parts(path) + item.removePrefix("f:")))
                else row(app, item.removePrefix("r:"), path)
            },
            commit = { list -> val dd = Settings.layout(app); dd.order[path] = list.toMutableList(); save(app, dd) },
            kindOf = { if (it.startsWith("f:")) 0 else 1 },
        )
        val s = r.build(if (front) "Front page" else Layout.parts(path).joinToString(" · "), "arrange")
        s.onShow = { r.items = current(); r.redraw() }
        return s
    }

    /** A folder made with "New folder", offered first when moving a row. */
    private var pendingFolder: String? = null

    private fun row(app: MainActivity, key: String, path: String): Screen {
        val e = entries(app).firstOrNull { keyOf(it) == key }
        val name = e?.name ?: key
        return Screen(name, listOf(
            Row("Move to another folder", "►", opens = { destinations(app, key, path) }),
            Row("Rename", "►", action = {
                ask(app, "Rename row", name) { n ->
                    val d = Settings.layout(app); Layout.rename(d, key, n); save(app, d)
                    app.toast("Renamed"); app.back()
                }
            }),
            Row("Hide it everywhere", action = { Settings.setHidden(app, key, true); Home.redraw(); app.toast("Hidden. Settings › Hide rows brings it back."); app.back() }),
            Row("Done", action = { app.back() }),
        ))
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
        Layout.allPaths(Layout.tree(e, d, withHidden = true)).forEach { p ->
            val depth = Layout.parts(p).size - 1
            rows += to(p, "  ".repeat(depth) + "▸ " + Layout.parts(p).last())
        }
        return Screen("Move to…", rows)
    }

    private fun folderOptions(app: MainActivity, path: String): Screen {
        val title = Layout.parts(path).last()
        val s = Screen("Folder: $title", emptyList())
        s.rows = listOf(
            Row("Rename this folder", "►", action = {
                ask(app, "Rename folder", title) { n ->
                    val d = Settings.layout(app); Layout.renameFolder(d, entries(app), path, n); save(app, d)
                    app.toast("Renamed"); app.back(); app.back()
                }
            }),
            Row(if (Settings.layout(app).hidden.contains(path)) "Show this folder again" else "Hide this whole folder", action = {
                val d = Settings.layout(app); val hide = path !in d.hidden; Layout.hideFolder(d, path, hide); save(app, d)
                app.toast(if (hide) "Folder hidden. It stays here so you can show it again." else "Folder shown again"); app.back()
            }),
            Row("Move all its rows to the front page", action = {
                val d = Settings.layout(app); Layout.emptyFolder(d, entries(app), path, ""); save(app, d)
                app.toast("Folder emptied"); app.back(); app.back()
            }),
            Row("Done", action = { app.back() }),
        )
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
