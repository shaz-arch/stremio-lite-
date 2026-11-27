package app.stremiolite

/**
 * Settings › Set up Stremonino: the whole setup in order (account, keys, rows, streams, Home,
 * a check), using the screens Settings already has. Shows what's done; every step can be skipped;
 * nothing already set up is replaced without asking. One password for rows and streams.
 */
object SetupWizard {
    /** The one password for rows and streams while setting up (kept in memory only). */
    var password: String? = null
        private set
    private var active = false
    private var currentStep: Screen? = null
    private var currentIndex = 0

    /** On a "Done" page reached from a setup step: back to that step, then on to the next. */
    fun continueRow(app: MainActivity): Row? {
        val step = currentStep ?: return null
        if (!active || !app.inStack(step)) return null
        return Row("Continue setting up", "►", action = {
            while (!app.isTop(step) && app.inStack(step)) app.back()
            next(app, currentIndex)
        })
    }

    private fun next(app: MainActivity, i: Int) {
        Settings.markSetup(app, STEPS[i].first)
        app.back()
        app.push(if (i + 1 < STEPS.size) step(app, i + 1) else finished(app))
    }

    /** A password just chosen for rows or streams: the other uses it too. */
    fun offer(v: String) { if (active && password == null) password = v }

    private val STEPS = listOf("account" to "Account", "keys" to "Keys", "rows" to "Rows", "streams" to "Streams", "home" to "Home & extras", "check" to "Check")

    private fun aioRows() = Home.allAddons().filter { AioRows.isAio(it) }
    private fun stickFirst() = aioRows().let { l -> l.firstOrNull { it.stickRows } ?: l.firstOrNull() }
    private fun newRows(): List<String> = stickFirst()?.let { a -> RowSetup.newRowsFromIds(a.catalogs.map { it.id }.toSet()) } ?: emptyList()

    /** One line of where each step stands, worked out from what's there now. */
    private fun status(app: MainActivity, step: String): String {
        val done = step in Settings.setupDone(app)
        return when (step) {
            "account" -> "✓ " + (Profiles.current(app)?.name ?: "signed in")
            "keys" -> Settings.keysOf(Profiles.prefsFor(app, "settings")).names.let { if (it.isEmpty()) "○ none yet" else "✓ " + it.joinToString(", ") }
            "rows" -> when {
                aioRows().isEmpty() -> "○ none yet"
                newRows().isNotEmpty() -> "◐ ${newRows().size} new rows to add"
                else -> "✓ up to date"
            }
            "streams" -> if (Home.allAddons().none { StreamSetup.isAioStreams(it) }) "○ none yet"
                else "✓ " + StreamSetup.names(Settings.debridKeys(app)).joinToString(", then ").ifEmpty { "on your account" }
            else -> if (done) "✓ done" else "○ not yet"
        }
    }

    fun screen(app: MainActivity): Screen {
        active = true
        password = null
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf(
                Row("Everything Stremonino can set up, in order. Skip any step;"),
                Row("nothing you already have is replaced without asking."),
                Row("────────────────────"),
            )
            STEPS.forEachIndexed { i, (id, name) -> rows += Row(name, status(app, id) + "  ►", action = { app.push(step(app, i)) }) }
            rows += Row("────────────────────")
            rows += Row("Start from step 1", "►", action = { app.push(step(app, 0)) })
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Set up Stremonino", emptyList())
        // What's on the account right now, not what Home last loaded.
        s.onShow = { build(); Home.refreshAddons(app) { if (app.isTop(s)) build() } }
        s.position = 3
        return s
    }

    private fun step(app: MainActivity, i: Int): Screen {
        val (id, name) = STEPS[i]
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf<Row>()
            rows += Row(status(app, id).removeSuffix("  ►"))
            rows += Row("────────────────────")
            rows += body(app, id) { build() }
            rows += Row("────────────────────")
            rows += if (i + 1 < STEPS.size) Row("Next: ${STEPS[i + 1].second}", "►", action = { next(app, i) })
                else Row("Finish", "►", action = { next(app, i) })
            rows += Row("Back to the list of steps", action = { Settings.markSetup(app, id); app.back() })
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Step ${i + 1} of ${STEPS.size}: $name", emptyList())
        s.onShow = {
            currentStep = s; currentIndex = i
            build()
            Home.refreshAddons(app) { if (app.isTop(s)) build() }
        }
        return s
    }

    /** What each step offers: the existing screens, in order. */
    private fun body(app: MainActivity, id: String, refresh: () -> Unit): List<Row> = when (id) {
        "account" -> {
            val me = Profiles.current(app)
            listOfNotNull(
                Row("Signed in to Stremio as ${me?.name ?: "…"}."),
                Row("Use another profile, or make a new free account", "►", opens = { Profiles.chooser(app) }),
                me?.let { Row("This profile: kids lock, copy keys from another…", "►", opens = { Profiles.profileScreen(app, it) }) },
            )
        }
        "keys" -> listOf(
            Row("TMDB: where to watch, age ratings, Coming soon (recommended)."),
            Row("Debrid: what your streams play from. MDBList, Gemini: optional."),
            Row("Each key is checked as soon as you enter it."),
            Row("Your keys", "►", opens = { Settings.keysScreen(app) }),
            Row("Check all my keys", "►", opens = { KeysCheck.screen(app) }),
        )
        "rows" -> if (aioRows().isEmpty()) listOf(
            Row("Your Home rows and Browse come from AIOMetadata."),
            Row("Make my rows (Standard is ticked, Releases included)", "►", opens = { RowsSetup.screen(app) }),
        ) else {
            val missing = newRows()
            listOfNotNull(
                if (missing.isEmpty()) Row("Your rows have everything in the catalogue's latest categories.")
                else Row("New since your rows were made: ${missing.size} Releases rows."),
                Row("Bring my rows up to date", if (missing.isEmpty()) "keys, settings  ►" else "adds ${missing.size}  ►", action = { app.push(update(app)) }),
                Row("   Adds new rows (on), updates changed ones and your keys; keeps your choices."),
                Row("Change my categories", "►", opens = { RowsSetup.screen(app) }),
            )
        }
        "streams" -> if (Home.allAddons().any { StreamSetup.isAioStreams(it) }) listOf(
            Row("You have streams. To use the keys from step 2, update them:"),
            Row("Update my streams with my keys", "►", opens = { StreamsSetup.screen(app) }),
            Row("   (It's the first button there. 'Create' makes a second install: not needed.)"),
        ) else listOf(
            Row("Your streams come from AIOStreams with your debrid services (step 2)."),
            Row("Create my streams", "►", opens = { StreamsSetup.screen(app) }),
        ) + listOf(
            Row("Fail-safe: Torrentio, used only if AIOStreams fails", if (Home.allAddons().any { Failsafe.isTorrentio(it) }) "✓ added  ►" else "add  ►", opens = { StreamsSetup.screen(app) }),
        )
        "home" -> listOf(
            Row("Live TV on Home", if (Settings.liveTv(app)) "◄ Shown ►" else "◄ Hidden ►", action = { Settings.flip(app, "liveTv", true); Home.redraw(); refresh() }),
            Row("News on Home", if (Settings.newsOn(app)) "◄ Shown ►" else "◄ Hidden ►", action = { Settings.flip(app, "newsOn", true); Home.redraw(); refresh() }),
            Row("Coming soon in Browse", if (Settings.comingOn(app)) "◄ Shown ►" else "◄ Hidden ►", action = { Settings.flip(app, "comingOn", true); Home.redraw(); refresh() }),
            Row("Watched films in lists", if (Settings.hideWatched(app)) "◄ Hidden ►" else "◄ Shown with ● ►", action = { Settings.flip(app, "hideWatched", false); refresh() }),
            Row("Live TV channels and playlists", "►", opens = { Live.screen(app) }),
        )
        else -> listOf(
            Row("Checks your account, add-ons, rows, streams, speed and every key."),
            Row("Is everything working?", "►", action = {
                app.toast("Getting your add-ons…")
                Home.refreshAddons(app) { app.push(HealthScreen.screen(app)) }
            }),
        )
    }

    /** "Bring my rows up to date": new rows on, changed ones refreshed, keys in. Asks for the rows' password. */
    private fun update(app: MainActivity): Screen = RowPicker.entry(app) { _, l, pw ->
        val status = Row("Updating your rows…")
        val s = Screen("Bring my rows up to date", listOf(status))
        val added = RowSetup.bringUpToDate(l.config, Settings.tmdbKey(app), Settings.mdblistKey(app), Settings.geminiKey(app))
        Net.async({ AioRows.save(l, pw) }) { r ->
            status.label = r.fold({ (if (added > 0) "Done: $added new rows added and switched on." else "Done: your rows were already complete.") + " Keys and settings updated." }) { e ->
                "Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))
            }
            if (r.isSuccess) continueRow(app)?.let { s.rows = s.rows + it }
            if (r.isSuccess) Home.refreshAddons(app)
            app.refresh(s)
        }
        s
    }

    private fun finished(app: MainActivity): Screen {
        active = false
        password = null
        val rows = mutableListOf(Row("All done. Where everything lives:"), Row("────────────────────"))
        STEPS.forEach { (id, name) -> rows += Row(info(name, status(app, id))) }
        rows += Row("────────────────────")
        rows += Row("Keys: Settings › Your keys.  Rows and streams: Settings › Configuration.")
        rows += Row("Back to Settings", action = { app.back(); app.back() })
        return Screen("Set up Stremonino", rows)
    }
}
