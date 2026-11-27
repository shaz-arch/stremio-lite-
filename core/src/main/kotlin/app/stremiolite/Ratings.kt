package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Review scores and the age rating for a title's page, from MDBList (your own key): IMDb, Rotten
 * Tomatoes and its audience score, Metacritic, Letterboxd, Trakt, TMDB, Roger Ebert, MyAnimeList.
 */
object Ratings {
    class Result(val line: String, val age: String)

    private const val KEEP_MS = 12 * 3_600_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, Result>>()

    /** The scores in lines of about [width] characters, so they wrap whole rather than being cut off. */
    fun lines(r: Result, width: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (p in r.line.split(" · ")) {
            cur = if (cur.isEmpty()) p else if (cur.length + 3 + p.length <= width) "$cur · $p" else { out += cur; p }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun known(id: String): Result? = cache[id]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second

    /** Blocking; only IMDb ids (tt…). An empty result if MDBList has nothing. */
    fun load(key: String, type: String, id: String): Result? {
        if (key.isBlank() || !id.startsWith("tt")) return null
        known(id)?.let { return it }
        val kind = if (type == "series") "show" else "movie"
        val o = JSONObject(Http.get("https://api.mdblist.com/imdb/$kind/${URLEncoder.encode(id, "UTF-8")}?apikey=${URLEncoder.encode(key, "UTF-8")}", timeoutMs = 12_000))
        return parse(o).also { cache[id] = System.currentTimeMillis() to it }
    }

    private val ORDER = listOf("imdb", "tomatoes", "popcorn", "tomatoesaudience", "metacritic", "letterboxd", "trakt", "tmdb", "rogerebert", "myanimelist")

    fun parse(o: JSONObject): Result {
        val arr = o.optJSONArray("ratings")
        val by = HashMap<String, JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { by[it.optString("source").lowercase()] = it }
        fun v(src: String): Double? = by[src]?.let { r -> if (r.isNull("value")) null else r.optDouble("value").takeIf { !it.isNaN() && it > 0 } }
        fun one(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.UK, "%.1f", d)
        val parts = mutableListOf<String>()
        var audienceDone = false
        for (src in ORDER) {
            val x = v(src) ?: continue
            when (src) {
                "imdb" -> parts += "IMDb ${one(x)}"
                "tomatoes" -> parts += "RT ${x.toInt()}%"
                "popcorn", "tomatoesaudience" -> if (!audienceDone) { audienceDone = true; parts += "Audience ${x.toInt()}%" }
                "metacritic" -> parts += "Metacritic ${x.toInt()}"
                "letterboxd" -> parts += "Letterboxd ${one(x)}"
                "trakt" -> parts += "Trakt ${x.toInt()}%"
                "tmdb" -> parts += "TMDB ${x.toInt()}%"
                "rogerebert" -> parts += "Ebert ${one(x)}/4"
                "myanimelist" -> parts += "MAL ${one(x)}"
            }
        }
        val cert = o.optString("certification").trim().takeIf { it.isNotEmpty() && it != "null" }
        val age = cert?.let { c -> c + (KidsLock.ageOf(c)?.let { a -> if (a > 0) " ($a+)" else " (all ages)" } ?: "") } ?: ""
        return Result(parts.joinToString(" · "), age)
    }
}
