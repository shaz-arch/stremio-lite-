package app.stremiolite

/** The Games corner (Switch profile → Games): 2048, Minesweeper, noughts & crosses and a film quiz. */
object GamesScreen {
    private fun stats(app: MainActivity) = GameStats(Settings.gameStats(app))
    private fun save(app: MainActivity, s: GameStats) = Settings.setGameStats(app, s.toString())

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build() {
            val st = stats(app)
            fun best(k: String, f: (Int) -> String) = st.int(k).let { if (it > 0) f(it) else "" }
            s.rows = listOf(
                Row("2048", best("2048") { "best $it  " } + "►", opens = { game(app, Play2048(stats(app))) }),
                Row("Minesweeper", best("minesTime") { "best ${it}s  " } + "►", opens = { game(app, PlayMines(stats(app))) }),
                Row("Noughts & crosses (easy)", "►", opens = { game(app, PlayNoughts(stats(app), hard = false)) }),
                Row("Noughts & crosses (hard)", "►", opens = { game(app, PlayNoughts(stats(app), hard = true)) }),
                Row("Film quiz", best("quiz") { "best $it/10  " } + "►", opens = { quiz(app) }),
                Row(""),
                Row("Scores are kept for this profile on this device."),
            )
            app.refresh(s)
        }
        s = Screen("Games", emptyList())
        s.onShow = { build() }
        return s
    }

    private val arrows = mapOf("up" to "↑ Up", "down" to "↓ Down", "left" to "← Left", "right" to "→ Right", "ok" to "OK", "play" to "Flag")

    /** A board game: the status line, the board, how to play, and (on a phone) buttons to tap. */
    private fun game(app: MainActivity, g: GamePlay): Screen {
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf(Row(g.status()), Row(""))
            g.lines().forEach { rows += Row("  $it") }
            rows += Row("")
            rows += Row(g.help(app.isPhone))
            if (app.isPhone) {
                rows += Row("────────────────────")
                g.phoneKeys.forEach { k -> rows += Row(arrows[k] ?: k, action = { press(app, g, k); build() }) }
                rows += Row("New game", action = { g.restart(); build() })
            }
            s.rows = rows
            app.refresh(s)
        }
        s = Screen(g.title, emptyList())
        s.onShow = { build(); s.moveTo = 0 }
        // The remote's arrows and OK go to the game, not the list.
        if (!app.isPhone) s.onKey = { k -> press(app, g, k); build() }
        return s
    }

    private fun press(app: MainActivity, g: GamePlay, k: String) {
        val wasOver = g.over
        g.press(k)
        // Scores are saved the moment a game ends.
        if (g.over && !wasOver) save(app, g.stats)
    }

    // ---- film quiz ---------------------------------------------------------------------------

    private fun quiz(app: MainActivity): Screen {
        lateinit var s: Screen
        var questions: List<Games.Question>? = null
        var i = 0
        var right = 0
        var picked: Int? = null

        fun build() {
            val qs = questions
            val rows = mutableListOf<Row>()
            when {
                qs == null -> rows += Row("Picking films from your catalogues…")
                qs.isEmpty() -> rows += Row("Not enough films with descriptions in your catalogues.")
                i >= qs.size -> {
                    val st = stats(app)
                    val record = st.best("quiz", right)
                    save(app, st)
                    rows += Row("You got $right out of ${qs.size}" + if (record) " · new best!" else "")
                    rows += Row("Play again", action = { questions = null; i = 0; right = 0; picked = null; load(app) { questions = it; build() } })
                }
                else -> {
                    val q = qs[i]
                    rows += Row("Question ${i + 1} of ${qs.size} · score $right")
                    rows += Row(listOf(q.year, q.genres).filter { it.isNotBlank() }.joinToString(" · "))
                    wrap(q.clue, if (narrow) 34 else 60).forEach { rows += Row(it) }
                    rows += Row("────────────────────")
                    q.choices.forEachIndexed { n, name ->
                        val mark = when {
                            picked == null -> ""
                            n == q.answer -> "✓"
                            n == picked -> "✗"
                            else -> ""
                        }
                        rows += Row(name, mark, action = {
                            if (picked != null) return@Row
                            picked = n
                            if (n == q.answer) right++
                            build()
                        })
                    }
                    if (picked != null) {
                        rows += Row("")
                        rows += Row(if (picked == q.answer) "Right!" else "It was ${q.choices[q.answer]}")
                        rows += Row(if (i + 1 < qs.size) "Next question" else "See your score", "►", action = { i++; picked = null; build() })
                        s.moveTo = rows.size - 1
                    } else {
                        s.moveTo = rows.size - 4
                    }
                }
            }
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Film quiz", listOf(Row("Picking films from your catalogues…")))
        s.onShow = { if (questions == null) load(app) { r -> questions = r; build() } else build() }
        return s
    }

    private fun load(app: MainActivity, done: (List<Games.Question>) -> Unit) {
        val cats = Games.quizCatalogues(Home.allAddons())
        Net.async({
            val metas = cats.flatMap { (a, c) -> runCatching { MetaParse.page(a, c) }.getOrDefault(emptyList()) }
            Games.quiz(metas)
        }) { r -> done(r.getOrDefault(emptyList())) }
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (w in text.split(' ')) {
            if (line.isEmpty()) line = w
            else if (line.length + 1 + w.length <= width) line += " $w"
            else { out += line; line = w }
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
