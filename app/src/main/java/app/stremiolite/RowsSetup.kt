package app.stremiolite

import android.text.InputType
import android.widget.EditText

/**
 * Settings › Your rows: pick streaming services and categories from the full catalogue, either
 * switching them on and off in your AIOMetadata or creating a new AIOMetadata install with them.
 */
object RowsSetup {
    fun screen(app: MainActivity): Screen {
        val aio = Home.allAddons().filter { AioRows.isAio(it) }
        val stick = aio.any { it.stickRows }
        val rows = mutableListOf<Row>()
        if (aio.isNotEmpty()) {
            rows += Row("Categories and streaming services", "►", opens = {
                RowPicker.entry(app) { _, l, pw -> checklist(app, RowSetup.picksFrom(l.catalogs), edit = l, password = pw) }
            })
            rows += Row("Switch single rows on or off", "►", opens = { RowPicker.entry(app) })
        }
        rows += Row("Arrange your Index (folders, names, order)", "►", opens = { Arrange.screen(app) })
        rows += Row("────────────────────")
        rows += Row(if (aio.isEmpty()) "Create your rows" else "Start again from the full catalogue", "►",
            opens = { checklist(app, RowSetup.defaults(), edit = null, password = null) })
        if (aio.isNotEmpty()) rows += Row("(makes a new AIOMetadata and replaces yours)")
        if (stick) rows += Row("You have a stick rows install: Stremonino shows its rows.")
        return Screen("Your rows", rows)
    }

    private fun counts(r: RowSetup.Region): Map<String, Int> {
        val all = RowSetup.forRegion(r)
        val m = HashMap<String, Int>()
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (o.optString("mergedInto").isNotBlank()) continue
            val c = RowSetup.group(o.optString("name")).first
            m[c] = (m[c] ?: 0) + 1
        }
        return m
    }

    /** The checklist. With [edit], it changes that install's rows; without, it creates a new install. */
    private fun checklist(app: MainActivity, p: RowSetup.Picks, edit: AioRows.Loaded?, password: String?): Screen {
        lateinit var s: Screen
        fun tick(on: Boolean) = if (on) "✓ " else "   "
        fun build(): List<Row> {
            val n = counts(p.region)
            val rows = mutableListOf<Row>()
            if (edit == null) rows += Row("Region", "◄ ${p.region.label} ►", action = {
                p.region = if (p.region == RowSetup.Region.UK) RowSetup.Region.US else RowSetup.Region.UK
                p.services.clear(); p.services += RowSetup.defaultServices(p.region)
                s.rows = build(); app.refresh(s)
            })
            else rows += Row("Region", p.region.label + " (set when created)")
            rows += Row("──── Streaming services (Top 10 and Streaming) ────")
            RowSetup.services(p.region).forEach { svc ->
                lateinit var r: Row
                r = Row(tick(svc in p.services) + svc, "", action = {
                    if (svc in p.services) p.services -= svc else p.services += svc
                    r.label = tick(svc in p.services) + svc; app.refresh(s)
                })
                rows += r
            }
            rows += Row("──── Categories ────")
            RowSetup.categories.forEach { c ->
                lateinit var r: Row
                r = Row(tick(c in p.categories) + c, "${n[c] ?: 0} rows", action = {
                    if (c in p.categories) p.categories -= c else p.categories += c
                    r.label = tick(c in p.categories) + c; app.refresh(s)
                })
                rows += r
            }
            rows += Row("────────────────────")
            rows += if (edit != null) Row("Save to AIOMetadata", "►", action = { save(app, edit, password!!, p) })
            else Row("Create my rows", "►", action = { askNewPassword(app, p) })
            return rows
        }
        s = Screen(if (edit != null) "Categories" else "Create your rows", emptyList())
        s.rows = build()
        return s
    }

    private fun save(app: MainActivity, l: AioRows.Loaded, password: String, p: RowSetup.Picks) {
        RowSetup.apply(l.catalogs, p)
        app.toast("Saving…")
        Net.async({ AioRows.save(l, password) }) { r ->
            r.onSuccess {
                app.toast("Saved. Your rows reload on Home.")
                Home.forget(); app.back()
            }.onFailure { e -> app.toast("Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))) }
        }
    }

    private fun askNewPassword(app: MainActivity, p: RowSetup.Picks) {
        val pw = EditText(app).apply {
            hint = "Choose a password (6+ characters)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
        }
        android.app.AlertDialog.Builder(app)
            .setTitle("AIOMetadata password")
            .setMessage("Used to change these rows later (here or on AIOMetadata's page). This device remembers it.")
            .setView(pw)
            .setPositiveButton("Create") { _, _ ->
                val v = pw.text.toString()
                if (v.length < 6) app.toast("Use at least 6 characters") else create(app, p, v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun create(app: MainActivity, p: RowSetup.Picks, password: String) {
        val key = Account.authKey(app) ?: return
        val status = Screen("Creating your rows", listOf(Row("Creating your AIOMetadata… (up to a minute)")))
        app.push(status)
        Net.async({
            val made = RowSetup.create(p, password)
            Stremio.installAddon(key, made.installUrl)
            made
        }) { r ->
            r.onSuccess { made ->
                RowPicker.remember(app, made.installUrl, password)
                val stick = Home.allAddons().any { it.stickRows }
                Home.forget()
                status.rows = listOfNotNull(
                    Row("Done. Your rows are on your Stremio account."),
                    Row("Every device signed in to it gets them."),
                    Row("Back on Home they load in a moment."),
                    if (stick) Row("You also have a stick rows install, which Stremonino shows instead: remove it in Your add-ons.") else null,
                    Row("Keep your password: you need it to change rows on another device."),
                )
            }.onFailure { e ->
                status.rows = listOf(Row("Couldn't create the rows: " + (if (e is AioRows.Failed) e.message else Net.describe(e))))
            }
            app.refresh(status)
        }
    }
}
