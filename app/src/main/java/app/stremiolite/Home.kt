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

    fun forget() {
        addons = null
        rebuildHome = null
        loadError = null
        checks.clear()
    }

    fun screen(app: MainActivity): Screen {
        val who = Row(info("Signed in as", Account.email(app).ifEmpty { "…" }))
        val addonsRow = Row("Your add-ons", "", opens = { addonList(app) })
        val status = Row("")
        lateinit var screen: Screen

        val search = Row("Search", "☰  ►", action = { Search.start(app) })
        val tools = listOf(
            Row("────────────────────"),
            Row("Settings", opens = { Settings.screen(app) }),
            addonsRow,
            Row("Reload my rows and add-ons", action = { if (!loading) load(app, screen) }),
            Row("Device check", opens = { DemoMenus.deviceCheck(app) }),
            Row("About", opens = { DemoMenus.about() }),
            who,
            Row("Sign out", opens = { signOut(app) }),
        )

        fun rebuild() {
            val list = addons
            addonsRow.detail = if (list != null) "${list.size}  ►" else ""
            Account.email(app).takeIf { it.isNotEmpty() }?.let { who.label = info("Signed in as", it) }
            val rows = mutableListOf<Row>()
            if (list != null) rows += homeRows(app, list)
            status.label = when {
                loading && list == null -> "Loading your rows…"
                loadError != null && list == null -> "Couldn't load your rows: $loadError"
                loadError != null -> "Showing saved rows (refresh failed: $loadError)"
                list != null && rows.isEmpty() -> "No catalogue rows found in your add-ons."
                else -> ""
            }
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
                Row("Watched", opens = { watchedScreen(app) }),
                Row("────────────────────"),
            ))
            screen.rows = rows + tools
            app.refresh(screen)
        }
        rebuildHome = { rebuild() }

        screen = Screen(title = "Stremio Lite", rows = listOf(Row("Loading your rows…")) + tools)
        screen.onShow = {
            History.pull(app) { rebuild() }
            Updates.check { rebuild() }
            if (addons == null) {
                // Show the saved list straight away, then refresh it from the account.
                runCatching { Stremio.parseAddons(Account.addonCache(app).readText()) }
                    .onSuccess { addons = it }
                if (!loading) load(app, screen)
            }
            rebuild()
        }
        return screen
    }

    private var rebuildHome: (() -> Unit)? = null

    private fun load(app: MainActivity, screen: Screen) {
        val key = Account.authKey(app) ?: return
        loading = true
        rebuildHome?.invoke()
        Net.async({
            val email = runCatching { Stremio.userEmail(key) }.getOrDefault("")
            val json = Stremio.addonsJson(key)
            Triple(email, json, Stremio.parseAddons(json))
        }) { r ->
            loading = false
            r.onSuccess { (email, json, list) ->
                addons = list
                loadError = null
                checks.clear()
                if (email.isNotEmpty()) Account.saveEmail(app, email)
                runCatching { Account.addonCache(app).writeText(json) }
                History.pull(app, force = true) { rebuildHome?.invoke() }
            }.onFailure { e ->
                loadError = if (e is Stremio.ApiError) e.message else Net.describe(e)
            }
            rebuildHome?.invoke()
        }
    }

    /**
     * Every catalogue that Stremio would show on its home screen (no required extras),
     * in add-on order then catalogue order, so it matches the Stremio home layout.
     */
    private fun homeRows(app: MainActivity, list: List<Addon>): List<Row> {
        val pairs = list.filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { it.required.isEmpty() }.map { c -> a to c }
        }
        // The add-on with the most rows (AIOMetadata) gets no name tag; others are labelled.
        val main = pairs.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key
        return pairs.map { (a, c) ->
            val tag = if (a === main) "" else shortName(a.name) + " · "
            Row(c.name, tag + Catalogs.typeLabel(c.type) + "  ►", opens = { Catalogs.screen(app, a, c) })
        }
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

    private fun watchedScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        fun build(): List<Row> {
            val list = History.watched(app)
            if (list.isEmpty()) return listOf(Row("Nothing finished yet. Films and episodes you watch to the end appear here."))
            return list.map { f ->
                val detail = listOf(f.label.substringBefore(' ').takeIf { it.startsWith("S") } ?: f.label, if (f.time > 0) day.format(java.util.Date(f.time)) else "")
                    .filter { it.isNotBlank() }.joinToString("   ")
                Row(f.name, detail, opens = {
                    Details.screenFor(app, f.id, f.type, f.name, f.year) ?: Screen(f.name, listOf(Row("Your add-ons are still loading. Try again in a moment.")))
                })
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
                listOf(Row("────────────────────"), Row("Check connections again", action = { checkAll() })),
        )
        screen.position = 2
        screen.onShow = { if (checks.isEmpty()) checkAll() else update() }
        return screen
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
        return Screen(a.name, head + cats)
    }

    private fun signOut(app: MainActivity) = Screen(
        "Sign out?",
        listOf(
            Row("No, go back", action = { app.back() }),
            Row("Yes, sign out of this TV", action = {
                History.clear(app)
                Account.clear(app)
                forget()
                app.resetTo(SignIn.screen(app))
            }),
        ),
    )
}

/** "Label ........ value" on one line, so values sit next to their labels. */
fun info(label: String, value: String) = "${label.padEnd(22)} $value"
