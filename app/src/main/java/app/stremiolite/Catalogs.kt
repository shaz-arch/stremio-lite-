package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/**
 * A catalogue row opened as a text list. Titles load a page at a time
 * (most add-ons send about 100) and the next page loads as you near the bottom.
 */
object Catalogs {
    private const val CACHE_MS = 60 * 60_000L
    private const val LOAD_AHEAD = 15

    class Meta(
        val id: String,
        val type: String,
        val name: String,
        val year: String,
        val rating: String,
        val runtime: String,
        val genres: List<String>,
        val description: String,
    )

    /** First pages of recently opened rows, kept for an hour so Back-then-reopen is instant. */
    private class Cached(val time: Long, val metas: List<Meta>)
    private val cache = object : LinkedHashMap<String, Cached>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 6
    }

    fun typeLabel(type: String) = when (type) {
        "movie" -> "Movies"
        "series" -> "Shows"
        "all" -> "Movies & shows"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun pageUrl(a: Addon, c: Addon.Catalog, skip: Int) =
        a.base + "/catalog/" + enc(c.type) + "/" + enc(c.id) + (if (skip > 0) "/skip=$skip" else "") + ".json"

    private fun fetch(url: String, useCache: Boolean): List<Meta> {
        if (useCache) synchronized(cache) {
            cache[url]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.metas }
        }
        val metas = parse(Net.get(url))
        if (useCache) synchronized(cache) { cache[url] = Cached(System.currentTimeMillis(), metas) }
        return metas
    }

    fun parse(json: String): List<Meta> {
        val arr = JSONObject(json).optJSONArray("metas") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val genres = m.optJSONArray("genres") ?: m.optJSONArray("genre")
            Meta(
                id = m.optString("id"),
                type = m.optString("type"),
                name = m.optString("name").ifEmpty { m.optString("id") },
                year = m.optString("releaseInfo").ifEmpty { m.optString("year") },
                rating = cleanRating(m.optString("imdbRating")),
                runtime = m.optString("runtime"),
                genres = genres?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList(),
                description = m.optString("description"),
            )
        }
    }

    fun detail(m: Meta): String {
        val kind = when (m.type) { "movie" -> "Movie"; "series" -> "Series"; else -> m.type }
        val rating = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
        return "${m.year.take(9).padEnd(9)} ${rating.padEnd(6)} ${kind.padEnd(6)}"
    }

    fun screen(app: MainActivity, a: Addon, c: Addon.Catalog, title: String = c.name): Screen {
        val items = mutableListOf<Meta>()
        val seen = HashSet<String>()
        var fetched = 0
        var loading = false
        var ended = false
        var error: String? = null
        lateinit var screen: Screen
        lateinit var loadMore: () -> Unit

        fun rebuild() {
            val rows = items.mapTo(ArrayList<Row>(items.size + 2)) { m ->
                Row(m.name, detail(m), opens = { Details.screen(app, a, m) }, play = {
                    // A film's stream request needs nothing from its details: start it right away.
                    if (m.type == "movie") Streams.prefetch(app, m.type, m.id)
                    app.push(Details.screen(app, a, m, autoPlay = true))
                })
            }
            when {
                error != null -> {
                    rows += Row("Couldn't load: $error")
                    rows += Row("Try again", action = { error = null; loadMore() })
                }
                loading -> rows += Row(if (items.isEmpty()) "Loading…" else "Loading more…")
                ended -> rows += Row(if (items.isEmpty()) "This row is empty." else "──── end of list: ${items.size} titles ────")
            }
            screen.rows = rows
            app.refresh(screen)
        }

        loadMore = {
            if (!loading && !ended) {
                loading = true
                rebuild()
                val first = fetched == 0
                val url = pageUrl(a, c, fetched)
                Net.async({ fetch(url, useCache = first) }) { r ->
                    loading = false
                    r.onSuccess { page ->
                        fetched += page.size
                        var added = 0
                        for (m in page) if (seen.add(m.type + ":" + m.id)) { items += m; added++ }
                        // Stop when a page is empty or only repeats what we have (some add-ons ignore skip).
                        if (page.isEmpty() || added == 0) ended = true
                    }.onFailure { e -> error = Net.describe(e) }
                    rebuild()
                }
            }
        }

        screen = Screen(title, listOf(Row("Loading…")), tag = typeLabel(c.type))
        screen.onShow = { if (items.isEmpty() && !loading && error == null && !ended) loadMore() }
        screen.onSelect = { pos -> if (pos >= items.size - LOAD_AHEAD && error == null) loadMore() }
        return screen
    }

    /** What the catalogue already tells us about a title. Step 4 adds full details and episodes. */
    fun preview(app: MainActivity, m: Meta): Screen {
        val rows = mutableListOf<Row>()
        rows += Row(info("Type", typeLabel(m.type).removeSuffix("s")))
        if (m.year.isNotBlank()) rows += Row(info("Year", m.year))
        if (m.rating.isNotBlank()) rows += Row(info("IMDb rating", "★ ${m.rating}"))
        if (m.runtime.isNotBlank()) rows += Row(info("Runtime", m.runtime))
        if (m.genres.isNotEmpty()) rows += Row(info("Genres", m.genres.joinToString(", ")))
        if (m.description.isNotBlank()) {
            rows += Row("────────────────────")
            wrap(m.description, app.columns()).forEach { rows += Row(it) }
        }
        rows += Row("────────────────────")
        rows += Row("Full details, seasons and streams arrive in steps 4 and 5.")
        return Screen(m.name, rows)
    }

    /** Blank for missing ratings ("", "null", "N/A"). */
    fun cleanRating(r: String) = r.trim().takeUnless { it.isEmpty() || it == "null" || it.equals("N/A", true) || it == "0" } ?: ""

    fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for (word in text.replace('\n', ' ').split(' ').filter { it.isNotEmpty() }) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                lines += line.toString()
                line.setLength(0)
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }
}
