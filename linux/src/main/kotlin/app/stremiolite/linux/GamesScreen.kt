package app.stremiolite.linux

import app.stremiolite.GamePlay
import app.stremiolite.GameStats
import app.stremiolite.Games
import app.stremiolite.MetaParse
import app.stremiolite.Play2048
import app.stremiolite.PlayMines
import app.stremiolite.PlayNoughts

/** The Games corner (Switch profile → Games): 2048, Minesweeper, noughts & crosses and a film quiz. */
object GamesScreen {
    private fun stats() = GameStats(Prefs.games)
    private fun save(s: GameStats) { Prefs.games = s.toString() }

    fun screen(): Screen {
        lateinit var s: Screen
        fun build() {
            val st = stats()
            fun best(k: String, f: (Int) -> String) = st.int(k).let { if (it > 0) f(it) else "" }
            s.rows = listOf(
                Row("2048", best("2048") { "best $it  " } + "►", opens = { game(Play2048(stats())) }),
                Row("Minesweeper", best("minesTime") { "best ${it}s  " } + "►", opens = { game(PlayMines(stats())) }),
                Row("Noughts & crosses (easy)", "►", opens = { game(PlayNoughts(stats(), hard = false)) }),
                Row("Noughts & crosses (hard)", "►", opens = { game(PlayNoughts(stats(), hard = true)) }),
                Row("Film quiz", best("quiz") { "best $it/10  " } + "►", opens = { quiz() }),
                Row(""),
                Row("Scores are kept for this profile on this computer."),
            )
            App.refresh()
        }
        s = Screen("Games", emptyList())
        s.onShow = { build() }
        return s
    }

    private fun game(g: GamePlay): Screen {
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf(Row(g.status()), Row(""))
            g.lines().forEach { rows += Row("  $it") }
            rows += Row("")
            rows += Row(g.help(false))
            rows += Row("Esc: back to Games")
            s.rows = rows
            s.pos = 0
            App.refresh()
        }
        s = Screen(g.title, emptyList())
        s.onShow = { build() }
        s.onKey = { k ->
            val wasOver = g.over
            g.press(k)
            if (g.over && !wasOver) save(g.stats)
            build()
        }
        return s
    }

    private fun quiz(): Screen {
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
                    val st = stats()
                    val record = st.best("quiz", right)
                    save(st)
                    rows += Row("You got $right out of ${qs.size}" + if (record) " · new best!" else "")
                    rows += Row("Play again", action = { questions = null; i = 0; right = 0; picked = null; load { questions = it; build() } })
                }
                else -> {
                    val q = qs[i]
                    rows += Row("Question ${i + 1} of ${qs.size} · score $right")
                    rows += Row(listOf(q.year, q.genres).filter { it.isNotBlank() }.joinToString(" · "))
                    wrap(q.clue, (App.columns() - 6).coerceIn(30, 90)).forEach { rows += Row(it) }
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
            App.refresh()
        }
        s = Screen("Film quiz", listOf(Row("Picking films from your catalogues…")))
        s.onShow = { if (questions == null) load { r -> questions = r; build() } else build() }
        return s
    }

    private fun load(done: (List<Games.Question>) -> Unit) {
        val cats = Games.quizCatalogues(Home.all())
        App.async({
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
