package app.stremiolite

import org.json.JSONObject

/**
 * A game as each app shows it: a status line, the board as lines of text, and keys in.
 * When a game ends, OK starts a new one. Scores go into [GameStats].
 */
abstract class GamePlay(val title: String, val stats: GameStats) {
    abstract fun status(): String
    abstract fun lines(): List<String>
    abstract fun key(k: String)
    abstract val over: Boolean
    abstract fun restart()

    /** One line of how to play, for the remote/keyboard or for phone taps. */
    abstract fun help(phone: Boolean): String

    /** The buttons a phone shows (no arrow keys there), as key names. */
    open val phoneKeys: List<String> = listOf("up", "down", "left", "right", "ok")

    /** Handles a key: when the game is over, OK (or Play) starts a new one. */
    fun press(k: String) {
        if (over) { if (k == "ok" || k == "play") restart(); return }
        key(k)
        if (over) ended()
    }

    protected abstract fun ended()
}

/** Best scores and tallies, kept per profile on each device. */
class GameStats(json: String?) {
    val o: JSONObject = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
    fun int(k: String) = o.optInt(k)
    fun best(k: String, v: Int, lowerIsBetter: Boolean = false): Boolean {
        val old = o.optInt(k)
        val better = old == 0 || (if (lowerIsBetter) v < old else v > old)
        if (better) o.put(k, v)
        return better
    }
    fun add(k: String) { o.put(k, o.optInt(k) + 1) }
    override fun toString(): String = o.toString()
}

class Play2048(stats: GameStats) : GamePlay("2048", stats) {
    private var g = Games.Twenty48()
    private var record = false
    override val over get() = g.over
    override fun restart() { g = Games.Twenty48(); record = false }
    override fun key(k: String) { if (k in listOf("up", "down", "left", "right")) g.move(k) }
    override fun ended() { record = stats.best("2048", g.score) }
    override fun lines() = g.lines()
    override fun status(): String = when {
        g.over -> "No moves left · score ${g.score}" + (if (record) " · new best!" else "") + " · OK: play again"
        g.won -> "2048! Keep going · score ${g.score}"
        else -> "Score ${g.score}" + stats.int("2048").let { if (it > 0) " · best $it" else "" }
    }
    override fun help(phone: Boolean) = if (phone) "Tap a direction: tiles slide, equal ones join." else "Arrows slide the tiles: two the same join up. Reach 2048."
    override val phoneKeys = listOf("up", "down", "left", "right")
}

class PlayMines(stats: GameStats) : GamePlay("Minesweeper", stats) {
    private var g = Games.Mines()
    private var started = 0L
    private var took = 0
    private var record = false
    override val over get() = g.lost || g.won
    override fun restart() { g = Games.Mines(); started = 0L; record = false }
    override fun key(k: String) {
        if (k == "ok" && started == 0L) started = System.currentTimeMillis()
        g.key(k)
    }
    override fun ended() {
        took = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtLeast(1)
        if (g.won) { stats.add("minesWon"); record = stats.best("minesTime", took, lowerIsBetter = true) } else stats.add("minesLost")
    }
    override fun lines() = g.lines()
    override fun status(): String = when {
        g.lost -> "Boom! · OK: play again"
        g.won -> "Cleared in ${took}s" + (if (record) " · new best!" else "") + " · OK: play again"
        else -> "Mines ${g.count - g.flags}" + stats.int("minesTime").let { if (it > 0) " · best ${it}s" else "" } +
            stats.int("minesWon").let { if (it > 0) " · won $it" else "" }
    }
    override fun help(phone: Boolean) =
        if (phone) "Move, then OK digs and Flag marks a mine. Numbers count mines next door."
        else "Arrows move · OK digs · Play/Pause (p) flags a mine. Numbers count mines next door."
    override val phoneKeys = listOf("up", "down", "left", "right", "ok", "play")
}

class PlayNoughts(stats: GameStats, private val hard: Boolean) : GamePlay("Noughts & crosses", stats) {
    private var g = Games.Noughts(hard)
    override val over get() = g.winner != null || g.draw
    override fun restart() { g = Games.Noughts(hard) }
    override fun key(k: String) = g.key(k)
    private val tag get() = if (hard) "Hard" else "Easy"
    override fun ended() = when (g.winner) { 'X' -> stats.add("ttt${tag}Won"); 'O' -> stats.add("ttt${tag}Lost"); else -> stats.add("ttt${tag}Drawn") }
    override fun lines() = g.lines()
    override fun status(): String = when (g.winner) {
        'X' -> "You win! · OK: play again"
        'O' -> "I win · OK: play again"
        else -> if (g.draw) "A draw · OK: play again"
        else "You're X ($tag) · won ${stats.int("ttt${tag}Won")}, drawn ${stats.int("ttt${tag}Drawn")}, lost ${stats.int("ttt${tag}Lost")}"
    }
    override fun help(phone: Boolean) = if (phone) "Move, then OK puts your X." else "Arrows move · OK puts your X. Three in a row wins."
}
