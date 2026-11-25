package app.stremiolite

/**
 * Fills in [AgeRatings] for the list on screen, one title at a time so the stick stays quick.
 * A newer list takes over from the one before. Needs your TMDB key; without it there's no column.
 */
object RatingFill {
    private val queue = ArrayDeque<Meta>()
    private var running = false
    private var changed: (() -> Unit)? = null
    private var since = 0

    fun fill(app: MainActivity, metas: List<Meta>, onChange: () -> Unit) {
        val key = Settings.tmdbKey(app)
        if (key.isBlank()) return
        val todo = AgeRatings.missing(metas)
        if (todo.isEmpty()) return
        queue.clear(); queue.addAll(todo)
        changed = onChange
        if (!running) next(key, Settings.watchRegion(app))
    }

    private fun next(key: String, region: String) {
        val m = queue.removeFirstOrNull()
        if (m == null) {
            running = false
            changed?.invoke()
            Net.async({ AgeRatings.save() }) {}
            return
        }
        running = true
        Net.async({ AgeRatings.fetch(key, region, m.type, m.id) }) { r ->
            // A key TMDB turns down: stop rather than ask about every title.
            if (r.exceptionOrNull() is WhereToWatch.KeyRefused) { queue.clear(); running = false; return@async }
            if (++since % 6 == 0) changed?.invoke()
            next(key, region)
        }
    }
}
