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
        var hidden = emptySet<String>()
        fun tree(): Layout.Node { val d = layout(); hidden = d.hidden.toSet(); return Layout.tree(entries(), d, withHidden = true) }
        lateinit var r: Reorder<Layout.Node>
        r = Reorder(
            head = {
                val e = entries()
                if (e.isEmpty()) listOf(Row("Your rows are still loading. Go back and try again in a moment."))
                else listOf(
                    Row("Front page", "${Layout.front(e, layout()).size}  ►", opens = { folder(Layout.FRONT) }),
                    Row("Back to the automatic Index", "►", opens = {
                        confirmScreen("Reset the Index?", "Your folders, names and order go back to how the app files them. Hidden rows stay hidden.",
                            "Yes, reset it") {
                            val dd = layout(); Layout.reset(dd); save(dd)
                            App.toast("The Index is back to automatic"); App.back()
                        }
                    }),
                    Row("──── Index folders ────"),
                )
            },
            items = tree().folders.toMutableList(),
            label = { it.name },
            detail = { n -> (if (n.path in hidden) "hidden · " else "") + "${n.count}  ►" },
            open = { n -> folder(n.path) },
            commit = { list ->
                val dd = layout(); dd.order[""] = list.map { Layout.folderItem(it.name) }.toMutableList(); save(dd)
            },
        )
        val s = r.build("Arrange your Index")
        s.onShow = { r.items = tree().folders.toMutableList(); r.redraw() }
        return s
    }

    /** A folder's sub-folders and rows; [path] is a folder path, or [Layout.FRONT]. */
    private fun folder(path: String): Screen {
        val front = path == Layout.FRONT
        // Worked out once per redraw: labels are looked up, not recomputed for every row.
        var names = emptyMap<String, String>()
        var details = emptyMap<String, String>()
        fun current(): MutableList<String> {
            val e = entries(); val d = layout()
            val nm = HashMap<String, String>(); val dt = HashMap<String, String>()
            val list: List<String> = if (front) {
                Layout.front(e, d).map { en -> Layout.rowItem(keyOf(en)).also { nm[it] = en.name; dt[it] = MetaParse.typeLabel(en.catalog.type) + "  ►" } }
            } else {
                val n = Layout.find(Layout.tree(e, d, withHidden = true), path)
                n?.folders?.forEach { f -> val k = Layout.folderItem(f.name); nm[k] = "▸ " + f.name; dt[k] = (if (f.path in d.hidden) "hidden · " else "") + "${f.count}  ►" }
                n?.rows?.forEach { en -> val k = Layout.rowItem(keyOf(en)); nm[k] = en.name; dt[k] = MetaParse.typeLabel(en.catalog.type) + "  ►" }
                n?.items ?: emptyList()
            }
            names = nm; details = dt
            return list.toMutableList()
        }
        lateinit var r: Reorder<String>
        r = Reorder(
            head = {
                if (front) emptyList() else listOf(
                    Row("Folder options: rename, hide, empty", "►", opens = { folderOptions(path) }),
                    Row("New folder inside this one", "►", action = {
                        ask("New folder", "") { name -> App.toast("Now move a row into \"$name\" to create it"); pendingFolder = Layout.join(Layout.parts(path) + name) }
                    }),
                )
            },
            items = current(),
            label = { item -> names[item] ?: item.substring(2) },
            detail = { item -> details[item] ?: "►" },
            open = { item ->
                if (item.startsWith("f:")) folder(Layout.join(Layout.parts(path) + item.removePrefix("f:")))
                else row(item.removePrefix("r:"), path)
            },
            commit = { list -> val dd = layout(); dd.order[path] = list.toMutableList(); save(dd) },
            kindOf = { if (it.startsWith("f:")) 0 else 1 },
        )
        val s = r.build(if (front) "Front page" else Layout.parts(path).joinToString(" · "), "arrange")
        s.onShow = { r.items = current(); r.redraw() }
        return s
    }

    /** A folder made with "New folder", offered first when moving a row. */
    private var pendingFolder: String? = null

    private fun row(key: String, path: String): Screen {
        val e = entries().firstOrNull { keyOf(it) == key }
        val name = e?.name ?: key
        return Screen(name, listOf(
            Row("Move to another folder", "►", opens = { destinations(key, path) }),
            Row("Rename", "►", action = {
                ask("Rename row", name) { n ->
                    val d = layout(); Layout.rename(d, key, n); save(d)
                    App.toast("Renamed"); App.back()
                }
            }),
            Row("Hide it everywhere", action = { Prefs.hiddenRows = Prefs.hiddenRows + key; Prefs.syncOut(); Home.redraw(); App.toast("Hidden. Settings › Hide rows brings it back."); App.back() }),
            Row("Done", action = { App.back() }),
        ))
    }

    private fun destinations(key: String, from: String): Screen {
        val e = entries(); val d = layout()
        val here = if (from == Layout.FRONT) "" else from
        fun to(path: String, label: String) = Row(label, if (path == here) "here now" else "", action = {
            val dd = layout(); Layout.moveRow(dd, entries(), key, path); save(dd)
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
        Layout.allPaths(Layout.tree(e, d, withHidden = true)).forEach { p ->
            val depth = Layout.parts(p).size - 1
            rows += to(p, "  ".repeat(depth) + "▸ " + Layout.parts(p).last())
        }
        return Screen("Move to…", rows)
    }

    private fun folderOptions(path: String): Screen {
        val title = Layout.parts(path).last()
        val s = Screen("Folder: $title", emptyList())
        s.rows = listOf(
            Row("Rename this folder", "►", action = {
                ask("Rename folder", title) { n ->
                    val d = layout(); Layout.renameFolder(d, entries(), path, n); save(d)
                    App.toast("Renamed"); App.back(); App.back()
                }
            }),
            Row(if (layout().hidden.contains(path)) "Show this folder again" else "Hide this whole folder", action = {
                val d = layout(); val hide = path !in d.hidden; Layout.hideFolder(d, path, hide); save(d)
                App.toast(if (hide) "Folder hidden. It stays here so you can show it again." else "Folder shown again"); App.back()
            }),
            Row("Move all its rows to the front page", action = {
                val d = layout(); Layout.emptyFolder(d, entries(), path, ""); save(d)
                App.toast("Folder emptied"); App.back(); App.back()
            }),
            Row("Done", action = { App.back() }),
        )
        return s
    }

    private fun ask(title: String, initial: String, done: (String) -> Unit) {
        App.ask(title, initial) { v ->
            val t = v?.trim()?.replace(">", "›").orEmpty()
            if (t.isNotEmpty()) done(t)
        }
    }
}
