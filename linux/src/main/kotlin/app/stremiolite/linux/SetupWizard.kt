package app.stremiolite.linux

import app.stremiolite.AioRows
import app.stremiolite.RowSetup
import app.stremiolite.StreamSetup

/**
 * Settings › Set up Stremonino: the whole setup in order (account, keys, rows, streams, Home,
 * a check), using the screens Settings already has. One password for rows and streams.
 */
object SetupWizard {
    var password: String? = null
        private set
    private var active = false
    private var currentStep: Screen? = null
    private var currentIndex = 0

    /** On a "Done" page reached from a setup step: back to that step, then on to the next. */
    fun continueRow(): Row? {
        val step = currentStep ?: return null
        if (!active || !App.inStack(step)) return null
        return Row("Continue setting up", "►", action = {
            while (!App.isTop(step) && App.inStack(step)) App.back()
            next(currentIndex)
        })
    }

    private fun next(i: Int) {
        mark(STEPS[i].first)
        App.back()
        App.push(if (i + 1 < STEPS.size) step(i + 1) else finished())
    }

    fun offer(v: String) { if (active && password == null) password = v }

    private val STEPS = listOf("account" to "Account", "keys" to "Keys", "rows" to "Rows", "streams" to "Streams", "home" to "Home & extras", "check" to "Check")

    private fun aioRows() = Home.all().filter { AioRows.isAio(it) }
    private fun newRows(): List<String> = (aioRows().firstOrNull { it.stickRows } ?: aioRows().firstOrNull())
        ?.let { a -> RowSetup.newRowsFromIds(a.catalogs.map { it.id }.toSet()) } ?: emptyList()

    private fun keyNames(): List<String> = listOfNotNull(if (Prefs.tmdbKey.isNotBlank()) "TMDB" else null) +
        StreamSetup.names(Prefs.debridKeys) + listOfNotNull(if (Prefs.mdblistKey.isNotBlank()) "MDBList" else null, if (Prefs.geminiKey.isNotBlank()) "Gemini" else null)

    private fun status(step: String): String = when (step) {
        "account" -> "✓ " + (Profiles.current()?.name ?: "signed in")
        "keys" -> keyNames().let { if (it.isEmpty()) "○ none yet" else "✓ " + it.joinToString(", ") }
        "rows" -> when { aioRows().isEmpty() -> "○ none yet"; newRows().isNotEmpty() -> "◐ ${newRows().size} new rows to add"; else -> "✓ up to date" }
        "streams" -> if (Home.all().none { StreamSetup.isAioStreams(it) }) "○ none yet"
            else "✓ " + StreamSetup.names(Prefs.debridKeys).joinToString(", then ").ifEmpty { "on your account" }
        else -> if (step in Prefs.setupDone) "✓ done" else "○ not yet"
    }

    private fun mark(step: String) { Prefs.setupDone = Prefs.setupDone + step }

    fun screen(): Screen {
        active = true
        password = null
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf(
                Row("Everything Stremonino can set up, in order. Skip any step; nothing you already have is replaced without asking."),
                Row("────────────────────"),
            )
            STEPS.forEachIndexed { i, (id, name) -> rows += Row(name, status(id) + "  ►", action = { App.push(step(i)) }) }
            rows += Row("────────────────────")
            rows += Row("Start from step 1", "►", action = { App.push(step(0)) })
            s.rows = rows
            App.refresh()
        }
        s = Screen("Set up Stremonino", emptyList())
        s.onShow = { build(); Home.refresh { if (App.isTop(s)) build() } }
        s.pos = 2
        return s
    }

    private fun step(i: Int): Screen {
        val (id, name) = STEPS[i]
        lateinit var s: Screen
        lateinit var build: () -> Unit
        build = {
            val rows = mutableListOf(Row(status(id)), Row("────────────────────"))
            rows += body(id) { build() }
            rows += Row("────────────────────")
            rows += if (i + 1 < STEPS.size) Row("Next: ${STEPS[i + 1].second}", "►", action = { next(i) })
                else Row("Finish", "►", action = { next(i) })
            rows += Row("Back to the list of steps", action = { mark(id); App.back() })
            s.rows = rows
            App.refresh()
        }
        s = Screen("Step ${i + 1} of ${STEPS.size}: $name", emptyList())
        s.onShow = { currentStep = s; currentIndex = i; build(); Home.refresh { if (App.isTop(s)) build() } }
        return s
    }

    private fun shown(on: Boolean) = if (on) "◄ Shown ►" else "◄ Hidden ►"

    private fun body(id: String, refresh: () -> Unit): List<Row> = when (id) {
        "account" -> listOfNotNull(
            Row("Signed in to Stremio as ${Profiles.current()?.name ?: "…"}."),
            Row("Use another profile, or make a new free account", "►", opens = { Profiles.chooser() }),
            Profiles.current()?.let { Row("This profile: kids lock, copy keys from another…", "►", opens = { Profiles.profile(it) }) },
        )
        "keys" -> listOf(
            Row("TMDB: where to watch, age ratings, Coming soon (recommended). Debrid: what your streams play from. MDBList, Gemini: optional."),
            Row("Each key is checked as soon as you enter it."),
            Row("Your keys", "►", opens = { keysScreen() }),
            Row("Check all my keys", "►", opens = { KeysCheck.screen() }),
        )
        "rows" -> if (aioRows().isEmpty()) listOf(
            Row("Your Home rows and Browse come from AIOMetadata."),
            Row("Make my rows (Standard is ticked, Releases included)", "►", opens = { RowsSetup.screen() }),
        ) else {
            val missing = newRows()
            listOf(
                Row(if (missing.isEmpty()) "Your rows have everything in the catalogue's latest categories." else "New since your rows were made: ${missing.size} Releases rows."),
                Row("Bring my rows up to date", if (missing.isEmpty()) "keys, settings  ►" else "adds ${missing.size}  ►", action = { App.push(update()) }),
                Row("   Adds new rows (on), updates changed ones and your keys; keeps your choices."),
                Row("Change my categories", "►", opens = { RowsSetup.screen() }),
            )
        }
        "streams" -> if (Home.all().any { StreamSetup.isAioStreams(it) }) listOf(
            Row("You have streams. To use the keys from step 2, update them:"),
            Row("Update my streams with my keys", "►", opens = { StreamsSetup.screen() }),
            Row("   (It's the first button there. 'Create' makes a second install: not needed.)"),
        ) else listOf(
            Row("Your streams come from AIOStreams with your debrid services (step 2)."),
            Row("Create my streams", "►", opens = { StreamsSetup.screen() }),
        )
        "home" -> listOf(
            Row("Live TV on Home", shown(Prefs.liveTv), action = { Prefs.liveTv = !Prefs.liveTv; Prefs.syncOut(); Home.redraw(); refresh() }),
            Row("News on Home", shown(Prefs.newsOn), action = { Prefs.newsOn = !Prefs.newsOn; Prefs.syncOut(); Home.redraw(); refresh() }),
            Row("Coming soon in Browse", shown(Prefs.comingOn), action = { Prefs.comingOn = !Prefs.comingOn; Prefs.syncOut(); Home.redraw(); refresh() }),
            Row("Watched films in lists", if (Prefs.hideWatched) "◄ Hidden ►" else "◄ Shown with ● ►", action = { Prefs.hideWatched = !Prefs.hideWatched; Prefs.syncOut(); refresh() }),
            Row("Live TV channels and playlists", "►", opens = { Live.screen() }),
        )
        else -> listOf(
            Row("Checks your account, add-ons, rows, streams, speed and every key."),
            Row("Is everything working?", "►", action = { App.toast("Getting your add-ons…"); Home.refresh { App.push(HealthScreen.screen()) } }),
        )
    }

    private fun update(): Screen = RowPicker.entry { _, l, pw ->
        val status = Row("Updating your rows…")
        val s = Screen("Bring my rows up to date", listOf(status))
        val added = RowSetup.bringUpToDate(l.config, Prefs.tmdbKey, Prefs.mdblistKey, Prefs.geminiKey)
        App.async({ AioRows.save(l, pw) }) { r ->
            status.label = r.fold({ (if (added > 0) "Done: $added new rows added and switched on." else "Done: your rows were already complete.") + " Keys and settings updated." }) { e ->
                "Couldn't save: " + (if (e is AioRows.Failed) e.message else Net.describe(e))
            }
            if (r.isSuccess) { Home.refresh(); continueRow()?.let { s.rows = s.rows + it } }
            App.refresh()
        }
        s
    }

    private fun finished(): Screen {
        active = false
        password = null
        val rows = mutableListOf(Row("All done. Where everything lives:"), Row("────────────────────"))
        STEPS.forEach { (id, name) -> rows += Row(info(name, status(id))) }
        rows += Row("────────────────────")
        rows += Row("Keys: Settings › Your keys.  Rows and streams: Settings › Rows & streams.")
        rows += Row("Back to Settings", action = { App.back(); App.back() })
        return Screen("Set up Stremonino", rows)
    }
}
