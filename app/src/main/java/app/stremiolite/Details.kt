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

    class Video(val id: String, val title: String, val season: Int, val episode: Int, val released: String, val overview: String)

    class Full(
        val id: String,
        val type: String,
        val name: String,
        val year: String,
        val rating: String,
        val runtime: String,
        val genres: List<String>,
        val director: List<String>,
        val cast: List<String>,
        val description: String,
        val videos: List<Video>,
        val source: String,
    )

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
                val full = parse(m, a.name)
                synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), full) }
                return full
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no details found")
    }

    private fun strings(o: JSONObject, vararg keys: String): List<String> {
        for (k in keys) {
            val arr = o.optJSONArray(k) ?: continue
            return (0 until arr.length()).mapNotNull { i ->
                // Some add-ons send ["Name"], others [{"name":"Name"}]
                arr.optJSONObject(i)?.optString("name") ?: arr.optString(i).takeIf { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    private fun parse(m: JSONObject, source: String): Full {
        val vids = m.optJSONArray("videos")
        val videos = if (vids == null) emptyList() else (0 until vids.length()).mapNotNull { i ->
            val v = vids.optJSONObject(i) ?: return@mapNotNull null
            Video(
                id = v.optString("id"),
                title = v.optString("title").ifEmpty { v.optString("name") },
                season = v.optInt("season", 0),
                episode = v.optInt("episode", v.optInt("number", 0)),
                released = v.optString("released").take(10),
                overview = v.optString("overview").ifEmpty { v.optString("description") },
            )
        }
        return Full(
            id = m.optString("id"),
            type = m.optString("type"),
            name = m.optString("name"),
            year = m.optString("releaseInfo").ifEmpty { m.optString("year") },
            rating = Catalogs.cleanRating(m.optString("imdbRating")),
            runtime = m.optString("runtime"),
            genres = strings(m, "genres", "genre"),
            director = strings(m, "director", "directors"),
            cast = strings(m, "cast"),
            description = m.optString("description"),
            videos = videos,
            source = source,
        )
    }

    fun screen(app: MainActivity, from: Addon, item: Catalogs.Meta): Screen {
        // Start with what the catalogue already knows, so there's something to read at once.
        val quick = Catalogs.preview(app, item)
        val loadingRow = Row("Loading full details…")
        quick.rows = quick.rows.dropLast(1) + loadingRow
        var started = false
        var full: Full? = null

        quick.onShow = {
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
    fun screenFor(app: MainActivity, id: String, type: String, name: String, year: String): Screen? {
        val from = Home.allAddons().firstOrNull { it.supports("meta", type, id) } ?: Home.allAddons().firstOrNull() ?: return null
        return screen(app, from, Catalogs.Meta(id, type, name, year, "", "", emptyList(), ""))
    }

    private fun epLabel(v: Video) = "S${v.season.toString().padStart(2, '0')}E${v.episode.toString().padStart(2, '0')}"

    private fun ordered(f: Full) = f.videos.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))

    /** Every video in Stremio's order (season, episode, release date), as its watched marks expect. */
    private fun stremioOrder(f: Full) = f.videos.sortedWith(compareBy({ it.season }, { it.episode }, { it.released })).map { it.id }

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
            // e.g. a film collection: each part is its own title
            rows += Row("Parts in this collection: ${f.videos.size}")
            f.videos.forEach { v ->
                rows += Row(v.title.ifEmpty { v.id }, v.released.take(4), opens = { Streams.screen(app, "movie", v.id, v.title.ifEmpty { f.name }) })
            }
        } else {
            rows += playRows(app, f, null, f.name)
        }
        rows += Row("────────────────────")
        rows += libraryRows(app, f)
        rows += Row("────────────────────")

        rows += Row(info("Type", if (isShow) "Series" else Catalogs.typeLabel(f.type).removeSuffix("s")))
        if (f.year.isNotBlank()) rows += Row(info("Year", f.year))
        if (f.rating.isNotBlank()) rows += Row(info("IMDb rating", "★ ${f.rating}"))
        if (f.runtime.isNotBlank()) rows += Row(info("Runtime", f.runtime))
        if (f.genres.isNotEmpty()) rows += Row(info("Genres", f.genres.joinToString(", ")))
        if (f.director.isNotEmpty()) rows += Row(info("Director", f.director.take(3).joinToString(", ")))
        if (f.cast.isNotEmpty()) rows += Row(info("Cast", f.cast.take(4).joinToString(", ")))
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
            Row("$num  $title", listOf(mark(app, v), date).filter { it.isNotEmpty() }.joinToString("   "), opens = { episode(app, f, seasonName, v) })
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
        }
        return screen
    }

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun upcoming(date: String): Boolean =
        date.length == 10 && date > day.format(Date())
}
