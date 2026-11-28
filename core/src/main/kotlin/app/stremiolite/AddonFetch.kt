package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** A small most-recently-used cache, safe to use from background threads. */
private class Recent<V>(private val max: Int, private val keepMs: Long) {
    private class Kept<V>(val time: Long, val value: V)
    // Kept in use order (not a LinkedHashMap subclass: Kotlin would add Java 8 methods the 2016 stick lacks).
    private val map = LinkedHashMap<String, Kept<V>>(max * 2, 0.75f, true)

    fun get(key: String): V? = synchronized(map) {
        map[key]?.takeIf { System.currentTimeMillis() - it.time < keepMs }?.value
    }

    fun put(key: String, value: V) = synchronized(map) {
        map[key] = Kept(System.currentTimeMillis(), value)
        while (map.size > max) map.remove(map.keys.first())
    }
}

/** A title's full details, shared by every front-end. Kept for a day (the last dozen titles). */
object TitleDetails {
    private val cache = Recent<Full>(12, 24 * 60 * 60_000L)

    /** Blocking: asks the add-on the title came from first ([from], if it can describe it), then any other that can. */
    fun load(addons: List<Addon>, from: Addon?, type: String, id: String): Full {
        val key = "$type/$id"
        cache.get(key)?.let { return it }
        val candidates = (listOfNotNull(from) + addons).distinct().filter { it.supports("meta", type, id) }
        if (candidates.isEmpty()) throw IllegalStateException("none of your add-ons can describe this title")
        var last: Throwable? = null
        for (a in candidates) {
            try {
                val m = JSONObject(Http.get(a.base + "/meta/" + enc(type) + "/" + enc(id) + ".json")).optJSONObject("meta") ?: continue
                val full = MetaParse.full(m, a.name)
                cache.put(key, full)
                return full
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no details found")
    }
}

/** Catalogue rows and search, shared by every front-end. */
object CataloguePages {
    /** First pages of recently opened rows, kept for an hour so Back-then-reopen is instant. */
    private val cache = Recent<List<Meta>>(6, 60 * 60_000L)

    fun url(a: Addon, c: Addon.Catalog, skip: Int): String =
        a.base + "/catalog/" + enc(c.type) + "/" + enc(c.id) + (if (skip > 0) "/skip=$skip" else "") + ".json"

    fun searchUrl(a: Addon, c: Addon.Catalog, query: String): String =
        a.base + "/catalog/" + enc(c.type) + "/" + enc(c.id) + "/search=" + enc(query) + ".json"

    /** Personal lists (MDBList, Trakt, Letterboxd…) change as you add to them: always fetched fresh. */
    fun isPersonal(c: Addon.Catalog) = listOf("mdblist.", "trakt.", "letterboxd.", "simkl.").any { c.id.startsWith(it) }

    /** Blocking: one page of a row. A row's first page is kept a while, unless it's a personal list. */
    fun page(a: Addon, c: Addon.Catalog, skip: Int): List<Meta> {
        val url = url(a, c, skip)
        val useCache = skip == 0 && !isPersonal(c)
        if (useCache) cache.get(url)?.let { return it }
        val metas = MetaParse.parse(Http.get(url))
        if (useCache) cache.put(url, metas)
        return metas
    }
}

/**
 * One round of asking a title's subtitle add-ons, shared by every front-end and by everything that
 * needs this title's subtitles (fetching early, Play best, the stream list). [listen]ers run on the
 * screen's thread after each answer.
 */
class SubtitleFetch private constructor(type: String, id: String, available: List<Addon>) {
    val started = System.currentTimeMillis()
    val found = mutableListOf<Sub>()
    var done = false
        private set
    private val listeners = mutableListOf<() -> Unit>()

    fun listen(l: () -> Unit): SubtitleFetch {
        listeners += l
        return this
    }

    private fun tell() = listeners.toList().forEach { it() }

    init {
        val addons = available.filter { it.supports("subtitles", type, id) }
        var answered = 0
        // Reports back after the caller has finished setting up, like the network answers do.
        if (addons.isEmpty()) Platform.async.later(0) { done = true; tell() }
        addons.forEach { a ->
            val url = a.base + "/subtitles/" + enc(type) + "/" + enc(id) + ".json"
            Platform.async.background({ JSONObject(Http.get(url, timeoutMs = TIMEOUT_MS)) }) { r ->
                answered++
                r.onSuccess { json ->
                    val arr = json.optJSONArray("subtitles")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val link = o.optString("url")
                        if (link.startsWith("http")) found += Sub(link, o.optString("lang"), StreamParse.shortName(a.name))
                    }
                }
                done = answered >= addons.size
                tell()
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 8_000
        private const val KEEP_MS = 10 * 60_000L
        private val recent = LinkedHashMap<String, SubtitleFetch>()

        /** A recent fetch for this title (e.g. from fetching early), or a new one. */
        fun get(type: String, id: String, addons: List<Addon>): SubtitleFetch {
            val key = "$type/$id"
            recent[key]?.let { if (System.currentTimeMillis() - it.started < KEEP_MS) return it }
            return SubtitleFetch(type, id, addons).also {
                recent.remove(key)
                recent[key] = it
                while (recent.size > 4) recent.remove(recent.keys.first())
            }
        }
    }
}
