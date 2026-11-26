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


    /** First pages of recently opened rows, kept for an hour so Back-then-reopen is instant. */
    private class Cached(val time: Long, val metas: List<Meta>)
    private val cache = object : LinkedHashMap<String, Cached>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 6
    }

    fun typeLabel(type: String) = MetaParse.typeLabel(type)

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

    fun parse(json: String): List<Meta> = MetaParse.parse(json)

    fun detail(m: Meta): String {
        val kind = when (m.type) { "movie" -> "Movie"; "series" -> "Series"; else -> m.type }
        val rating = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
        return "${m.year.take(9).padEnd(9)} ${rating.padEnd(6)} ${AgeRatings.label(m.id).padEnd(if (narrow) 5 else 8)} ${kind.padEnd(6)}"
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

        val placeKey = "cat:${a.transportUrl}|${c.type}|${c.id}"
        // Return to the same line as last time (loading further pages if needed, up to a limit).
        var pending: Int? = app.savedPlace(placeKey)?.takeIf { it in 2..400 }
        // Row 0 is "About this row"; titles start at row 1.
        val about = Row("About this row", "►", opens = { aboutRow(app, a, c) })
        var shown = 0

        fun rebuild() {
            val marks = History.marks(app)
            val hide = Settings.hideWatched(app)
            val aged = KidsGuard.ageLimited(app)
            val visible = (if (hide) items.filter { marks[it.id] != Marks.WATCHED } else items)
                .let { l -> if (aged) l.filter { KidsGuard.allows(app, it.id) } else l }
            shown = visible.size
            val rows = visible.mapTo(ArrayList<Row>(visible.size + 3).apply { add(about) }) { m ->
                val mark = marks[m.id]
                // Play/Pause on a title while browsing puts it in (or out of) the basket.
                Row(Marks.title(mark, m.name), detail(m), opens = { Details.screen(app, a, m) }, play = {
                    BasketList.toggle(app, m.id, m.type, m.name, m.year); rebuild(); app.refresh(screen)
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
            pending?.let { want ->
                when {
                    want <= shown -> { screen.moveTo = want; pending = null }
                    ended || error != null -> { screen.moveTo = shown.coerceAtLeast(1); pending = null }
                }
            }
            app.refresh(screen)
            if (pending != null && !loading && !ended && error == null) Net.later(0) { loadMore() }
        }

        loadMore = {
            if (!loading && !ended) {
                loading = true
                rebuild()
                // Personal lists (MDBList, Trakt, Letterboxd…) change as you add to them: always fetch fresh.
                val personal = listOf("mdblist.", "trakt.", "letterboxd.", "simkl.").any { c.id.startsWith(it) }
                val first = fetched == 0 && !personal
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
                    RatingFill.fill(app, screen, items) { if (app.inStack(screen)) rebuild() }
                }
            }
        }

        screen = Screen(title, listOf(about, Row("Loading…")), tag = typeLabel(c.type))
        screen.key = placeKey
        screen.position = 1
        screen.onShow = {
            if (items.isEmpty() && !loading && error == null && !ended) loadMore()
            else if (items.isNotEmpty()) rebuild() // refresh ✓ marks after watching something
        }
        screen.onSelect = { pos -> if (pos >= shown + 1 - LOAD_AHEAD && error == null) loadMore() }
        return screen
    }

    /** Which add-on a row comes from and a guess at the list behind it. */
    private fun aboutRow(app: MainActivity, a: Addon, c: Addon.Catalog): Screen {
        val r = RowSource.about(a.name, c)
        return Screen(
            "About: ${c.name}",
            listOf(
                Row(info("From", r.addon)),
                Row(info("Row ID", r.id)),
                Row(info("Source", r.source)),
                Row(info("Type", r.type)),
            ),
        )
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
    fun cleanRating(r: String) = MetaParse.cleanRating(r)

    fun wrap(text: String, width: Int): List<String> = MetaParse.wrap(text, width)
}
