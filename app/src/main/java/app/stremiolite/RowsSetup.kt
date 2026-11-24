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
                RowPicker.entry(app) { _, l, pw -> checklist(app, RowSetup.picksFrom(l.config), edit = l, password = pw) }
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


    /** The checklist. With [edit], it changes that install's rows; without, it creates a new install. */
    private fun checklist(app: MainActivity, p: RowSetup.Picks, edit: AioRows.Loaded?, password: String?): Screen {
        lateinit var s: Screen
        fun tick(on: Boolean) = if (on) "✓ " else "   "
        fun build(): List<Row> {
            val rows = mutableListOf<Row>()
            if (edit == null) rows += Row("Region", "◄ ${p.region.label} ►", action = {
                p.region = if (p.region == RowSetup.Region.UK) RowSetup.Region.US else RowSetup.Region.UK
                p.services.clear(); p.services += RowSetup.defaultServices(p.region)
                s.rows = build(); app.refresh(s)
            })
            else rows += Row("Region", p.region.label + " (set when created)")
            rows += Row("Size", "◄ ${p.size.label} ►   about ${RowSetup.count(p)} rows" + (if (RowSetup.count(p) > RowSetup.SERVER_LIMIT) " (over 200!)" else ""), action = {
                val sizes = RowSetup.Size.values()
                RowSetup.resize(p, sizes[(p.size.ordinal + 1) % sizes.size])
                s.rows = build(); app.refresh(s)
            })
            rows += Row("──── Streaming services (Top 10 and Streaming) ────")
            RowSetup.services(p.region).forEach { svc ->
                lateinit var r: Row
                r = Row(tick(svc in p.services) + svc, "", action = {
                    if (svc in p.services) p.services -= svc else p.services += svc
                    s.rows = build(); app.refresh(s)
                })
                rows += r
            }
            rows += Row("──── Categories ────")
            RowSetup.categories.forEach { c ->
                lateinit var r: Row
                r = Row(tick(c in p.categories) + c, "${RowSetup.countIn(p, c)} rows", action = {
                    if (c in p.categories) p.categories -= c else p.categories += c
                    s.rows = build(); app.refresh(s)
                })
                rows += r
            }
            rows += Row("──── Optional keys ────")
            rows += Row("MDBList key", (if (p.mdblistKey.isNotBlank()) "set ✓" else "adds ${RowSetup.mdblistCount(p)} rows") + "  ►", opens = {
                keyScreen(app, "MDBList key", RowSetup.MDBLIST_KEY_PAGE, listOf(
                    "Free account at mdblist.com, then Preferences › API key.",
                    "Adds MDBList rows: Top 100 Today, IMDb Top 250, Certified Fresh,",
                    "Trending Kids, Bollywood releases and a few genre lists.",
                ), { p.mdblistKey }, { p.mdblistKey = it })
            })
            rows += Row("Your own TMDB key", (if (p.tmdbKey.isNotBlank()) "set ✓" else "not needed") + "  ►", opens = {
                keyScreen(app, "TMDB key", RowSetup.TMDB_KEY_PAGE, listOf(
                    "Only needed if the server asks for one.",
                    "Free account at themoviedb.org, then Settings › API › API Key.",
                ), { p.tmdbKey }, { p.tmdbKey = it })
            })
            rows += Row("────────────────────")
            rows += if (edit != null) Row("Save to AIOMetadata", "►", action = { if (fits(app, p)) save(app, edit, password!!, p) })
            else Row("Create my rows", "►", action = { if (fits(app, p)) askNewPassword(app, p) })
            return rows
        }
        s = Screen(if (edit != null) "Categories" else "Create your rows", emptyList())
        s.rows = build()
        s.onShow = { s.rows = build(); app.refresh(s) }
        return s
    }

    /** One optional key: where to get it (link or QR code), and entering, changing or removing it. */
    fun keyScreen(app: MainActivity, title: String, page: String, about: List<String>, get: () -> String, set: (String) -> Unit): Screen {
        lateinit var s: Screen
        fun build(): List<Row> = about.map { Row(it) } + listOf(
            Row(""),
            LinkOut.row(app, "Get your key", title, page),
            Row(if (get().isBlank()) "Enter the key" else "Change the key", if (get().isBlank()) "" else "…" + get().takeLast(4), action = {
                val input = EditText(app).apply { hint = title; setSingleLine(); setText(get()) }
                android.app.AlertDialog.Builder(app).setTitle(title).setMessage("Type or paste it.").setView(input)
                    .setPositiveButton("OK") { _, _ -> set(input.text.toString().trim()); s.rows = build(); app.refresh(s) }
                    .setNegativeButton("Cancel", null).show()
            }),
        ) + (if (get().isNotBlank()) listOf(Row("Remove the key", action = { set(""); s.rows = build(); app.refresh(s) })) else emptyList()) +
            listOf(Row("Done", action = { app.back() }))
        s = Screen(title, build())
        return s
    }

    private fun save(app: MainActivity, l: AioRows.Loaded, password: String, p: RowSetup.Picks) {
        RowSetup.putKeys(l.config, p)
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

    private fun create(app: MainActivity, p: RowSetup.Picks, password: String, tmdbKey: String = "") {
        val key = Account.authKey(app) ?: return
        val status = Screen("Creating your rows", listOf(Row("Creating your AIOMetadata… (up to a minute)")))
        app.push(status)
        Net.async({
            val made = RowSetup.create(p, password, tmdbKey.ifBlank { p.tmdbKey })
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
                val needsKey = e is AioRows.Failed && e.message?.contains("TMDB") == true
                status.rows = listOfNotNull(
                    Row("Couldn't create the rows: " + (if (e is AioRows.Failed) e.message else Net.describe(e))),
                    if (needsKey) Row("Free key: themoviedb.org › Settings › API (copy the 'API Key')") else null,
                    if (needsKey) Row("Enter a TMDB key", "►", action = {
                        val input = EditText(app).apply { hint = "TMDB API key"; setSingleLine() }
                        android.app.AlertDialog.Builder(app).setTitle("TMDB key").setView(input)
                            .setPositiveButton("Create") { _, _ ->
                                val k = input.text.toString().trim()
                                if (k.isNotEmpty()) { app.back(); create(app, p, password, k) }
                            }.setNegativeButton("Cancel", null).show()
                    }) else Row("Try again", action = { app.back(); create(app, p, password, tmdbKey) }),
                )
            }
            app.refresh(status)
        }
    }
    /** ElfHosted's free server takes at most 200 rows per install: say what to untick if it's over. */
    private fun fits(app: MainActivity, p: RowSetup.Picks): Boolean {
        val n = RowSetup.count(p)
        if (n <= RowSetup.SERVER_LIMIT - 10) return true
        val big = RowSetup.biggest(p).joinToString(", ") { "${it.first} (${it.second})" }
        android.app.AlertDialog.Builder(app).setTitle("Too many rows: about $n")
            .setMessage("ElfHosted's free AIOMetadata allows 200 rows per install. Untick some of the biggest: $big.")
            .setPositiveButton("OK", null).show()
        return false
    }

}
