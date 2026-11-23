package app.stremiolite

/**
 * Home: every catalogue row from the account's add-ons as one list, in Stremio's order,
 * then the tools (add-ons and their connection checks, device check, sign-out).
 */
object Home {
    private var addons: List<Addon>? = null
    private var loading = false
    private var loadError: String? = null

    /** Connection result per add-on (by transport URL), shown on the list and details screens. */
    private val checks = mutableMapOf<String, String>()

    /** The add-ons currently loaded (empty until the list arrives). */
    fun allAddons(): List<Addon> = addons ?: emptyList()

    /**
     * Add-ons whose rows and search the app uses: an add-on installed as "stick rows" takes the
     * place of the add-on with the same id (e.g. the phone's AIOMetadata) for rows and search.
     * That add-on still answers everything else (details, episodes).
     */
    fun rowAddons(list: List<Addon> = allAddons()): List<Addon> = Folders.rowAddons(list)

    fun forget() {
        addons = null
        savedJson = null
        rebuildHome = null
        loadError = null
        checks.clear()
    }

    fun screen(app: MainActivity): Screen {
        // Just the profile's name (the email stays in Switch profile).
        fun whoText() = Profiles.current(app)?.name ?: "…"
        val who = Row("Profile", whoText())
        val addonsRow = Row("Your add-ons", "", opens = { addonList(app) })
        val status = Row("")
        lateinit var screen: Screen

        val search = Row("Search", "☰  ►", opens = { Search.menu(app) })
        val tools = listOf(
            Row("────────────────────"),
            Row("Settings", opens = { Settings.screen(app) }),
            addonsRow,
            Row("Reload my rows and add-ons", action = { if (!loading) load(app, screen) }),
            Row("Export my setup to another account", "►", opens = { Share.screen(app) }),
            Row("Import a setup from another account", "►", opens = { Share.importScreen(app) }),
            Row("Device check", opens = { DemoMenus.deviceCheck(app) }),
            Row("About", opens = { DemoMenus.about() }),
            who,
            Row("Switch profile", "►", opens = { Profiles.chooser(app) }),
            Row("Sign out of this profile", opens = { signOut(app) }),
        )

        fun rebuild() {
            val list = addons
            addonsRow.detail = if (list != null) "${list.size}  ►" else ""
            who.detail = whoText()
            screen.tag = Profiles.current(app)?.name ?: ""
            val rows = mutableListOf<Row>()
            if (list != null) rows += homeRows(app, list)
            status.label = when {
                loading && list == null -> "Loading your rows…"
                loadError != null && list == null -> "Couldn't load your rows: $loadError"
                loadError != null -> "Showing saved rows (refresh failed: $loadError)"
                list != null && rows.isEmpty() -> "No catalogue rows found in your add-ons."
                else -> ""
            }
            val expired = loadError?.contains("session", ignoreCase = true) == true
            status.action = if (expired) ({ Profiles.current(app)?.let { app.push(Profiles.signInAgain(app, it.id)) } }) else null
            if (expired) status.label = "Your sign-in has expired. Press OK to sign in again"
            if (status.label.isNotEmpty()) rows.add(0, status)
            // Search, then what you're watching and what you've saved, then the catalogue rows.
            val cw = History.continueWatching(app)
            val lib = History.library(app)
            Updates.available?.let { n ->
                rows.add(0, Row("Update available: build $n. Press OK to install", "►", action = { Updates.install(app) }))
            }
            rows.addAll(if (Updates.available != null) 1 else 0, listOf(
                search,
                Row("Continue watching", if (cw.isEmpty()) "nothing yet" else "${cw.size}  ►", opens = { continueScreen(app) }),
                Row("Library", if (lib.isEmpty()) "empty" else "${lib.size}  ►", opens = { libraryScreen(app) }),
                Row("New episodes", NewEpisodes.recentCount()?.let { if (it == 0) "none  ►" else "$it  ►" } ?: "", opens = { NewEpisodes.screen(app) }),
                Row("Calendar", NewEpisodes.weekCount()?.let { if (it == 0) "nothing this week  ►" else "$it this week  ►" } ?: "", opens = { NewEpisodes.calendar(app) }),
                Row("Watched", History.watched(app).size.let { if (it == 0) "" else "$it  ►" }, opens = { watchedScreen(app) }),
                Row("Waiting for streams", Waiting.summary(app), opens = { Waiting.screen(app) }),
                Row("────────────────────"),
            ))
            screen.rows = rows + tools
            app.refresh(screen)
        }
        rebuildHome = { rebuild() }

        screen = Screen(title = "Stremonino", rows = listOf(Row("Loading your rows…")) + tools, tag = Profiles.current(app)?.name ?: "")
        screen.onShow = {
            // A renamed profile shows its new name in the title straight away.
            screen.tag = Profiles.current(app)?.name ?: ""
            who.detail = whoText()
            History.pull(app) { rebuild() }
            Updates.check { rebuild() }
            Waiting.checkSoon(app) { rebuild() }
            // Counts for New episodes and the Calendar: checked quietly once an hour.
            if (!NewEpisodes.fresh()) Net.later(4_000) { if (addons != null) NewEpisodes.load(app) { rebuild() } }
            // A shared setup opened before signing in: offer it now.
            Share.takePending()?.let { p -> Net.later(300) { app.push(Share.receive(app, p)) } }
            if (addons == null) {
                // Show the saved list straight away, then refresh it from the account.
                runCatching { Account.addonCache(app).readText() }.onSuccess { text ->
                    savedJson = text
                    runCatching { Stremio.parseAddons(text) }.onSuccess { addons = it }
                }
                if (!loading) load(app, screen)
            }
            rebuild()
        }
        return screen
    }

    private var rebuildHome: (() -> Unit)? = null

    /** Redraws Home if it has been built (e.g. an update was found while the app was in the background). */
    fun redraw() { rebuildHome?.invoke() }

    /** The add-on list as last saved, so a refresh that changes nothing doesn't redraw Home. */
    private var savedJson: String? = null

    private fun load(app: MainActivity, screen: Screen) {
        val key = Account.authKey(app) ?: return
        loading = true
        rebuildHome?.invoke()
        Net.async({
            val email = runCatching { Stremio.userEmail(key) }.getOrDefault("")
            val json = Stremio.withLiveManifests(Stremio.addonsJson(key))
            Triple(email, json, Stremio.parseAddons(json))
        }) { r ->
            loading = false
            r.onSuccess { (email, json, list) ->
                val hadError = loadError != null
                loadError = null
                if (email.isNotEmpty()) Account.saveEmail(app, email)
                if (json == savedJson && addons != null) {
                    // Same rows as on screen already: nothing to redraw.
                    if (hadError) rebuildHome?.invoke()
                    return@async
                }
                addons = list
                savedJson = json
                checks.clear()
                Net.async({ runCatching { Account.addonCache(app).writeText(json) } }) { }
            }.onFailure { e ->
                loadError = if (e is Stremio.ApiError) e.message else Net.describe(e)
            }
            rebuildHome?.invoke()
        }
    }

    /**
     * The front page (home rows with no folder prefix, in add-on order), then the Index of folders.
     */
    private fun homeRows(app: MainActivity, list: List<Addon>): List<Row> {
        val entries = Index.entries(app, list)
        val front = entries.filter { it.folder == null }
        // The add-on with the most rows (AIOMetadata) gets no name tag; others are labelled.
        val main = entries.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
        val rows = front.mapTo(mutableListOf()) { e ->
            val tag = if (e.addon === main) "" else shortName(e.addon.name) + " · "
            Row(e.name, tag + Catalogs.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(app, e.addon, e.catalog) })
        }
        val folders = Index.folderRows(app, entries)
        if (folders.isNotEmpty()) {
            rows += Row("──── Index ─────────────")
            rows += folders
        }
        return rows
    }

    private fun continueScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val list = History.continueWatching(app)
            if (list.isEmpty()) return listOf(Row("Nothing here yet. Films and episodes you start will appear here."))
            return list.map { e ->
                val detail = when {
                    e.upNext -> "next: " + e.label.substringBefore(' ')
                    e.duration > 0 -> (if (e.label.isNotEmpty()) e.label.substringBefore(' ') + "  " else "") +
                        "${History.clock((e.duration - e.position).coerceAtLeast(0))} left"
                    else -> e.label.substringBefore(' ')
                }
                Row(e.name, detail, opens = {
                    Details.screenFor(app, e.id, e.type, e.name, e.year) ?: Screen(e.name, listOf(Row("Your add-ons are still loading. Try again in a moment.")))
                }, play = { Details.screenFor(app, e.id, e.type, e.name, e.year, autoPlay = true)?.let { app.push(it) } })
            }
        }
        screen = Screen("Continue watching", build())
        screen.onShow = {
            screen.rows = build(); app.refresh(screen)
            History.pull(app) { screen.rows = build(); app.refresh(screen) }
        }
        return screen
    }

    private fun libraryScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val list = History.library(app)
            if (list.isEmpty()) return listOf(Row("Empty. Use \"Add to library\" on any film or show."))
            return list.map { s ->
                Row(s.name, listOf(s.year, Catalogs.typeLabel(s.type).removeSuffix("s")).filter { it.isNotBlank() }.joinToString("  "), opens = {
                    Details.screenFor(app, s.id, s.type, s.name, s.year) ?: Screen(s.name, listOf(Row("Your add-ons are still loading. Try again in a moment.")))
                }, play = { Details.screenFor(app, s.id, s.type, s.name, s.year, autoPlay = true)?.let { app.push(it) } })
            }
        }
        screen = Screen("Library", build())
        screen.onShow = {
            screen.rows = build(); app.refresh(screen)
            History.pull(app) { screen.rows = build(); app.refresh(screen) }
        }
        return screen
    }

    /** One finished film or episode: open it, or take it off the watched list. */
    private fun watchedEntry(app: MainActivity, f: History.Finished): Screen {
        val ep = f.label.substringBefore(' ').takeIf { Regex("S\\d+E\\d+").matches(it) } ?: ""
        val s = Screen(f.name + if (ep.isNotEmpty()) " $ep" else "", emptyList())
        s.rows = listOf(
            Row("Open", "►", opens = {
                Details.screenFor(app, f.id, f.type, f.name, f.year) ?: Screen(f.name, listOf(Row("Your add-ons are still loading. Try again in a moment.")))
            }),
            Row("Remove from watched", action = {
                if (f.type == "movie" || ep.isEmpty()) {
                    History.unwatch(app, f.id, f.type, f.id, "", emptyList())
                    app.toast("Removed from watched"); app.back()
                } else {
                    // Episodes: the show's episode list is needed to find this one and update its marks.
                    app.toast("Removing…")
                    Net.async({ Details.loadFull("series", f.id) }) { r ->
                        r.onSuccess { show ->
                            val v = show.videos.firstOrNull { Details.epLabel(it) == ep }
                            History.unwatch(app, f.id, f.type, v?.id ?: f.id, ep, MetaParse.stremioOrder(show))
                            app.toast("Removed from watched"); app.back()
                        }.onFailure { app.toast("Couldn't remove: ${Net.describe(it)}") }
                    }
                }
            }),
        )
        return s
    }

    private fun watchedScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        fun build(): List<Row> {
            val list = History.watched(app)
            if (list.isEmpty()) return listOf(Row("Nothing finished yet. Films and episodes you watch to the end appear here."))
            return list.map { f ->
                val detail = listOf(f.label.substringBefore(' ').takeIf { it.startsWith("S") } ?: f.label, if (f.time > 0) day.format(java.util.Date(f.time)) else "")
                    .filter { it.isNotBlank() }.joinToString("   ")
                Row(f.name, detail, opens = { watchedEntry(app, f) })
            }
        }
        screen = Screen("Watched", build())
        screen.onShow = {
            screen.rows = build(); app.refresh(screen)
            History.pull(app) { screen.rows = build(); app.refresh(screen) }
        }
        return screen
    }

    private fun shortName(name: String) = name.substringBefore('|').trim().take(18)

    private fun addonList(app: MainActivity): Screen {
        val list = addons
        if (list == null) {
            return Screen("Your add-ons", listOf(Row(if (loading) "Still loading, go back and try again in a moment." else "Couldn't load: ${loadError ?: "?"}")))
        }
        val summary = Row("")
        lateinit var screen: Screen
        val rows = list.map { a -> a to Row(a.name, "", opens = { addonDetails(app, a) }) }

        fun update() {
            rows.forEach { (a, row) -> row.detail = "v${a.version}  " + (checks[a.transportUrl] ?: "checking…") }
            val done = list.count { checks.containsKey(it.transportUrl) }
            val ok = list.count { checks[it.transportUrl]?.startsWith("OK") == true }
            val skipped = list.count { checks[it.transportUrl]?.startsWith("built") == true }
            val failed = done - ok - skipped
            summary.label = if (done < list.size) "Checking ${list.size} add-ons… $done done"
            else "${list.size} add-ons: $ok connect" +
                (if (failed > 0) ", $failed FAILED" else "") +
                (if (skipped > 0) ", $skipped built into Stremio" else "")
            app.refresh(screen)
        }

        fun checkAll() {
            checks.clear()
            update()
            for (a in list) {
                if (a.isLocal) {
                    checks[a.transportUrl] = "built into Stremio (skipped)"
                    continue
                }
                Net.async({
                    val t0 = System.currentTimeMillis()
                    Net.get(a.transportUrl, timeoutMs = 10_000)
                    System.currentTimeMillis() - t0
                }) { r ->
                    checks[a.transportUrl] = r.fold({ ms -> "OK ${ms} ms" }, { e -> "FAILED: ${Net.describe(e)}" })
                    update()
                }
            }
            update()
        }

        screen = Screen(
            "Your add-ons",
            listOf(summary, Row("────────────────────")) +
                rows.map { it.second } +
                listOf(
                    Row("────────────────────"),
                    Row("Check connections again", action = { checkAll() }),
                    Row("Install or replace an add-on by link", "►", action = { askInstall(app, false) }),
                    Row("Add stick rows (an index config just for this app)", "►", action = { askInstall(app, true) }),
                ),
        )
        screen.position = 2
        screen.onShow = { if (checks.isEmpty()) checkAll() else update() }
        return screen
    }

    /** Paste an add-on's link (https:// or stremio://). Replaces an installed copy of the same add-on. */
    fun askInstall(app: MainActivity, stickRows: Boolean) {
        val key = Account.authKey(app) ?: return
        val input = android.widget.EditText(app).apply {
            hint = "https://…/manifest.json"
            setSingleLine()
        }
        android.app.AlertDialog.Builder(app)
            .setTitle("Add-on link")
            .setMessage(
                if (stickRows) "Paste the install link of the config whose rows this app should show. " +
                    "Stremio's own apps won't see it, so your phone keeps its own rows."
                else "Paste the add-on's install link. If you already have this add-on (e.g. AIOMetadata), it is replaced.",
            )
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val link = input.text.toString().trim()
                if (link.isEmpty()) return@setPositiveButton
                val status = Screen("Installing", listOf(Row("Installing the add-on…")))
                app.push(status)
                Net.async({ Stremio.installAddon(key, link, stickRows) }) { r ->
                    status.rows = r.fold(
                        { name -> listOf(Row("Installed: $name"), Row("Your rows will reload on Home.")) },
                        { e -> listOf(Row("Couldn't install: " + if (e is Stremio.ApiError) e.message else Net.describe(e))) },
                    )
                    app.refresh(status)
                    if (r.isSuccess) { forget() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addonDetails(app: MainActivity, a: Addon): Screen {
        val searchOnly = a.catalogs.count { it.required.isNotEmpty() }
        val head = listOf(
            Row(info("Version", a.version)),
            Row(info("Server", a.host)),
            Row(info("Connection", checks[a.transportUrl] ?: "not checked yet")),
            Row(info("Types", a.types.joinToString(", ").ifEmpty { "-" })),
            Row(info("Provides", a.resources.joinToString(", ").ifEmpty { "-" })),
            Row(info("Catalogue rows", "${a.catalogs.size}" + if (searchOnly > 0) " ($searchOnly not on home)" else "")),
        )
        val cats = if (a.catalogs.isEmpty()) emptyList() else
            listOf(Row("──── Catalogue rows ────")) +
                a.catalogs.map { c ->
                    Row(c.name, Catalogs.typeLabel(c.type) + if (c.required.isNotEmpty()) " · not on home" else "")
                }
        val edit = if (a.isLocal) emptyList() else listOf(
            Row("Edit this add-on's settings on your phone", "QR  ►", opens = { editQr(a) }),
            Row("Show its install link (to keep a copy)", "QR  ►", opens = { linkQr(a) }),
            Row("Remove from this account", "►", opens = { removeScreen(app, a) }),
        )
        return Screen(a.name, edit + head + cats)
    }

    private fun removeScreen(app: MainActivity, a: Addon) = Screen(
        "Remove ${a.name}?",
        listOf(
            Row("No, go back", action = { app.back() }),
            Row("Yes, remove it from this Stremio account", action = {
                val key = Account.authKey(app) ?: return@Row
                Net.async({ Stremio.removeAddon(key, a.transportUrl) }) { r ->
                    r.onSuccess { app.toast("Removed ${a.name}"); forget(); app.resetTo(screen(app)) }
                        .onFailure { app.toast("Couldn't remove: ${Net.describe(it)}") }
                }
            }),
        ),
    )

    /** The add-on's install link as a QR code: photograph it, or scan it to reinstall elsewhere. */
    private fun linkQr(a: Addon): Screen {
        val s = Screen(
            "Install link: ${a.name.substringBefore('|').trim()}",
            listOf(
                Row("Scan or photograph this to keep a copy of the link."),
                Row("It reinstalls this exact setup if it's ever removed."),
                Row(""),
                Row("Keep it private: the link alone lets anyone"),
                Row("use this setup (but not change it)."),
                Row(""),
                Row(info("Server", a.host)),
            ),
        )
        s.image = Qr.make(a.transportUrl)
        return s
    }

    /** A QR code for the add-on's own configure page, so the phone opens the right config. */
    private fun editQr(a: Addon): Screen {
        val url = a.base + "/configure"
        val s = Screen(
            "Edit on your phone",
            listOf(
                Row("Scan the code with your phone's camera."),
                Row("It opens this add-on's own settings page."),
                Row(""),
                Row("For AIOMetadata: press Load Configuration,"),
                Row("enter your password, change things, then Save."),
                Row("Back here, choose Reload my rows and add-ons."),
                Row(""),
                Row("Your install link stays the same,"),
                Row("so nothing needs reinstalling."),
            ),
        )
        s.image = Qr.make(url)
        return s
    }

    private fun signOut(app: MainActivity) = Screen(
        "Sign out?",
        listOf(
            Row("No, go back", action = { app.back() }),
            Row("Yes, sign this profile out of the stick", action = {
                History.clear(app)
                Account.clear(app)
                History.reset()
                forget()
                app.applyLook()
                app.resetTo(
                    when {
                        Profiles.all(app).size > 1 -> Profiles.chooser(app)
                        Account.authKey(app) != null -> screen(app)
                        else -> SignIn.screen(app)
                    }
                )
            }),
        ),
    )
}

/** "Label ........ value" on one line, so values sit next to their labels. */
/** True in portrait (phones held upright): rows stack and label columns are narrower. */
var narrow = false

fun info(label: String, value: String) = "${label.padEnd(if (narrow) 13 else 22)} $value"
