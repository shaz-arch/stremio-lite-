package app.stremiolite

import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import java.net.URLEncoder

/** Search every add-on catalogue that supports it, all at once, results grouped by catalogue. */
object Search {
    private var lastQuery = ""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

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
                app.push(results(app, q))
            }
        }
        dialog = AlertDialog.Builder(app)
            .setTitle("Search")
            .setView(input)
            .setPositiveButton("Search") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) { go(); true } else false
        }
        dialog.show()
        input.requestFocus()
    }

    private fun results(app: MainActivity, query: String): Screen {
        val sources = Home.allAddons().filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { it.searchable }.map { c -> a to c }
        }
        // Each catalogue's results, kept in catalogue order so the list doesn't jump about.
        val results = arrayOfNulls<List<Catalogs.Meta>>(sources.size)
        val errors = mutableListOf<String>()
        var answered = 0
        var started = false
        val summary = Row("")
        lateinit var screen: Screen

        fun rebuild() {
            summary.label = when {
                sources.isEmpty() -> "None of your add-ons supports search."
                answered < sources.size -> "Searching ${sources.size} catalogues… $answered done"
                else -> "Results for \"$query\""
            }
            val rows = mutableListOf(summary, Row("New search", action = { start(app) }))
            errors.forEach { rows += Row(it) }
            val seen = HashSet<String>()
            sources.forEachIndexed { i, (a, c) ->
                val metas = results[i]?.filter { seen.add(it.type + ":" + it.id) } ?: return@forEachIndexed
                if (metas.isEmpty()) return@forEachIndexed
                rows += Row("──── ${c.name} · ${Catalogs.typeLabel(c.type)} ────")
                metas.take(25).forEach { m ->
                    rows += Row(m.name, Catalogs.detail(m), opens = { Details.screen(app, a, m) }, play = {
                        if (m.type == "movie") Streams.prefetch(app, m.type, m.id)
                        app.push(Details.screen(app, a, m, autoPlay = true))
                    })
                }
            }
            if (answered >= sources.size && rows.size == 2 + errors.size && sources.isNotEmpty()) {
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
