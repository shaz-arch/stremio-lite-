package app.stremiolite.linux

import app.stremiolite.AgeRatings
import app.stremiolite.Meta
import app.stremiolite.WhereToWatch

/** Fills in [AgeRatings] for the list on screen, three at a time. A newer list takes over. Needs your TMDB key. */
object RatingFill {
    private val queue = ArrayDeque<Meta>()
    private var workers = 0
    private var changed: (() -> Unit)? = null
    private var since = 0
    private var owner: Screen? = null

    fun fill(screen: Screen, metas: List<Meta>, onChange: () -> Unit) {
        val key = Prefs.tmdbKey
        if (key.isBlank()) return
        val todo = AgeRatings.missing(metas)
        if (todo.isEmpty()) return
        queue.clear(); queue.addAll(todo)
        changed = onChange
        owner = screen
        // Up to three at once. A fixed count, not "while fewer than three": a worker that finds the
        // queue empty stops at once, so that loop never ended when fewer than three were missing.
        repeat((minOf(3, queue.size) - workers).coerceAtLeast(0)) { workers++; next(key, Prefs.watchRegion) }
    }

    private fun next(key: String, region: String) {
        // The list was closed: stop. Something's playing: wait and try again.
        if (owner?.let { App.inStack(it) } != true) queue.clear()
        else if (Player.playing) { App.later(10_000) { next(key, region) }; return }
        val m = queue.removeFirstOrNull()
        if (m == null) {
            if (--workers == 0) { changed?.invoke(); App.async({ AgeRatings.save() }) {} }
            return
        }
        App.async({ AgeRatings.fetch(key, region, m.type, m.id) }) { r ->
            if (r.exceptionOrNull() is WhereToWatch.KeyRefused) { queue.clear() }
            if (++since % 6 == 0) changed?.invoke()
            next(key, region)
        }
    }
}
