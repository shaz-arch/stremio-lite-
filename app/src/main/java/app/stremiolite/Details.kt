package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full details for a title from the add-ons' /meta endpoint, then seasons and episodes for shows.
 * The catalogue's own info shows straight away while the full details load.
 */
object Details {
    private const val CACHE_MS = 24 * 60 * 60_000L

    private class Cached(val time: Long, val full: Full)
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 12
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Ask the add-on the title came from first (if it can describe it), then any other that can. */
    private fun fetch(from: Addon, type: String, id: String): Full {
        val key = "$type/$id"
        synchronized(cache) {
            cache[key]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.full }
        }
        val candidates = (listOf(from) + Home.allAddons()).distinct().filter { it.supports("meta", type, id) }
        if (candidates.isEmpty()) throw IllegalStateException("none of your add-ons can describe this title")
        var last: Throwable? = null
        for (a in candidates) {
            try {
                val json = JSONObject(Net.get(a.base + "/meta/" + enc(type) + "/" + enc(id) + ".json"))
                val m = json.optJSONObject("meta") ?: continue
                val full = MetaParse.full(m, a.name)
                synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), full) }
                return full
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no details found")
    }

    /** [autoPlay]: go straight on to playing (Play/Pause on a list), via the same resume/next logic. */
    fun screen(app: MainActivity, from: Addon, item: Meta, autoPlay: Boolean = false): Screen {
        // Start with what the catalogue already knows, so there's something to read at once.
        val quick = Catalogs.preview(app, item)
        val loadingRow = Row("Loading full details…")
        quick.rows = quick.rows.dropLast(1) + loadingRow
        var started = false
        var full: Full? = null

        quick.onShow = {
            // Picks up where you got to on another device (resume point, watched marks).
            History.pullFresh(app) { full?.let { f -> if (app.isTop(quick)) { quick.rows = rows(app, f); app.refresh(quick) } } }
            val f = full
            if (f != null) {
                // Back from the player or an episode: redraw so resume points and marks are current.
                quick.rows = rows(app, f)
                app.refresh(quick)
            } else if (!started) {
                started = true
                Net.async({ fetch(from, item.type, item.id) }) { r ->
                    r.onSuccess { loaded ->
                        full = loaded
                        if (loaded.videos.isNotEmpty()) History.applyAccountWatched(app, loaded.id, stremioOrder(loaded))
                        quick.rows = rows(app, loaded)
                        val target = mainVideo(app, loaded)
                        if (autoPlay && target != null && app.isTop(quick)) {
                            app.refresh(quick)
                            app.push(target.second)
                            return@async
                        }
                        // Fetch the streams you'd most likely play next, so Play best is quick,
                        // but only once you've stayed on this title (not while browsing past it).
                        target?.first?.let { vid ->
                            Net.later(1_500, Runnable { if (app.isTop(quick)) Streams.prefetch(app, loaded.type, vid) })
                        }
                    }.onFailure { e ->
                        loadingRow.label = "Couldn't load full details: ${Net.describe(e).let { d -> if (d == e.javaClass.simpleName) e.message ?: d else d }}"
                    }
                    app.refresh(quick)
                }
            }
        }
        return quick
    }

    /** Opens a title from Continue watching or the Library, where only the basics were saved. */
    fun screenFor(app: MainActivity, id: String, type: String, name: String, year: String, autoPlay: Boolean = false): Screen? {
        val from = Home.allAddons().firstOrNull { it.supports("meta", type, id) } ?: Home.allAddons().firstOrNull() ?: return null
        return screen(app, from, Meta(id, type, name, year, "", "", emptyList(), ""), autoPlay)
    }

    /**
     * What "play this title" means: a film (resuming if started); for a show, the episode in progress,
     * the next one, or the first one not yet watched. Returns its video id and the Play best screen.
     */
    private fun mainVideo(app: MainActivity, f: Full): Pair<String, Screen>? {
        val isShow = f.videos.any { it.season > 0 || it.episode > 0 } && f.type != "movie"
        if (!isShow) {
            if (f.videos.isNotEmpty()) return null   // a collection: pick a part yourself
            val w = watchFor(f, null)
            val at = History.resumeAt(app, f.id)
            return f.id to Streams.playBest(app, f.type, f.id, f.name, f.runtime, w, at)
        }
        val list = ordered(f).filter { !upcoming(it.released) }
        val v = History.item(app, f.id)?.let { item -> list.firstOrNull { it.id == item.videoId } }
            ?: list.firstOrNull { History.progress(app, it.id)?.done != true }
            ?: list.firstOrNull()
            ?: return null
        val at = History.resumeAt(app, v.id)
        return v.id to Streams.playBest(app, f.type, v.id, "${f.name} ${epLabel(v)}", f.runtime, watchFor(f, v), at)
    }

    /** Full details for a title from whichever add-on can describe it (background threads only). */
    fun loadFull(type: String, id: String): Full {
        val from = Home.allAddons().firstOrNull { it.supports("meta", type, id) }
            ?: throw IllegalStateException("none of your add-ons can describe this title")
        return fetch(from, type, id)
    }

    /** One episode's screen, and playing it, for lists outside the show's own screens (New episodes). */
    fun episodeScreen(app: MainActivity, f: Full, v: Video): Screen =
        episode(app, f, if (v.season > 0) "Season ${v.season}" else "Specials", v)

    fun playEpisode(app: MainActivity, f: Full, v: Video): Screen =
        Streams.playBest(app, f.type, v.id, "${f.name} ${epLabel(v)}", f.runtime, watchFor(f, v), History.resumeAt(app, v.id))

    fun isUpcoming(date: String) = upcoming(date)

    fun epLabel(v: Video) = MetaParse.epLabel(v)

    private fun ordered(f: Full) = MetaParse.ordered(f)

    /** Every video in Stremio's order (season, episode, release date), as its watched marks expect. */
    private fun stremioOrder(f: Full) = MetaParse.stremioOrder(f)

    /** What to file the playback under; for an episode, also which episode comes next. */
    private fun watchFor(f: Full, v: Video?): History.Watch {
        if (v == null) return History.Watch(f.id, f.type, f.name, f.year, f.id, "", f.runtime, null, null, emptyList())
        val list = ordered(f)
        val next = list.getOrNull(list.indexOfFirst { it.id == v.id }.let { if (it < 0) -1 else it + 1 })
            ?.takeIf { !upcoming(it.released) }
        return History.Watch(
            f.id, f.type, f.name, f.year, v.id, (epLabel(v) + " " + v.title).trim(), f.runtime,
            next?.id, next?.let { (epLabel(it) + " " + it.title).trim() },
            stremioOrder(f),
        )
    }

    /** "Resume from …" (when there's a saved position), "Play best", "Choose a stream". */
    private fun playRows(app: MainActivity, f: Full, v: Video?, title: String): List<Row> {
        val w = watchFor(f, v)
        val at = History.resumeAt(app, w.videoId)
        val rows = mutableListOf<Row>()
        if (at > 0) {
            rows += Row("Resume from ${History.clock(at)}", "►", opens = { Streams.playBest(app, f.type, w.videoId, title, f.runtime, w, at) })
            rows += Row("Play best from the start", opens = { Streams.playBest(app, f.type, w.videoId, title, f.runtime, w, 0) })
        } else {
            rows += Row("Play best", "►", opens = { Streams.playBest(app, f.type, w.videoId, title, f.runtime, w, 0) })
        }
        rows += Row("Choose a stream", opens = { Streams.screen(app, f.type, w.videoId, title, w, at) })
        return rows
    }

    private fun libraryRows(app: MainActivity, f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        lateinit var lib: Row
        lib = Row(if (History.inLibrary(app, f.id)) "Remove from library" else "Add to library", action = {
            val added = History.toggleLibrary(app, History.Saved(f.id, f.type, f.name, f.year))
            lib.label = if (added) "Remove from library" else "Add to library"
            app.toast(if (added) "Added to library" else "Removed from library")
            app.refreshTop()
        })
        rows += lib
        if (f.type == "series") {
            // Following is separate from the Library: shows you've started are followed automatically.
            lateinit var fol: Row
            fun folLabel() = if (Follow.isFollowed(app, f.id)) "Following new episodes and seasons ✓ (stop)" else "Tell me about new episodes and seasons"
            fol = Row(folLabel(), action = {
                if (Follow.isFollowed(app, f.id)) {
                    Follow.unfollow(app, f.id)
                    app.toast("Stopped following ${f.name}")
                } else {
                    Follow.follow(app, f.id, f.name)
                    Waiting.askToNotify(app)
                    app.toast("Following ${f.name}: you'll be told when new episodes play")
                }
                fol.label = folLabel()
                app.refreshTop()
            })
            rows += fol
        }
        if (f.type == "movie" && History.marks(app)[f.id] == "✓") rows += Row("Remove from watched", action = {
            History.unwatch(app, f.id, f.type, f.id, "", emptyList())
            app.toast("${f.name} is no longer marked as watched")
            app.refreshTop()
        })
        if (f.type == "movie") {
            lateinit var wait: Row
            fun waitLabel() = if (Waiting.has(app, f.id)) "Stop waiting for a stream" else "Tell me when a stream is available"
            wait = Row(waitLabel(), action = {
                if (Waiting.has(app, f.id)) { Waiting.remove(app, f.id); app.toast("Removed from Waiting for streams") }
                else { Waiting.add(app, Waiting.Entry(f.id, f.type, f.name, f.year, 0)); app.toast("Added to Waiting for streams") }
                wait.label = waitLabel()
                app.refreshTop()
            })
            rows += wait
            // Played it but the picture wasn't great? Keep an eye out for a better release.
            lateinit var better: Row
            fun betterLabel() = if (Waiting.watchingForBetter(app, f.id)) "Watching for a better version ✓ (stop)" else "Tell me when a better version appears"
            better = Row(betterLabel(), action = {
                if (Waiting.watchingForBetter(app, f.id)) {
                    Waiting.remove(app, f.id); app.toast("Stopped watching for a better version")
                    better.label = betterLabel(); app.refreshTop()
                } else {
                    app.toast("Checking what's there now…")
                    Waiting.addBetter(app, f.id, f.type, f.name, f.year) { have ->
                        app.toast("I'll tell you when something better than $have appears")
                        better.label = betterLabel(); wait.label = waitLabel(); app.refreshTop()
                    }
                }
            })
            rows += better
        }
        if (History.item(app, f.id) != null) {
            lateinit var cw: Row
            cw = Row("Remove from Continue watching", action = {
                History.removeItem(app, f.id)
                cw.label = "Removed from Continue watching"
                app.refreshTop()
            })
            rows += cw
        }
        return rows
    }

    private fun rows(app: MainActivity, f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        val isShow = f.videos.any { it.season > 0 || it.episode > 0 } && f.type != "movie"

        // What you'll want first goes at the top: the way in to watch.
        if (isShow) {
            History.item(app, f.id)?.let { item ->
                val v = f.videos.firstOrNull { it.id == item.videoId }
                if (v != null && !upcoming(v.released)) {
                    val title = "${f.name} ${epLabel(v)}"
                    val at = History.resumeAt(app, v.id)
                    val label = when {
                        item.upNext -> "Next: ${epLabel(v)}  ${v.title}"
                        at > 0 -> "Resume ${epLabel(v)} from ${History.clock(at)}"
                        else -> "Continue: ${epLabel(v)}  ${v.title}"
                    }
                    rows += Row(label, "►", opens = { Streams.playBest(app, f.type, v.id, title, f.runtime, watchFor(f, v), at) })
                    rows += Row("────────────────────")
                }
            }
            val bySeason = f.videos.groupBy { it.season }
            val seasons = bySeason.keys.filter { it > 0 }.sorted() + bySeason.keys.filter { it <= 0 }
            for (s in seasons) {
                val eps = bySeason.getValue(s).sortedBy { it.episode }
                val name = if (s > 0) "Season $s" else "Specials"
                val aired = eps.count { !upcoming(it.released) }
                val watched = eps.count { History.progress(app, it.id)?.done == true }
                val count = when {
                    watched == eps.size -> "all ${eps.size} watched ✓"
                    watched > 0 -> "$watched of ${eps.size} watched"
                    aired == eps.size -> "${eps.size} episodes"
                    else -> "$aired of ${eps.size} aired"
                }
                rows += Row(name, "$count  ►", opens = { episodes(app, f, name, eps) })
            }
        } else if (f.videos.isNotEmpty()) {
            // A film collection: each part is a film of its own, in release order, with ✓ on the ones watched.
            val marks = History.marks(app)
            rows += Row("Films in this collection: ${f.videos.size}")
            SearchRank.parts(f.videos).forEach { v ->
                val name = v.title.ifEmpty { v.id }
                val own = v.id.startsWith("tt") || v.id.startsWith("tmdb:")
                rows += Row((if (marks[v.id] == "✓") "✓ " else "") + name, v.released.take(4), opens = {
                    if (own) screenFor(app, v.id, "movie", name, v.released.take(4)) ?: Streams.screen(app, "movie", v.id, name)
                    else Streams.screen(app, "movie", v.id, name)
                }, play = {
                    if (own) screenFor(app, v.id, "movie", name, v.released.take(4), autoPlay = true)?.let { app.push(it) }
                    else app.push(Streams.screen(app, "movie", v.id, name))
                })
            }
        } else {
            rows += playRows(app, f, null, f.name)
        }
        val collection = !isShow && f.videos.isNotEmpty()
        rows += Row("────────────────────")
        if (!collection) {
            rows += libraryRows(app, f)
            rows += Row("────────────────────")
        }

        rows += Row(info("Type", if (isShow) "Series" else if (collection) "Collection" else Catalogs.typeLabel(f.type).removeSuffix("s")))
        if (f.year.isNotBlank()) rows += Row(info("Year", f.year))
        if (f.rating.isNotBlank()) rows += Row(info("IMDb rating", "★ ${f.rating}"))
        if (f.runtime.isNotBlank()) rows += Row(info("Runtime", f.runtime))
        if (f.genres.isNotEmpty()) rows += Row(info("Genres", f.genres.joinToString(", ")))
        if (f.director.isNotEmpty()) rows += Row(info("Director", f.director.take(3).joinToString(", ")))
        if (f.cast.isNotEmpty()) rows += Row(info("Cast", f.cast.take(4).joinToString(", ")))
        DetailExtras.facts(f, DetailExtras.today()).forEach { (k, v) -> rows += Row(info(k, v)) }
        DetailExtras.history(History.infos(app).firstOrNull { it.id == f.id }).forEach { (k, v) -> rows += Row(info(k, v)) }
        if (f.imdbUrl.isNotBlank()) rows += LinkOut.row(app, "IMDb page", "IMDb: ${f.name}", f.imdbUrl)
        if (f.trailerUrl.isNotBlank()) rows += LinkOut.row(app, "Trailer", "Trailer: ${f.name}", f.trailerUrl)
        if (f.description.isNotBlank()) {
            rows += Row("────────────────────")
            Catalogs.wrap(f.description, app.columns()).forEach { rows += Row(it) }
        }
        rows += Row("────────────────────")
        rows += Row(info("Details from", f.source))
        return rows
    }

    /** ✓ for watched, time left for started. */
    private fun mark(app: MainActivity, v: Video): String {
        val p = History.progress(app, v.id) ?: return ""
        return when {
            p.done -> "✓"
            p.position > 60_000 && p.duration > 0 -> "${History.clock(p.duration - p.position)} left"
            p.position > 60_000 -> "started"
            else -> ""
        }
    }

    private fun episodes(app: MainActivity, f: Full, seasonName: String, eps: List<Video>): Screen {
        fun build() = eps.map { v ->
            val num = "E" + v.episode.toString().padStart(2, '0')
            val title = v.title.ifEmpty { "Episode ${v.episode}" }
            val date = when {
                v.released.isBlank() -> ""
                upcoming(v.released) -> "${v.released}  upcoming"
                else -> v.released
            }
            Row(
                "$num  $title", listOf(mark(app, v), date).filter { it.isNotEmpty() }.joinToString("   "),
                opens = { episode(app, f, seasonName, v) },
                play = {
                    app.push(Streams.playBest(app, f.type, v.id, "${f.name} ${epLabel(v)}", f.runtime, watchFor(f, v), History.resumeAt(app, v.id)))
                },
            )
        }
        val screen = Screen("${f.name} › $seasonName", build())
        // Start on the first episode not yet watched.
        screen.position = eps.indexOfFirst { History.progress(app, it.id)?.done != true }.coerceAtLeast(0)
        screen.onShow = {
            screen.rows = build()
            app.refresh(screen)
        }
        return screen
    }

    private fun episode(app: MainActivity, f: Full, seasonName: String, v: Video): Screen {
        val label = epLabel(v)
        fun build(): List<Row> {
            val rows = playRows(app, f, v, "${f.name} $label").toMutableList()
            rows += Row("────────────────────")
            rows += Row(info("Episode", "$seasonName, episode ${v.episode}"))
            if (v.released.isNotBlank()) rows += Row(info("Released", v.released + if (upcoming(v.released)) " (upcoming)" else ""))
            mark(app, v).takeIf { it.isNotEmpty() }?.let { rows += Row(info("Watched", if (it == "✓") "yes ✓" else it)) }
            if (mark(app, v) == "✓") rows += Row("Remove from watched", action = {
                History.unwatch(app, f.id, f.type, v.id, label, stremioOrder(f))
                app.toast("$label is no longer marked as watched")
                app.refreshTop()
            })
            if (v.overview.isNotBlank()) {
                rows += Row("────────────────────")
                Catalogs.wrap(v.overview, app.columns()).forEach { rows += Row(it) }
            }
            return rows
        }
        val screen = Screen("${f.name} › $label  ${v.title}", build())
        screen.onShow = {
            screen.rows = build()
            app.refresh(screen)
            Net.later(1_500, Runnable { if (app.isTop(screen)) Streams.prefetch(app, f.type, v.id) })
        }
        return screen
    }

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun upcoming(date: String): Boolean =
        date.length == 10 && date > day.format(Date())
}
