package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Age ratings for lists (U, PG, 12A, 15, 18 in the UK), from TMDB with your key: catalogue rows
 * don't carry them. Your country's rating first, the US one if it has none. Kept on the device,
 * so a list you've seen before shows them straight away.
 */
object AgeRatings {
    private const val KEEP_MS = 60L * 24 * 3600_000      // ratings rarely change
    private const val RETRY_MS = 7L * 24 * 3600_000      // none yet: new titles get one later
    private val UNRATED = setOf("NR", "UR", "UNRATED", "NOT RATED", "N/A", "NONE", "-")

    @Volatile var dir: java.io.File? = null
    private val known = ConcurrentHashMap<String, Pair<String, Long>>()   // id → rating ("" = none), when
    @Volatile private var read = false
    @Volatile private var dirty = false

    private fun file() = dir?.let { java.io.File(it, "age-ratings.json") }

    private fun load() {
        if (read) return
        read = true
        runCatching {
            val o = JSONObject(file()?.takeIf { it.exists() }?.readText() ?: return)
            o.keys().forEach { k -> o.optJSONArray(k)?.let { known[k] = it.optString(0) to it.optLong(1) } }
        }
    }

    /** Writes what's new to disk (call after a batch of look-ups). */
    fun save() {
        if (!dirty) return
        val f = file() ?: return
        dirty = false
        runCatching {
            val o = JSONObject()
            known.forEach { (k, v) -> o.put(k, JSONArray().put(v.first).put(v.second)) }
            f.parentFile?.mkdirs()
            val tmp = java.io.File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
        }
    }

    /** The rating if known ("" when TMDB has none), or null when it hasn't been looked up lately. */
    fun of(id: String, now: Long = System.currentTimeMillis()): String? {
        load()
        val (r, at) = known[id] ?: return null
        return r.takeIf { now - at < (if (r.isEmpty()) RETRY_MS else KEEP_MS) }
    }

    /** Titles in [metas] still to look up. */
    fun missing(metas: List<Meta>): List<Meta> = metas.filter { (it.type == "movie" || it.type == "series") && of(it.id) == null }.distinctBy { it.id }

    /** Looks one title up (blocking). Failures aren't remembered, so they're tried again next time. */
    fun fetch(key: String, region: String, type: String, id: String) {
        if (of(id) != null) return
        val tv = type == "series"
        val tmdb = WhereToWatch.tmdbId(key, tv, id, "")
        val r = if (tmdb == null) "" else pick(WhereToWatch.get(key, if (tv) "/tv/$tmdb/content_ratings" else "/movie/$tmdb/release_dates"), tv, region)
        known[id] = r to System.currentTimeMillis()
        dirty = true
    }

    /** Your country's rating from a TMDB reply, or else the US one. */
    fun pick(o: JSONObject, tv: Boolean, region: String): String {
        val res = o.optJSONArray("results") ?: return ""
        val byCountry = (0 until res.length()).mapNotNull { res.optJSONObject(it) }.associateBy { it.optString("iso_3166_1") }
        fun cert(c: JSONObject?): String? {
            c ?: return null
            if (tv) return c.optString("rating").trim().takeIf { it.isNotEmpty() && it.uppercase() !in UNRATED }
            val ds = c.optJSONArray("release_dates") ?: return null
            val list = (0 until ds.length()).mapNotNull { ds.optJSONObject(it) }
                .filter { it.optString("certification").trim().let { s -> s.isNotEmpty() && s.uppercase() !in UNRATED } }
            // The cinema rating first, then any other release's.
            return (list.firstOrNull { it.optInt("type") == 3 } ?: list.firstOrNull())?.optString("certification")?.trim()
        }
        return cert(byCountry[region]) ?: cert(byCountry["US"]) ?: ""
    }

    /** For the list column: "15", "PG-13", or blank. */
    fun label(id: String): String = of(id).orEmpty().take(5)
}
