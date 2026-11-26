package app.stremiolite

import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.net.URLEncoder

/** Search every add-on catalogue that supports it, all at once: best matches, then collections, films and shows. */
object Search {
    private var lastQuery = ""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- recent searches (last 8, newest first) ----

    private fun prefs(c: android.content.Context) = Profiles.prefsFor(c, "search")

    fun recent(c: android.content.Context): List<String> =
        prefs(c).getString("recent", "")!!.split('\n').filter { it.isNotBlank() }

    private fun remember(c: android.content.Context, q: String) {
        val list = (listOf(q) + recent(c).filter { !it.equals(q, ignoreCase = true) }).take(8)
        prefs(c).edit().putString("recent", list.joinToString("\n")).apply()
    }

    /** Home's Search line: type a new search, or press OK on a recent one. */
    fun menu(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val rows = mutableListOf(Row("New search…", "☰", action = { start(app) }))
            // Ask AI once a Gemini key is set on this profile (it lives in your rows' config).
            if (Settings.geminiKey(app).isNotBlank()) rows += Row("Ask AI…", "describe what you want  ►", action = { askAi(app) })
            val recent = recent(app)
            if (recent.isNotEmpty()) {
                rows += Row("──── Recent searches ────")
                recent.forEach { q -> rows += Row(q, "►", action = { remember(app, q); app.push(results(app, q)) }) }
                rows += Row("────────────────────")
                rows += Row("Clear recent searches", action = {
                    prefs(app).edit().remove("recent").apply()
                    screen.rows = build()
                    screen.moveTo = 0
                    app.refresh(screen)
                })
            }
            return rows
        }
        screen = Screen("Search", build())
        screen.onShow = { screen.rows = build(); app.refresh(screen) }
        return screen
    }

    /** "Ask AI": a description in your own words, answered by AIOMetadata's AI Search. */
    private fun askAi(app: MainActivity) {
        val input = EditText(app).apply {
            hint = "e.g. 90s heist films with a twist"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val b = AlertDialog.Builder(app)
            .setTitle("Ask AI")
            .setMessage("Describe what you'd like to watch. Your words go to Google's Gemini through your AIOMetadata.")
            .setPositiveButton("Ask") { _, _ ->
                val q = input.text.toString().trim()
                if (q.length >= 3) app.push(aiResults(app, q))
            }
            .setNegativeButton("Cancel", null)
        PhoneTyping.show(app, b, input, "Ask AI")
    }

    private fun aiResults(app: MainActivity, query: String): Screen {
        lateinit var s: Screen
        var found: List<Pair<Addon, Meta>>? = null
        var error: String? = null
        fun build() {
            val rows = mutableListOf(Row("\"$query\""), Row("Ask again", action = { askAi(app) }), Row("────────────────────"))
            when {
                error != null -> { rows += Row("Couldn't ask: $error"); rows += Row("Check my AI setup", "►", opens = { AiCheck.screen(app) }) }
                found == null -> rows += Row("Asking AI… (this can take a few seconds)")
                found!!.isEmpty() -> rows += Row("AI found nothing for that. Try describing it differently.")
                else -> found!!.forEach { rows += row(app, it) }
            }
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Ask AI", listOf(Row("Asking AI…")))
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                Net.async({ AiSearch.ask(Home.allAddons(), query) }) { r ->
                    r.onSuccess { found = it; RatingFill.fill(app, s, it.map { p -> p.second }) { if (app.inStack(s)) build() } }
                        .onFailure { error = it.message ?: Net.describe(it) }
                    build()
                }
            }
            build()
        }
        return s
    }

    /** Words heard by voice search: straight to the results. */
    fun spoken(app: MainActivity, q: String) {
        lastQuery = q
        remember(app, q)
        app.push(results(app, q))
    }

    /** Opens the TV keyboard, then the results screen. */
    fun start(app: MainActivity) {
        val input = EditText(app).apply {
            hint = "Film or show name"
            setText(lastQuery)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
        }
        lateinit var dialog: AlertDialog
        fun go() {
            val q = input.text.toString().trim()
            dialog.dismiss()
            if (q.length >= 2) {
                lastQuery = q
                remember(app, q)
                app.push(results(app, q))
            }
        }
        val b = AlertDialog.Builder(app)
            .setTitle("Search")
            .setPositiveButton("Search") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
        if (Voice.available(app)) b.setNeutralButton("Speak") { _, _ -> Voice.listen(app) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) { go(); true } else false
        }
        dialog = PhoneTyping.show(app, b, input, "Search Stremonino", submit = { go() })
        input.requestFocus()
    }

    /** Ids found to be film collections in the current search. */
    private val collections = HashSet<String>()

    private fun row(app: MainActivity, p: Pair<Addon, Meta>): Row {
        val (a, m) = p
        val detail = if (m.id in collections) listOf(m.year, "Collection").filter { it.isNotBlank() }.joinToString("  ") else Catalogs.detail(m)
        lateinit var r: Row
        r = Row(Marks.title(History.marks(app)[m.id], m.name), detail, opens = { Details.screen(app, a, m) }, play = {
            BasketList.toggle(app, m.id, m.type, m.name, m.year)
            r.label = Marks.title(History.marks(app)[m.id], m.name); app.refreshTop()
        })
        return r
    }

    private fun results(app: MainActivity, query: String): Screen {
        val sources = Home.rowAddons().filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { it.searchable && !AiSearch.isAiCatalog(it) }.map { c -> a to c }
        }
        // Each catalogue's results, kept in catalogue order so the list doesn't jump about.
        val results = arrayOfNulls<List<Meta>>(sources.size)
        val errors = mutableListOf<String>()
        var answered = 0
        var started = false
        val summary = Row("")
        lateinit var screen: Screen
        var channels: List<LiveTv.Channel> = emptyList()
        var rated = false

        fun rebuild() {
            summary.label = when {
                sources.isEmpty() -> "None of your add-ons supports search."
                answered < sources.size -> "Searching ${sources.size} catalogues… $answered done"
                else -> "Results for \"$query\""
            }
            val rows = mutableListOf(summary, Row("New search", action = { start(app) }))
            errors.forEach { rows += Row(it) }
            if (channels.isNotEmpty()) {
                rows += Row("──── Live TV ────")
                channels.take(5).forEach { rows += Live.searchRow(app, it) }
                if (channels.size > 5) rows += Row("All matching channels", "${channels.size}  ►", opens = { Screen("Channels: $query", channels.map { Live.searchRow(app, it) }) })
            }
            val seen = HashSet<String>()
            val all = mutableListOf<Pair<Addon, Meta>>()
            sources.forEachIndexed { i, (a, c) ->
                results[i]?.forEach { m ->
                    if (seen.add(m.type + ":" + m.id)) {
                        all += a to m
                        if (SearchRank.isCollection(m, a.name, c.type)) collections += m.id
                    }
                }
            }
            val g = SearchRank.group(all.filter { KidsGuard.allows(app, it.second.id) }, query, { it.second }) { it.second.id in collections }
            if (g.best.isNotEmpty()) {
                rows += Row("──── Best matches ────")
                g.best.forEach { rows += row(app, it) }
            }
            if (all.isNotEmpty()) rows += Row("────────────────────")
            fun group(name: String, list: List<Pair<Addon, Meta>>) {
                if (list.isNotEmpty()) rows += Row(name, "${list.size}  ►", opens = { Screen("$name: $query", list.map { row(app, it) }) })
            }
            group("Collections", g.collections)
            group("Films", g.films)
            group("Shows", g.shows)
            group("Other", g.other)
            if (answered >= sources.size && !rated) { rated = true; RatingFill.fill(app, screen, all.map { it.second }) { if (app.inStack(screen)) rebuild() } }
            if (answered >= sources.size && all.isEmpty() && channels.isEmpty() && sources.isNotEmpty()) {
                rows += Row("Nothing found.")
            }
            screen.rows = rows
            app.refresh(screen)
        }

        screen = Screen("Search: $query", listOf(summary))
        screen.position = 0
        screen.onShow = {
            if (!started) {
                started = true
                rebuild()
                Live.search(app, query) { channels = it; rebuild() }
                sources.forEachIndexed { i, (a, c) ->
                    val url = a.base + "/catalog/" + enc(c.type) + "/" + enc(c.id) + "/search=" + enc(query) + ".json"
                    Net.async({ Catalogs.parse(Net.get(url, timeoutMs = 12_000)) }) { r ->
                        answered++
                        r.onSuccess { results[i] = it }
                            .onFailure { e -> errors += "${c.name}: ${Net.describe(e)}" }
                        rebuild()
                    }
                }
            }
        }
        return screen
    }
}
