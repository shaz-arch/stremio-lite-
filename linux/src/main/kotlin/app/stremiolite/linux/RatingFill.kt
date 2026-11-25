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

    fun fill(metas: List<Meta>, onChange: () -> Unit) {
        val key = Prefs.tmdbKey
        if (key.isBlank()) return
        val todo = AgeRatings.missing(metas)
        if (todo.isEmpty()) return
        queue.clear(); queue.addAll(todo)
        changed = onChange
        while (workers < 3) { workers++; next(key, Prefs.watchRegion) }
    }

    private fun next(key: String, region: String) {
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
