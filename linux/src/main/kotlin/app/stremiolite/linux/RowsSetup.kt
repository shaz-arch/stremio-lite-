package app.stremiolite.linux

import app.stremiolite.AioRows
import app.stremiolite.RowSetup
import app.stremiolite.Stremio

/**
 * Settings › Your rows: pick streaming services and categories from the full catalogue, either
 * switching them on and off in your AIOMetadata or creating a new AIOMetadata install with them.
 */
object RowsSetup {
    fun screen(): Screen {
        val aio = Home.all().filter { AioRows.isAio(it) }
        val stick = aio.any { it.stickRows }
        val rows = mutableListOf<Row>()
        if (aio.isNotEmpty()) {
            rows += Row("Categories and streaming services", "►", opens = {
                RowPicker.entry { _, l, pw -> checklist(RowSetup.picksFrom(l.catalogs), edit = l, password = pw) }
            })
            rows += Row("Switch single rows on or off", "►", opens = { RowPicker.entry() })
        }
        rows += Row("Arrange your Index (folders, names, order)", "►", opens = { Arrange.screen() })
        rows += Row("────────────────────")
        rows += Row(if (aio.isEmpty()) "Create your rows" else "Start again from the full catalogue", "►",
            opens = { checklist(RowSetup.defaults(), edit = null, password = null) })
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

    private fun checklist(p: RowSetup.Picks, edit: AioRows.Loaded?, password: String?): Screen {
        lateinit var s: Screen
        fun tick(on: Boolean) = if (on) "✓ " else "   "
        fun build(): List<Row> {
            val n = counts(p.region)
            val rows = mutableListOf<Row>()
            if (edit == null) rows += Row("Region", "◄ ${p.region.label} ►", action = {
                p.region = if (p.region == RowSetup.Region.UK) RowSetup.Region.US else RowSetup.Region.UK
                p.services.clear(); p.services += RowSetup.defaultServices(p.region)
                s.rows = build(); App.refresh()
            })
            else rows += Row("Region", p.region.label + " (set when created)")
            rows += Row("──── Streaming services (Top 10 and Streaming) ────")
            RowSetup.services(p.region).forEach { svc ->
                lateinit var r: Row
                r = Row(tick(svc in p.services) + svc, "", action = {
                    if (svc in p.services) p.services -= svc else p.services += svc
                    r.label = tick(svc in p.services) + svc; App.refresh()
                })
                rows += r
            }
            rows += Row("──── Categories ────")
            RowSetup.categories.forEach { c ->
                lateinit var r: Row
                r = Row(tick(c in p.categories) + c, "${n[c] ?: 0} rows", action = {
                    if (c in p.categories) p.categories -= c else p.categories += c
                    r.label = tick(c in p.categories) + c; App.refresh()
                })
                rows += r
            }
            rows += Row("────────────────────")
            rows += if (edit != null) Row("Save to AIOMetadata", "►", action = { save(edit, password!!, p) })
            else Row("Create my rows", "►", action = { askNewPassword(p) })
            return rows
        }
        s = Screen(if (edit != null) "Categories" else "Create your rows", emptyList())
        s.rows = build()
        return s
    }

    private fun save(l: AioRows.Loaded, password: String, p: RowSetup.Picks) {
        RowSetup.apply(l.catalogs, p)
        App.toast("Saving…")
        App.async({ AioRows.save(l, password) }) { r ->
            r.onSuccess {
                App.toast("Saved. Your rows reload on Home.")
                java.io.File(Store.dir, "addons.json").delete(); Home.forget(); Home.load(force = true)
                App.back()
            }.onFailure { e -> App.toast("Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))) }
        }
    }

    private fun askNewPassword(p: RowSetup.Picks) {
        App.ask("Choose an AIOMetadata password (6+ characters, to change rows later)") { v ->
            val pw = v.orEmpty()
            if (pw.isEmpty()) return@ask
            if (pw.length < 6) App.toast("Use at least 6 characters") else create(p, pw)
        }
    }

    private fun create(p: RowSetup.Picks, password: String) {
        val key = Account.authKey ?: return
        val status = Screen("Creating your rows", listOf(Row("Creating your AIOMetadata… (up to a minute)")))
        App.push(status)
        App.async({
            val made = RowSetup.create(p, password)
            Stremio.installAddon(key, made.installUrl)
            made
        }) { r ->
            r.onSuccess { made ->
                RowPicker.remember(made.installUrl, password)
                val stick = Home.all().any { it.stickRows }
                java.io.File(Store.dir, "addons.json").delete(); Home.forget(); Home.load(force = true)
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
            App.refresh()
        }
    }
}
