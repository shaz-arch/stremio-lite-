package app.stremiolite

/**
 * Fills in [AgeRatings] for the list on screen, one title at a time so the stick stays quick.
 * A newer list takes over from the one before; leaving the list stops it, and it waits while
 * the player is open so playback has the connection to itself. Needs your TMDB key.
 */
object RatingFill {
    private val queue = ArrayDeque<Meta>()
    private var running = false
    private var changed: (() -> Unit)? = null
    private var since = 0
    private var owner: Screen? = null

    fun fill(app: MainActivity, screen: Screen, metas: List<Meta>, onChange: () -> Unit) {
        val key = Settings.tmdbKey(app)
        if (key.isBlank()) return
        val todo = AgeRatings.missing(metas)
        if (todo.isEmpty()) return
        queue.clear(); queue.addAll(todo)
        changed = onChange
        owner = screen
        if (!running) next(app, key, Settings.watchRegion(app))
    }

    private fun next(app: MainActivity, key: String, region: String) {
        // The list was closed: stop. Something's playing: wait and try again.
        if (owner?.let { app.inStack(it) } != true) queue.clear()
        else if (app.playerOpen) { running = true; Net.later(10_000) { next(app, key, region) }; return }
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
            next(app, key, region)
        }
    }
}
