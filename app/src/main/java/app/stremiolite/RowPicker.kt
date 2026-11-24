package app.stremiolite

import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Settings › Choose your rows: switch AIOMetadata's rows off, on, or on the home screen, and put
 * them in order, then save them back to AIOMetadata. Every device (and Stremio) sees the result.
 */
object RowPicker {
    private fun prefs(app: MainActivity) = Profiles.prefsFor(app, "aio")

    /** Remembers a password on this device for the install with this link (after creating it). */
    fun remember(app: MainActivity, installUrl: String, password: String) {
        val m = Regex("""^(https?://[^/]+(?:/.*?)?)/stremio/([^/]+)/manifest\.json$""").find(installUrl) ?: return
        prefs(app).edit().putString("pw:" + m.groupValues[1] + "/" + m.groupValues[2], password).apply()
    }

    fun entry(app: MainActivity, then: ((Addon, AioRows.Loaded, String) -> Screen)? = null): Screen {
        val list = Home.allAddons().filter { AioRows.isAio(it) }
        if (list.isEmpty()) return Screen("Choose your rows", listOf(
            Row("No AIOMetadata add-on found on this account."),
            Row("If Home is still loading, go back and try again."),
        ))
        if (list.size == 1) return start(app, list[0], then)
        return Screen("Choose your rows", listOf(Row("Which rows do you want to change?"), Row("")) + list.map { a ->
            Row(if (a.stickRows) "Stremonino's rows" else if (list.any { it.stickRows }) "Stremio's rows" else a.name.substringBefore('|').trim(),
                (if (a.stickRows) "stick rows install" else a.name.substringBefore('|').trim()) + "  ►",
                opens = { start(app, a, then) })
        })
    }

    /** Asks for the password (unless this device remembers it), then loads. */
    private fun start(app: MainActivity, a: Addon, then: ((Addon, AioRows.Loaded, String) -> Screen)?): Screen {
        val inst = AioRows.install(a) ?: return Screen("Choose your rows", listOf(Row("Couldn't read this add-on's install link.")))
        val status = Screen("Choose your rows", listOf(Row("Loading your AIOMetadata settings…")))
        val saved = prefs(app).getString("pw:" + inst.server + "/" + inst.id, null)
        status.onShow = {
            if (status.rows.size == 1 && status.rows[0].label.startsWith("Loading")) {
                status.onShow = null
                if (saved != null) load(app, status, a, inst, saved, true, then)
                else askPassword(app, status, a, inst, then)
            }
        }
        return status
    }

    private fun askPassword(app: MainActivity, status: Screen, a: Addon, inst: AioRows.Install, then: ((Addon, AioRows.Loaded, String) -> Screen)?) {
        val pw = EditText(app).apply {
            hint = "AIOMetadata password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val keep = CheckBox(app).apply { text = "Remember on this device"; isChecked = true }
        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(pw); addView(keep)
        }
        android.app.AlertDialog.Builder(app)
            .setTitle(if (a.stickRows) "Stremonino's rows" else "Stremio's rows")
            .setMessage("The password you use on AIOMetadata's settings page (Load Configuration).")
            .setView(box)
            .setPositiveButton("Open") { _, _ ->
                val p = pw.text.toString()
                if (p.isEmpty()) app.back() else load(app, status, a, inst, p, keep.isChecked, then)
            }
            .setNegativeButton("Cancel") { _, _ -> app.back() }
            .setOnCancelListener { app.back() }
            .show()
    }

    private fun load(app: MainActivity, status: Screen, a: Addon, inst: AioRows.Install, password: String, remember: Boolean, then: ((Addon, AioRows.Loaded, String) -> Screen)?) {
        status.rows = listOf(Row("Loading your AIOMetadata settings…"))
        app.refresh(status)
        val key = "pw:" + inst.server + "/" + inst.id
        Net.async({ AioRows.load(inst, password) }) { r ->
            r.onSuccess { l ->
                if (remember) prefs(app).edit().putString(key, password).apply()
                val next = then?.invoke(a, l, password) ?: picker(app, a, l, password)
                if (app.isTop(status)) app.replaceTop(next) else app.push(next)
            }.onFailure { e ->
                if (e is AioRows.Failed && e.message == "wrong password") prefs(app).edit().remove(key).apply()
                status.rows = listOf(
                    Row("Couldn't open the settings: " + (if (e is AioRows.Failed) e.message else Net.describe(e))),
                    Row("Try again", action = { askPassword(app, status, a, inst, then) }),
                )
                app.refresh(status)
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

    private fun picker(app: MainActivity, a: Addon, l: AioRows.Loaded, password: String): Screen {
        val start = AioRows.signature(l) + "#" + order(l)
        var saved = false
        lateinit var screen: Screen
        lateinit var saveRow: Row
        fun changed() = AioRows.signature(l) + "#" + order(l) != start
        fun counts(): String {
            val all = AioRows.items(l)
            return "${all.count { it.state != AioRows.State.OFF }} on · ${all.count { it.state == AioRows.State.HOME }} on home"
        }
        fun build(): List<Row> {
            val rows = mutableListOf<Row>()
            saveRow = Row("Save to AIOMetadata", "", action = { save(app, l, password) { saved = true } })
            rows += saveRow
            rows += Row("Put the rows in order", "►", opens = { order(app, l) })
            rows += Row("OK: off → on → on home → off.  ⌂ = on the home screen")
            AioRows.items(l).groupBy { AioRows.folder(it) }.forEach { (folder, items) ->
                rows += Row("──── $folder ────")
                items.forEach { item ->
                    lateinit var row: Row
                    row = Row(mark(item.state) + short(item), item.typeLabel + " · " + item.state.label, action = {
                        item.state = item.next()
                        row.label = mark(item.state) + short(item)
                        row.detail = item.typeLabel + " · " + item.state.label
                        saveRow.detail = if (changed()) "unsaved  ►" else ""
                        app.refresh(screen)
                    })
                    rows += row
                }
            }
            saveRow.detail = if (changed()) "unsaved  ►" else ""
            return rows
        }
        screen = Screen(if (a.stickRows) "Stremonino's rows" else "Your rows", build(), tag = counts())
        screen.onShow = { screen.tag = counts(); saveRow.detail = if (changed()) "unsaved  ►" else ""; app.refresh(screen) }
        // Leaving with changes not saved: say so, since nothing reaches AIOMetadata until Save.
        screen.onHide = { if (changed() && !saved && !app.inStack(screen)) app.toast("Your row changes weren't saved") }
        return screen
    }

    /** The rows that are on, in order: pick one up and drop it where it goes. */
    private fun order(app: MainActivity, l: AioRows.Loaded): Screen {
        fun on() = AioRows.items(l).filter { it.state != AioRows.State.OFF }.toMutableList()
        val r = Reorder(app,
            head = { listOf(Row("⌂ = on Stremio's home screen. Save on the last screen.")) },
            items = on(),
            label = { mark(it.state) + it.name },
            detail = { it.typeLabel },
            open = { null },
            commit = { list -> AioRows.setOrder(l, list) },
        )
        return r.build("Row order")
    }

    private fun order(l: AioRows.Loaded) = AioRows.items(l).filter { it.state != AioRows.State.OFF }.joinToString(",") { it.id }

    private fun save(app: MainActivity, l: AioRows.Loaded, password: String, done: () -> Unit) {
        app.toast("Saving…")
        Net.async({ AioRows.save(l, password) }) { r ->
            r.onSuccess {
                done()
                app.toast("Saved. Your rows will reload on Home; Stremio picks them up when it next loads.")
                Home.forget()
                app.back()
            }.onFailure { e -> app.toast("Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))) }
        }
    }
}
