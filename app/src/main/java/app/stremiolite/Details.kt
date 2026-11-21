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

        quick.onShow = {
            if (!started) {
                started = true
                Net.async({ fetch(from, item.type, item.id) }) { r ->
                    r.onSuccess { full ->
                        quick.rows = rows(app, full)
                    }.onFailure { e ->
                        loadingRow.label = "Couldn't load full details: ${Net.describe(e).let { d -> if (d == e.javaClass.simpleName) e.message ?: d else d }}"
                    }
                    app.refresh(quick)
                }
            }
        }
        return quick
    }

    private fun rows(app: MainActivity, f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        val isShow = f.videos.any { it.season > 0 || it.episode > 0 } && f.type != "movie"

        // What you'll want first goes at the top: the way in to watch.
        if (isShow) {
            val bySeason = f.videos.groupBy { it.season }
            val seasons = bySeason.keys.filter { it > 0 }.sorted() + bySeason.keys.filter { it <= 0 }
            for (s in seasons) {
                val eps = bySeason.getValue(s).sortedBy { it.episode }
                val name = if (s > 0) "Season $s" else "Specials"
                val aired = eps.count { !upcoming(it.released) }
                val count = if (aired == eps.size) "${eps.size} episodes" else "$aired of ${eps.size} aired"
                rows += Row(name, "$count  ►", opens = { episodes(app, f, name, eps) })
            }
        } else if (f.videos.isNotEmpty()) {
            // e.g. a film collection: each part is its own title
            rows += Row("Parts in this collection: ${f.videos.size}")
            f.videos.forEach { v ->
                rows += Row(v.title.ifEmpty { v.id }, v.released.take(4), opens = { Streams.screen(app, "movie", v.id, v.title.ifEmpty { f.name }) })
            }
        } else {
            rows += Row("Streams", opens = { Streams.screen(app, f.type, f.id, f.name) })
        }
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

    private fun episodes(app: MainActivity, f: Full, seasonName: String, eps: List<Video>): Screen {
        val rows = eps.map { v ->
            val num = "E" + v.episode.toString().padStart(2, '0')
            val title = v.title.ifEmpty { "Episode ${v.episode}" }
            val date = when {
                v.released.isBlank() -> ""
                upcoming(v.released) -> "${v.released}  upcoming"
                else -> v.released
            }
            Row("$num  $title", date, opens = { episode(app, f, seasonName, v) })
        }
        return Screen("${f.name} › $seasonName", rows)
    }

    private fun episode(app: MainActivity, f: Full, seasonName: String, v: Video): Screen {
        val label = "S${v.season.toString().padStart(2, '0')}E${v.episode.toString().padStart(2, '0')}"
        val rows = mutableListOf(
            Row("Streams", opens = { Streams.screen(app, f.type, v.id, "${f.name} $label") }),
            Row("────────────────────"),
        )
        rows += Row(info("Episode", "$seasonName, episode ${v.episode}"))
        if (v.released.isNotBlank()) rows += Row(info("Released", v.released + if (upcoming(v.released)) " (upcoming)" else ""))
        if (v.overview.isNotBlank()) {
            rows += Row("────────────────────")
            Catalogs.wrap(v.overview, app.columns()).forEach { rows += Row(it) }
        }
        return Screen("${f.name} › $label  ${v.title}", rows)
    }

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun upcoming(date: String): Boolean =
        date.length == 10 && date > day.format(Date())
}
