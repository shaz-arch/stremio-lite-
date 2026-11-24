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
                RowPicker.entry { _, l, pw -> checklist(RowSetup.picksFrom(l.config), edit = l, password = pw) }
            })
            rows += Row("Switch single rows on or off", "►", opens = { RowPicker.entry() })
        }
        if (!RowSetup.hasCollectionsAddon(Home.all())) rows += Row("Add TMDB Collections (opens film series)", "►", action = {
            val key = Account.authKey
            if (key != null) {
                App.toast("Adding TMDB Collections…")
                App.async({ Stremio.installAddon(key, RowSetup.collectionsAddon, detailsOnly = true) }) { r ->
                    r.onSuccess { java.io.File(Store.dir, "addons.json").delete(); Home.forget(); Home.load(force = true); App.toast("Added. Collections rows now open their film series."); App.back() }
                        .onFailure { e -> App.toast("Couldn't add it: " + Net.describe(e)) }
                }
            }
        })
        rows += Row("────────────────────")
        rows += Row(if (aio.isEmpty()) "Create your rows" else "Start again from the full catalogue", "►",
            opens = { checklist(RowSetup.defaults(), edit = null, password = null) })
        if (aio.isNotEmpty()) rows += Row("(makes a new AIOMetadata and replaces yours)")
        if (stick) rows += Row("You have a stick rows install: Stremonino shows its rows.")
        return Screen("Your rows (AIOMetadata)", rows)
    }


    private fun checklist(p: RowSetup.Picks, edit: AioRows.Loaded?, password: String?): Screen {
        lateinit var s: Screen
        fun tick(on: Boolean) = if (on) "✓ " else "   "
        fun build(): List<Row> {
            val rows = mutableListOf<Row>()
            if (edit == null) rows += Row("Region", "◄ ${p.region.label} ►", action = {
                p.region = if (p.region == RowSetup.Region.UK) RowSetup.Region.US else RowSetup.Region.UK
                p.services.clear(); p.services += RowSetup.defaultServices(p.region)
                s.rows = build(); App.refresh()
            })
            else rows += Row("Region", p.region.label + " (set when created)")
            rows += Row("Size", "◄ ${p.size.label} ►   about ${RowSetup.count(p)} rows" + (if (RowSetup.count(p) > RowSetup.SERVER_LIMIT) " (over 200!)" else ""), action = {
                val sizes = RowSetup.Size.values()
                RowSetup.resize(p, sizes[(p.size.ordinal + 1) % sizes.size])
                s.rows = build(); App.refresh()
            })
            rows += Row("──── Streaming services (Top 10 and Streaming) ────")
            RowSetup.services(p.region).forEach { svc ->
                lateinit var r: Row
                r = Row(tick(svc in p.services) + svc, "", action = {
                    if (svc in p.services) p.services -= svc else p.services += svc
                    s.rows = build(); App.refresh()
                })
                rows += r
            }
            rows += Row("──── Categories ────")
            RowSetup.categories.forEach { c ->
                lateinit var r: Row
                r = Row(tick(c in p.categories) + c, "${RowSetup.countIn(p, c)} rows", action = {
                    if (c in p.categories) p.categories -= c else p.categories += c
                    s.rows = build(); App.refresh()
                })
                rows += r
            }
            rows += Row("──── Optional keys ────")
            rows += Row("MDBList key", (if (p.mdblistKey.isNotBlank()) "set ✓" else "adds ${RowSetup.mdblistCount(p)} rows") + "  ►", opens = {
                keyScreen("MDBList key", RowSetup.MDBLIST_KEY_PAGE, listOf(
                    "Free account at mdblist.com, then Preferences › API key.",
                    "Adds MDBList rows: Top 100 Today, IMDb Top 250, Certified Fresh,",
                    "Trending Kids, Bollywood releases and a few genre lists.",
                ), { p.mdblistKey }, { p.mdblistKey = it })
            })
            rows += Row("Your own TMDB key", (if (p.tmdbKey.isNotBlank()) "set ✓" else "not needed") + "  ►", opens = {
                keyScreen("TMDB key", RowSetup.TMDB_KEY_PAGE, listOf(
                    "Only needed if the server asks for one.",
                    "Free account at themoviedb.org, then Settings › API › API Key.",
                ), { p.tmdbKey }, { p.tmdbKey = it })
            })
            rows += Row("────────────────────")
            rows += if (edit != null) Row("Save to AIOMetadata", "►", action = { if (fits(p, edit)) save(edit, password!!, p) })
            else Row("Create my rows", "►", action = { if (fits(p)) askNewPassword(p) })
            return rows
        }
        s = Screen(if (edit != null) "Categories" else "Create your rows", emptyList())
        s.rows = build()
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    fun keyScreen(title: String, page: String, about: List<String>, get: () -> String, set: (String) -> Unit): Screen {
        lateinit var s: Screen
        fun build(): List<Row> = about.map { Row(it) } + listOf(
            Row(""),
            Row("Get your key", "opens browser  ►", action = { WebLink.open(page) }),
            Row(if (get().isBlank()) "Enter the key" else "Change the key", if (get().isBlank()) "" else "…" + get().takeLast(4), action = {
                App.ask("$title (ctrl+shift+v pastes)", get()) { v -> if (v != null) { set(v.trim()); s.rows = build(); App.refresh() } }
            }),
        ) + (if (get().isNotBlank()) listOf(Row("Remove the key", action = { set(""); s.rows = build(); App.refresh() })) else emptyList()) +
            listOf(Row("Done", action = { App.back() }))
        s = Screen(title, build())
        return s
    }

    private fun save(l: AioRows.Loaded, password: String, p: RowSetup.Picks) {
        RowSetup.putKeys(l.config, p)
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

    private fun create(p: RowSetup.Picks, password: String, tmdbKey: String = "") {
        val key = Account.authKey ?: return
        val status = Screen("Creating your rows", listOf(Row("Creating your AIOMetadata… (up to a minute)")))
        App.push(status)
        App.async({
            val made = RowSetup.create(p, password, tmdbKey.ifBlank { p.tmdbKey })
            Stremio.installAddon(key, made.installUrl)
            if ("Collections" in p.categories && !RowSetup.hasCollectionsAddon(Home.all()))
                runCatching { Stremio.installAddon(key, RowSetup.collectionsAddon, detailsOnly = true) }
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
                val needsKey = e is AioRows.Failed && e.message?.contains("TMDB") == true
                status.rows = listOfNotNull(
                    Row("Couldn't create the rows: " + (if (e is AioRows.Failed) e.message else Net.describe(e))),
                    if (needsKey) Row("Free key: themoviedb.org › Settings › API (copy the 'API Key')") else null,
                    if (needsKey) Row("Enter a TMDB key", "►", action = {
                        App.ask("TMDB API key (ctrl+shift+v pastes)") { k ->
                            val key2 = k?.trim().orEmpty()
                            if (key2.isNotEmpty()) { App.back(); create(p, password, key2) }
                        }
                    }) else Row("Try again", action = { App.back(); create(p, password, tmdbKey) }),
                )
            }
            App.refresh()
        }
    }
    /** ElfHosted's free server takes at most 200 rows per install: say what to untick if it's over. */
    private fun fits(p: RowSetup.Picks, existing: AioRows.Loaded? = null): Boolean {
        val n = RowSetup.serverCountFor(p, existing?.catalogs)
        if (n <= RowSetup.SERVER_LIMIT) return true
        val big = RowSetup.biggest(p).joinToString(", ") { "${it.first} (${it.second})" }
        App.toast("$n rows: ElfHosted allows 200 per install. Untick some of: $big")
        return false
    }

}
