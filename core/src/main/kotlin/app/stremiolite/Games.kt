package app.stremiolite

import kotlin.random.Random

/**
 * Little games for the Games corner: 2048, Minesweeper, noughts & crosses and a film quiz.
 * Only the rules live here; each app draws them as lines of text and passes the keys in
 * ("up", "down", "left", "right", "ok", "play").
 */
object Games {
    /** A cell drawn 3 columns wide, with brackets round the one under the cursor. */
    private fun cell(text: String, cursor: Boolean) = if (cursor) "[$text]" else " $text "

    // ---- 2048 ----------------------------------------------------------------------------------

    class Twenty48(private val rnd: Random = Random.Default) {
        val board = IntArray(16)
        var score = 0
            private set
        var won = false
            private set

        init { add(); add() }

        private fun add() {
            val free = (0 until 16).filter { board[it] == 0 }
            if (free.isEmpty()) return
            board[free[rnd.nextInt(free.size)]] = if (rnd.nextInt(10) == 0) 4 else 2
        }

        /** Slides one line towards its start; returns the new line. */
        private fun slide(line: IntArray): IntArray {
            val tiles = line.filter { it != 0 }
            val out = ArrayList<Int>()
            var i = 0
            while (i < tiles.size) {
                if (i + 1 < tiles.size && tiles[i] == tiles[i + 1]) {
                    val v = tiles[i] * 2
                    out += v; score += v
                    if (v == 2048) won = true
                    i += 2
                } else { out += tiles[i]; i++ }
            }
            while (out.size < 4) out += 0
            return out.toIntArray()
        }

        /** Moves the tiles; true if anything moved (then a new tile appears). */
        fun move(dir: String): Boolean {
            val before = board.copyOf()
            for (k in 0 until 4) {
                val idx = when (dir) {
                    "left" -> IntArray(4) { k * 4 + it }
                    "right" -> IntArray(4) { k * 4 + 3 - it }
                    "up" -> IntArray(4) { it * 4 + k }
                    "down" -> IntArray(4) { (3 - it) * 4 + k }
                    else -> return false
                }
                val line = slide(IntArray(4) { board[idx[it]] })
                for (j in 0 until 4) board[idx[j]] = line[j]
            }
            val moved = !before.contentEquals(board)
            if (moved) add()
            return moved
        }

        val over: Boolean get() {
            if (board.any { it == 0 }) return false
            for (r in 0 until 4) for (c in 0 until 4) {
                val v = board[r * 4 + c]
                if (c < 3 && board[r * 4 + c + 1] == v) return false
                if (r < 3 && board[(r + 1) * 4 + c] == v) return false
            }
            return true
        }

        val best: Int get() = board.maxOrNull() ?: 0

        fun lines(): List<String> {
            val bar = "+------+------+------+------+"
            val out = mutableListOf(bar)
            for (r in 0 until 4) {
                out += "|" + (0 until 4).joinToString("|") { c ->
                    val v = board[r * 4 + c]
                    (if (v == 0) "" else v.toString()).padStart(5).padEnd(6)
                } + "|"
                out += bar
            }
            return out
        }
    }

    // ---- Minesweeper -------------------------------------------------------------------------

    class Mines(val w: Int = 9, val h: Int = 9, val count: Int = 10, private val rnd: Random = Random.Default) {
        private val mine = BooleanArray(w * h)
        val open = BooleanArray(w * h)
        val flag = BooleanArray(w * h)
        var x = w / 2
        var y = h / 2
        private var laid = false
        var lost = false
            private set
        val won: Boolean get() = !lost && (0 until w * h).all { mine[it] || open[it] }
        val flags: Int get() = flag.count { it }

        private fun around(i: Int): List<Int> {
            val cx = i % w; val cy = i / w
            val out = ArrayList<Int>(8)
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx; val ny = cy + dy
                if (nx in 0 until w && ny in 0 until h) out += ny * w + nx
            }
            return out
        }

        private fun near(i: Int) = around(i).count { mine[it] }

        /** Mines go down on the first dig, never on or next to it, so the first dig is always safe. */
        private fun lay(safe: Int) {
            val keep = around(safe).toSet() + safe
            val spots = (0 until w * h).filter { it !in keep }.shuffled(java.util.Random(rnd.nextLong()))
            spots.take(count).forEach { mine[it] = true }
            laid = true
        }

        fun key(k: String) {
            if (lost || won) return
            when (k) {
                "up" -> y = (y - 1).coerceAtLeast(0)
                "down" -> y = (y + 1).coerceAtMost(h - 1)
                "left" -> x = (x - 1).coerceAtLeast(0)
                "right" -> x = (x + 1).coerceAtMost(w - 1)
                "play" -> { val i = y * w + x; if (!open[i]) flag[i] = !flag[i] }
                "ok" -> dig(y * w + x)
            }
        }

        private fun dig(i: Int) {
            if (flag[i] || open[i]) return
            if (!laid) lay(i)
            if (mine[i]) { lost = true; open[i] = true; return }
            // Open this square, and spread out from any with no mines around.
            val todo = ArrayDeque(listOf(i))
            while (todo.isNotEmpty()) {
                val j = todo.removeFirst()
                if (open[j] || flag[j]) continue
                open[j] = true
                if (near(j) == 0) around(j).filter { !open[it] && !mine[it] }.forEach { todo.addLast(it) }
            }
        }

        fun lines(): List<String> = (0 until h).map { r ->
            (0 until w).joinToString("") { c ->
                val i = r * w + c
                val t = when {
                    flag[i] && !(lost && mine[i]) -> "F"
                    (lost && mine[i]) -> "*"
                    !open[i] -> "·"
                    near(i) == 0 -> " "
                    else -> near(i).toString()
                }
                cell(t, r == y && c == x)
            }
        }
    }

    // ---- noughts & crosses ---------------------------------------------------------------------

    /** You're X and go first; the app is O. [hard]: it never loses. */
    class Noughts(val hard: Boolean, private val rnd: Random = Random.Default) {
        val b = CharArray(9) { ' ' }
        var at = 4
        var winner: Char? = null
            private set
        val draw: Boolean get() = winner == null && b.none { it == ' ' }

        private val lines = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )

        private fun won(board: CharArray): Char? =
            lines.firstOrNull { l -> board[l[0]] != ' ' && board[l[0]] == board[l[1]] && board[l[1]] == board[l[2]] }?.let { board[it[0]] }

        fun key(k: String) {
            if (winner != null || draw) return
            when (k) {
                "up" -> if (at >= 3) at -= 3
                "down" -> if (at < 6) at += 3
                "left" -> if (at % 3 > 0) at -= 1
                "right" -> if (at % 3 < 2) at += 1
                "ok", "play" -> if (b[at] == ' ') {
                    b[at] = 'X'
                    winner = won(b)
                    if (winner == null && !draw) { b[reply()] = 'O'; winner = won(b) }
                }
            }
        }

        /** Hard: the best move (minimax). Easy: the best move half the time, otherwise any free square. */
        private fun reply(): Int {
            val free = (0 until 9).filter { b[it] == ' ' }
            if (!hard && rnd.nextBoolean()) return free[rnd.nextInt(free.size)]
            return free.maxByOrNull { i -> b[i] = 'O'; val s = score(false, 0); b[i] = ' '; s }!!
        }

        private fun score(oTurn: Boolean, depth: Int): Int {
            when (won(b)) { 'O' -> return 10 - depth; 'X' -> return depth - 10 }
            val free = (0 until 9).filter { b[it] == ' ' }
            if (free.isEmpty()) return 0
            val scores = free.map { i -> b[i] = if (oTurn) 'O' else 'X'; val s = score(!oTurn, depth + 1); b[i] = ' '; s }
            return if (oTurn) scores.max() else scores.min()
        }

        fun lines(): List<String> = (0 until 3).flatMap { r ->
            val row = (0 until 3).joinToString("│") { c -> cell(b[r * 3 + c].toString(), r * 3 + c == at && winner == null && !draw) }
            if (r < 2) listOf(row, "───┼───┼───") else listOf(row)
        }
    }

    // ---- film quiz ---------------------------------------------------------------------------

    class Question(val clue: String, val year: String, val genres: String, val choices: List<String>, val answer: Int)

    /**
     * Questions from films and shows in your catalogues: a clue (the plot, with the title blanked
     * out), the year and genres, and four names to pick from.
     */
    fun quiz(metas: List<Meta>, count: Int = 10, rnd: Random = Random.Default): List<Question> {
        val pool = metas.filter { it.name.isNotBlank() && it.description.length >= 40 }.distinctBy { it.name.lowercase() }
        if (pool.size < 4) return emptyList()
        return pool.shuffled(rnd).take(count).map { m ->
            val wrong = pool.filter { it.name != m.name }.let { o -> o.filter { it.type == m.type }.takeIf { it.size >= 3 } ?: o }.shuffled(rnd).take(3).map { it.name }
            val choices = (wrong + m.name).shuffled(rnd)
            Question(blank(m.description, m.name), m.year, m.genres.take(3).joinToString(", "), choices, choices.indexOf(m.name))
        }
    }

    /** The plot with the title's words blanked out, cut to a couple of sentences. */
    private fun blank(text: String, title: String): String {
        var t = text.replace(Regex("\\s+"), " ").trim()
        val words = title.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 3 && it.lowercase() !in setOf("the", "and", "for", "with") }
        for (w in words + title) t = t.replace(Regex("\\b" + Regex.escape(w) + "\\b", RegexOption.IGNORE_CASE), "▒▒▒")
        if (t.length > 220) t = t.take(219).substringBeforeLast(' ') + "…"
        return t
    }

    /** Catalogues worth quizzing on: plain film and show lists, popular or trending ones first. */
    fun quizCatalogues(addons: List<Addon>): List<Pair<Addon, Addon.Catalog>> =
        addons.flatMap { a -> a.catalogs.filter { (it.type == "movie" || it.type == "series") && Folders.openable(it) }.map { a to it } }
            .sortedBy { (_, c) -> if (Regex("popular|trending|top", RegexOption.IGNORE_CASE).containsMatchIn(c.name)) 0 else 1 }
            .take(4)
}
