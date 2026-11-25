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
                RowPicker.entry(app) { _, l, pw ->
                    // Read your ticks first, then add any catalogue rows the install is missing (switched off).
                    val picks = RowSetup.picksFrom(l.config)
                    RowSetup.complete(l.catalogs, picks.region)
                    checklist(app, picks, edit = l, password = pw)
                }
            })
            rows += Row("Switch single rows on or off", "►", opens = { RowPicker.entry(app) })
        }
        rows += Row("TMDB Collections add-on", (if (onAccount(app)) "on your account ✓" else "needed for Collections") + "  ►", opens = { collections(app) })
        rows += Row("────────────────────")
        rows += Row(if (aio.isEmpty()) "Create your rows" else "Start again from the full catalogue", "►",
            opens = { checklist(app, RowSetup.defaults(), edit = null, password = null) })
        if (aio.isNotEmpty()) rows += Row("(makes a new AIOMetadata and replaces yours)")
        if (stick) rows += Row("You have a stick rows install: Stremonino shows its rows.")
        return Screen("Your rows (AIOMetadata)", rows)
    }


    /** The checklist. With [edit], it changes that install's rows; without, it creates a new install. */
    private fun checklist(app: MainActivity, p: RowSetup.Picks, edit: AioRows.Loaded?, password: String?): Screen {
        // A key remembered from an earlier setup on this device is filled in.
        if (p.mdblistKey.isBlank()) p.mdblistKey = Settings.mdblistKey(app)
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
            if (edit != null) {
                val others = RowSetup.others(edit.catalogs)
                if (others.isNotEmpty()) {
                    rows += Row("Other rows in your config", "${others.size} · ◄ ${if (p.othersOff) "Turn off" else "Keep"} ►", action = {
                        p.othersOff = !p.othersOff; s.rows = build(); app.refresh(s)
                    })
                    rows += Row("   See which (older rows, your own lists)", "►", opens = { Screen("Other rows (${others.size})", others.map { Row(it) }) })
                }
            }
            rows += Row("──── Add-ons these rows need ────")
            rows += Row("TMDB Collections", (when {
                onAccount(app) -> "on your account ✓"
                "Collections" in p.categories -> if (edit == null) "added with your rows" else "needed: add it"
                else -> "only for Collections"
            }) + "  ►", opens = { collections(app) })
            rows += Row("──── Optional keys ────")
            rows += Row("MDBList key", (if (p.mdblistKey.isNotBlank()) "set ✓" else "adds ${RowSetup.mdblistCount(p)} rows") + "  ►", opens = {
                keyScreen(app, "MDBList key", RowSetup.MDBLIST_KEY_PAGE, listOf(
                    "Free account at mdblist.com, then Preferences › API key.",
                    "Adds MDBList rows: Top 100 Today, IMDb Top 250, Certified Fresh,",
                    "Trending Kids, Bollywood releases and a few genre lists.",
                ), { p.mdblistKey }, { p.mdblistKey = it; Settings.setMdblistKey(app, it) })
            })
            rows += Row("Your own TMDB key", (if (p.tmdbKey.isNotBlank()) "set ✓" else "not needed") + "  ►", opens = {
                keyScreen(app, "TMDB key", RowSetup.TMDB_KEY_PAGE, listOf(
                    "Only needed if the server asks for one.",
                    "Free account at themoviedb.org, then Settings › API › API Key.",
                ), { p.tmdbKey }, { p.tmdbKey = it })
            })
            rows += Row("────────────────────")
            rows += if (edit != null) Row("Save to AIOMetadata", "►", action = { if (fits(app, p, edit)) save(app, edit, password!!, p) })
            else Row("Create my rows", "►", action = { if (fits(app, p)) askNewPassword(app, p) })
            return rows
        }
        s = Screen(if (edit != null) "Categories" else "Create your rows", emptyList())
        s.rows = build()
        s.onShow = { s.rows = build(); app.refresh(s) }
        return s
    }

    /** One optional key: where to get it (link or QR code), and entering, changing or removing it. */
    fun keyScreen(app: MainActivity, title: String, page: String, about: List<String>, get: () -> String, set: (String) -> Unit, top: List<Row> = emptyList(), after: (() -> List<Row>)? = null): Screen {
        lateinit var s: Screen
        fun build(): List<Row> = top + about.map { Row(it) } + listOf(
            Row(""),
            LinkOut.row(app, "Get your key", title, page),
            Row(if (get().isBlank()) "Enter the key" else "Change the key", if (get().isBlank()) "" else "…" + get().takeLast(4), action = {
                val input = EditText(app).apply { hint = title; setSingleLine(); setText(get()) }
                PhoneTyping.show(app, android.app.AlertDialog.Builder(app).setTitle(title).setMessage("Type or paste it.")
                    .setPositiveButton("OK") { _, _ -> set(input.text.toString().trim()); s.rows = build(); app.refresh(s) }
                    .setNegativeButton("Cancel", null), input, title, secret = true)
            }),
        ) + (if (get().isNotBlank()) listOf(Row("Remove the key", action = { set(""); s.rows = build(); app.refresh(s) })) else emptyList()) +
            (if (get().isNotBlank()) after?.invoke().orEmpty() else emptyList()) +
            listOf(Row("Done", action = { app.back() }))
        s = Screen(title, build())
        return s
    }

    /**
     * Puts [key] into your AIOMetadata rows' config (nothing else changes), so MDBList rows and
     * personal lists like the Watchlist use that account. Asks for the rows' password if needed.
     */
    fun useMdblistKey(app: MainActivity, key: String): Screen = RowPicker.entry(app) { _, l, pw ->
        val status = Row("Saving the key into your rows…")
        val s = Screen("MDBList key", listOf(status))
        RowSetup.setMdblistKey(l.config, key)
        Net.async({ AioRows.save(l, pw) }) { r ->
            status.label = r.fold({ "Saved: your rows use this key now. Home reloads them." }) { e -> "Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e)) }
            if (r.isSuccess) Home.forget()
            app.refresh(s)
        }
        s
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

    private var addedHere = false
    private fun onAccount(app: MainActivity) = addedHere || RowSetup.hasCollectionsAddon(Home.allAddons())

    /** The TMDB Collections add-on: Collections rows list film series, and this add-on opens them. */
    fun collections(app: MainActivity): Screen {
        lateinit var s: Screen
        var redraw: () -> Unit = {}
        fun install(link: String) {
            val key = Account.authKey(app) ?: return
            app.toast("Adding TMDB Collections…")
            Net.async({ Stremio.installAddon(key, link, detailsOnly = true) }) { r ->
                r.onSuccess { addedHere = true; Home.forget(); app.toast("Added. Collections now open their film series."); redraw() }
                    .onFailure { e -> app.toast("Couldn't add it: " + Net.describe(e)) }
            }
        }
        fun build(): List<Row> = listOfNotNull(
            Row("Collections rows list film series (Harry Potter, Alien…)."),
            Row("Opening one needs the TMDB Collections add-on on your"),
            Row("account. It's added for details only: no extra rows."),
            Row(info("On your account", if (onAccount(app)) "yes ✓" else "no")),
            Row(""),
            if (!onAccount(app)) Row("Add it now (standard settings)", "►", action = { install(RowSetup.collectionsAddon) }) else null,
            Row("Use my own install link", "paste  ►", action = {
                val input = EditText(app).apply { hint = "https://…/manifest.json"; setSingleLine() }
                PhoneTyping.show(app, android.app.AlertDialog.Builder(app).setTitle("TMDB Collections install link")
                    .setMessage("From its settings page: Install, or copy the link.")
                    .setPositiveButton("Add") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { install(it) } }
                    .setNegativeButton("Cancel", null), input, "TMDB Collections link")
            }),
            LinkOut.row(app, "Its settings page (language, adult, search)", "TMDB Collections settings", RowSetup.collectionsPage),
            if (onAccount(app)) Row("Take it off the account", action = {
                val key = Account.authKey(app)
                if (key != null) Net.async({ RowSetup.collectionsAddons(Stremio.parseAddons(Stremio.addonsJson(key))).forEach { Stremio.removeAddon(key, it.transportUrl) } }) { r ->
                    r.onSuccess { addedHere = false; Home.forget(); app.toast("Taken off. Collections rows won't open."); redraw() }
                        .onFailure { e -> app.toast("Couldn't: " + Net.describe(e)) }
                }
            }) else null,
            Row("Done", action = { app.back() }),
        )
        s = Screen("TMDB Collections", build())
        redraw = { s.rows = build(); app.refresh(s) }
        return s
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
            .setView(PhoneTyping.box(app, pw, Secret.toggle(app, pw)))
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
            if ("Collections" in p.categories && !RowSetup.hasCollectionsAddon(Home.allAddons()))
                runCatching { Stremio.installAddon(key, RowSetup.collectionsAddon, detailsOnly = true) }
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
                        PhoneTyping.show(app, android.app.AlertDialog.Builder(app).setTitle("TMDB key")
                            .setPositiveButton("Create") { _, _ ->
                                val k = input.text.toString().trim()
                                if (k.isNotEmpty()) { app.back(); create(app, p, password, k) }
                            }.setNegativeButton("Cancel", null), input, "TMDB key", secret = true)
                    }) else Row("Try again", action = { app.back(); create(app, p, password, tmdbKey) }),
                )
            }
            app.refresh(status)
        }
    }
    /** ElfHosted's free server takes at most 200 rows per install: say what to untick if it's over. */
    private fun fits(app: MainActivity, p: RowSetup.Picks, existing: AioRows.Loaded? = null): Boolean {
        val n = RowSetup.serverCountFor(p, existing?.catalogs)
        if (n <= RowSetup.SERVER_LIMIT) return true
        val big = RowSetup.biggest(p, existing = existing?.catalogs).joinToString(", ") { "${it.first} (${it.second})" }
        android.app.AlertDialog.Builder(app).setTitle("Too many rows: $n")
            .setMessage("ElfHosted's free AIOMetadata allows 200 rows per install. Untick some of the biggest: $big.")
            .setPositiveButton("OK", null).show()
        return false
    }

}
