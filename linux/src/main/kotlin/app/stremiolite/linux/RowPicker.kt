package app.stremiolite.linux

import app.stremiolite.Addon
import app.stremiolite.AioRows

/**
 * Settings › Choose your rows: switch AIOMetadata's rows off, on, or on the home screen, and put
 * them in order, then save them back to AIOMetadata. Every device (and Stremio) sees the result.
 */
object RowPicker {
    private const val FILE = "aio-passwords.json"
    private fun keyOf(i: AioRows.Install) = i.server + "/" + i.id

    fun entry(): Screen {
        val list = Home.all().filter { AioRows.isAio(it) }
        if (list.isEmpty()) return Screen("Choose your rows", listOf(
            Row("No AIOMetadata add-on found on this account."),
            Row("If Home is still loading, go back and try again."),
        ))
        if (list.size == 1) return start(list[0])
        return Screen("Choose your rows", listOf(Row("Which rows do you want to change?"), Row("")) + list.map { a ->
            Row(if (a.stickRows) "Stremonino's rows" else if (list.any { it.stickRows }) "Stremio's rows" else a.name.substringBefore('|').trim(),
                (if (a.stickRows) "stick rows install" else a.name.substringBefore('|').trim()) + "  ►",
                opens = { start(a) })
        })
    }

    private fun start(a: Addon): Screen {
        val inst = AioRows.install(a) ?: return Screen("Choose your rows", listOf(Row("Couldn't read this add-on's install link.")))
        val status = Screen("Choose your rows", listOf(Row("Loading your AIOMetadata settings…")))
        val saved = Store.readJson(FILE).optString(keyOf(inst)).takeIf { it.isNotEmpty() }
        status.onShow = {
            status.onShow = null
            if (saved != null) load(status, a, inst, saved, remember = true) else askPassword(status, a, inst)
        }
        return status
    }

    private fun askPassword(status: Screen, a: Addon, inst: AioRows.Install) {
        App.ask("AIOMetadata password (as on its settings page)", hidden = true) { pw ->
            if (pw.isNullOrEmpty()) { App.back(); return@ask }
            App.ask("Remember it on this computer? (y/n)", "y") { yn ->
                load(status, a, inst, pw, remember = yn?.trim()?.lowercase()?.startsWith("y") == true)
            }
        }
    }

    private fun load(status: Screen, a: Addon, inst: AioRows.Install, password: String, remember: Boolean) {
        status.rows = listOf(Row("Loading your AIOMetadata settings…"))
        App.refresh()
        App.async({ AioRows.load(inst, password) }) { r ->
            r.onSuccess { l ->
                if (remember) Store.writeJson(FILE, Store.readJson(FILE).put(keyOf(inst), password))
                if (App.isTop(status)) App.replaceTop(picker(a, l, password)) else App.push(picker(a, l, password))
            }.onFailure { e ->
                if (e is AioRows.Failed && e.message == "wrong password") Store.writeJson(FILE, Store.readJson(FILE).apply { remove(keyOf(inst)) })
                status.rows = listOf(
                    Row("Couldn't open the settings: " + (if (e is AioRows.Failed) e.message else Net.describe(e))),
                    Row("Try again", action = { askPassword(status, a, inst) }),
                )
                App.refresh()
            }
        }
    }

    /** "Genres > Movies: Horror" shows as "Movies: Horror" under its Genres heading. */
    private fun short(item: AioRows.Item): String {
        val n = item.name
        val i = n.indexOf(" > ")
        return if (i in 1..24) n.substring(i + 3) else app.stremiolite.Folders.split(n).let { (p, r) -> if (p != null) r else n }
    }

    private fun mark(s: AioRows.State) = when (s) { AioRows.State.OFF -> "   "; AioRows.State.ON -> "✓  "; AioRows.State.HOME -> "✓⌂ " }
    private fun onOrder(l: AioRows.Loaded) = AioRows.items(l).filter { it.state != AioRows.State.OFF }.joinToString(",") { it.id }

    private fun picker(a: Addon, l: AioRows.Loaded, password: String): Screen {
        val start = AioRows.signature(l) + "#" + onOrder(l)
        var saved = false
        lateinit var screen: Screen
        lateinit var saveRow: Row
        fun changed() = AioRows.signature(l) + "#" + onOrder(l) != start
        fun counts(): String {
            val all = AioRows.items(l)
            return "${all.count { it.state != AioRows.State.OFF }} on · ${all.count { it.state == AioRows.State.HOME }} home"
        }
        val rows = mutableListOf<Row>()
        saveRow = Row("Save to AIOMetadata", "", action = {
            App.toast("Saving…")
            App.async({ AioRows.save(l, password) }) { r ->
                r.onSuccess {
                    saved = true
                    App.toast("Saved. Your rows reload on Home; Stremio picks them up when it next loads.")
                    java.io.File(Store.dir, "addons.json").delete(); Home.forget(); Home.load(force = true)
                    App.back()
                }.onFailure { e -> App.toast("Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))) }
            }
        })
        rows += saveRow
        rows += Row("Put the rows in order", "►", opens = { order(l) })
        rows += Row("Enter: off → on → on home → off.  ⌂ = on the home screen")
        AioRows.items(l).groupBy { AioRows.folder(it) }.forEach { (folder, items) ->
            rows += Row("──── $folder ────")
            items.forEach { item ->
                lateinit var row: Row
                row = Row(mark(item.state) + short(item), item.typeLabel + " · " + item.state.label, action = {
                    item.state = item.next()
                    row.label = mark(item.state) + short(item)
                    row.detail = item.typeLabel + " · " + item.state.label
                    saveRow.detail = if (changed()) "unsaved" else ""
                    screen.tag = counts()
                    App.refresh()
                })
                rows += row
            }
        }
        screen = Screen(if (a.stickRows) "Stremonino's rows" else "Your rows", rows, tag = counts())
        screen.onShow = { screen.tag = counts(); saveRow.detail = if (changed()) "unsaved" else ""; App.refresh() }
        screen.onHide = { if (changed() && !saved && !App.inStack(screen)) App.toast("Your row changes weren't saved") }
        return screen
    }

    private fun order(l: AioRows.Loaded): Screen {
        lateinit var s: Screen
        fun build() = AioRows.items(l).filter { it.state != AioRows.State.OFF }.mapIndexed { i, item ->
            Row("${(i + 1).toString().padStart(3)}  ${mark(item.state)}${item.name}", item.typeLabel, opens = { mover(l, item) })
        }
        s = Screen("Row order", build(), tag = "⌂ = home screen")
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    private fun mover(l: AioRows.Loaded, item: AioRows.Item): Screen {
        val where = Row("")
        fun place() {
            val on = AioRows.items(l).filter { it.state != AioRows.State.OFF }
            where.label = "Position ${on.indexOfFirst { it.obj === item.obj } + 1} of ${on.size}"
            App.refresh()
        }
        val s = Screen(item.name, listOf(
            where,
            Row("Move up", action = { if (!AioRows.move(l, item, -1)) App.toast("Already at the top"); place() }),
            Row("Move down", action = { if (!AioRows.move(l, item, +1)) App.toast("Already at the bottom"); place() }),
            Row("Move to the top", action = { AioRows.moveToEnd(l, item, top = true); place() }),
            Row("Move to the bottom", action = { AioRows.moveToEnd(l, item, top = false); place() }),
            Row("Done", action = { App.back() }),
        ))
        s.pos = 1
        s.onShow = { place() }
        return s
    }
}
