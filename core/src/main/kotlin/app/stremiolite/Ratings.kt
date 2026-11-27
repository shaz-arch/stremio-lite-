package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Review scores and the age rating for a title's page, from MDBList (your own key), shown the way
 * Settings › Ratings says: which scores, in what order, with vote counts or as percentages.
 * Episode scores come from TMDB (your own key).
 */
object Ratings {
    /** One score: [value] on the source's own scale, [votes] if known. */
    class Score(val value: Double, val votes: Long)

    /** Everything MDBList had for a title; [overall] is its combined 0–100 score. */
    class Result(val scores: Map<String, Score>, val overall: Int, val age: String)

    /** Every score that can be shown: (key, name). */
    val SOURCES = listOf(
        "overall" to "Overall", "imdb" to "IMDb", "tomatoes" to "Rotten Tomatoes", "audience" to "Audience (Popcornmeter)",
        "metacritic" to "Metacritic", "metacriticuser" to "Metacritic users", "letterboxd" to "Letterboxd", "trakt" to "Trakt",
        "tmdb" to "TMDB", "rogerebert" to "Roger Ebert", "myanimelist" to "MyAnimeList",
    )
    val DEFAULT_ORDER = listOf("overall", "imdb", "tomatoes", "audience", "metacritic", "letterboxd", "trakt", "tmdb", "rogerebert", "myanimelist")

    /** How they're shown (Settings › Ratings). */
    class Config(val order: List<String> = DEFAULT_ORDER, val votes: Boolean = false, val percent: Boolean = false) {
        fun encode() = order.joinToString(",")
        companion object { fun orderOf(raw: String?) = raw?.split(',')?.map { it.trim() }?.filter { k -> SOURCES.any { it.first == k } }?.takeIf { raw.isNotBlank() } ?: DEFAULT_ORDER }
    }

    private const val KEEP_MS = 12 * 3_600_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, Result>>()

    fun known(id: String): Result? = cache[id]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second

    /** Blocking; only IMDb ids (tt…). */
    fun load(key: String, type: String, id: String): Result? {
        if (key.isBlank() || !id.startsWith("tt")) return null
        known(id)?.let { return it }
        val kind = if (type == "series") "show" else "movie"
        val o = JSONObject(Http.get("https://api.mdblist.com/imdb/$kind/${enc(id)}?apikey=${enc(key)}", timeoutMs = 12_000))
        return parse(o).also { cache[id] = System.currentTimeMillis() to it }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun parse(o: JSONObject): Result {
        val out = HashMap<String, Score>()
        val arr = o.optJSONArray("ratings")
        if (arr != null) for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.isNull("value")) continue
            val v = r.optDouble("value"); if (v.isNaN() || v <= 0) continue
            val key = when (val s = r.optString("source").lowercase()) { "popcorn", "tomatoesaudience" -> "audience"; else -> s }
            if (key !in out) out[key] = Score(v, if (r.isNull("votes")) 0 else r.optLong("votes"))
        }
        val cert = o.optString("certification").trim().takeIf { it.isNotEmpty() && it != "null" }
        return Result(out, o.optInt("score", 0), cert?.let { withAge(it) } ?: "")
    }

    private fun one(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(java.util.Locale.UK, "%.1f", d)
    private fun votes(n: Long) = when { n >= 1_000_000 -> one(Math.round(n / 100_000.0) / 10.0) + "M"; n >= 1_000 -> "${Math.round(n / 1000.0)}k"; else -> "$n" }

    /** A score as a percentage, whatever its scale. */
    private fun pct(key: String, v: Double): Int = Math.round(when (key) {
        "imdb", "metacriticuser", "myanimelist" -> v * 10
        "letterboxd" -> v * 20
        "rogerebert" -> v * 25
        else -> v
    }).toInt()

    /** "IMDb 7.4 (52k)", "RT 84%", "Letterboxd 3.9"… in the chosen order. */
    fun parts(r: Result, c: Config): List<String> = c.order.mapNotNull { key ->
        if (key == "overall") return@mapNotNull if (r.overall > 0) "Overall ${r.overall}" + if (c.percent) "%" else "" else null
        val s = r.scores[key] ?: return@mapNotNull null
        val name = when (key) {
            "imdb" -> "IMDb"; "tomatoes" -> "RT"; "audience" -> "Audience"; "metacritic" -> "Metacritic"; "metacriticuser" -> "MC users"
            "letterboxd" -> "Letterboxd"; "trakt" -> "Trakt"; "tmdb" -> "TMDB"; "rogerebert" -> "Ebert"; "myanimelist" -> "MAL"; else -> return@mapNotNull null
        }
        val value = when {
            c.percent -> "${pct(key, s.value)}%"
            key in setOf("tomatoes", "audience", "trakt", "tmdb") -> "${s.value.toInt()}%"
            key == "metacritic" -> "${s.value.toInt()}"
            key == "rogerebert" -> "${one(s.value)}/4"
            else -> one(s.value)
        }
        "$name $value" + if (c.votes && s.votes > 0) " (${votes(s.votes)})" else ""
    }

    /** The scores in lines of about [width] characters, so they wrap whole rather than being cut off. */
    fun lines(r: Result, c: Config, width: Int): List<String> {
        val out = mutableListOf<String>()
        var cur = ""
        for (p in parts(r, c)) {
            cur = if (cur.isEmpty()) p else if (cur.length + 3 + p.length <= width) "$cur · $p" else { out += cur; p }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** "15 (15+)", "PG (8+)", "U (all ages)". */
    fun withAge(c: String): String = c + (KidsLock.ageOf(c)?.let { a -> if (a > 0) " ($a+)" else " (all ages)" } ?: "")

    /**
     * The age rating to show: the title's own (from your metadata add-on, which follows your country,
     * so UK first), and MDBList's US one only when there's none.
     */
    fun ageLine(own: String, r: Result?): String = own.trim().takeIf { it.isNotEmpty() }?.let { withAge(it) } ?: r?.age.orEmpty()

    // ---- episodes (TMDB) ------------------------------------------------------------------------

    private val tvIds = ConcurrentHashMap<String, Int>()
    private val episodes = ConcurrentHashMap<String, Pair<Long, Score?>>()

    fun knownEpisode(videoId: String): Score? = episodes[videoId]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second
    fun triedEpisode(videoId: String) = episodes[videoId]?.let { System.currentTimeMillis() - it.first < KEEP_MS } == true

    /** TMDB's score for an episode ("tt…:2:5"), blocking; null if TMDB has none. */
    fun episode(tmdbKey: String, videoId: String): Score? {
        if (tmdbKey.isBlank()) return null
        val p = videoId.split(':'); if (p.size < 3 || !p[0].startsWith("tt")) return null
        val show = p[0]; val season = p[1].toIntOrNull() ?: return null; val ep = p[2].toIntOrNull() ?: return null
        val tv = tvIds[show] ?: JSONObject(Http.get("https://api.themoviedb.org/3/find/$show?external_source=imdb_id&api_key=${enc(tmdbKey)}", timeoutMs = 10_000))
            .optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.also { tvIds[show] = it } ?: return null
        val o = JSONObject(Http.get("https://api.themoviedb.org/3/tv/$tv/season/$season/episode/$ep?api_key=${enc(tmdbKey)}", timeoutMs = 10_000))
        val v = o.optDouble("vote_average"); val n = o.optLong("vote_count")
        val s = if (v.isNaN() || v <= 0 || n <= 0) null else Score(v, n)
        episodes[videoId] = System.currentTimeMillis() to s
        return s
    }

    private val seasons = ConcurrentHashMap<String, Long>()

    /** A season's episode list has been asked for lately ("tt…:2"). */
    fun triedSeason(show: String, season: Int) = seasons["$show:$season"]?.let { System.currentTimeMillis() - it < KEEP_MS } == true

    /** TMDB's scores for a whole season in one call (blocking); fills [knownEpisode] for each. */
    fun season(tmdbKey: String, show: String, season: Int): Boolean {
        if (tmdbKey.isBlank() || !show.startsWith("tt")) return false
        seasons["$show:$season"] = System.currentTimeMillis()
        val tv = tvIds[show] ?: JSONObject(Http.get("https://api.themoviedb.org/3/find/$show?external_source=imdb_id&api_key=${enc(tmdbKey)}", timeoutMs = 10_000))
            .optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.also { tvIds[show] = it } ?: return false
        val eps = JSONObject(Http.get("https://api.themoviedb.org/3/tv/$tv/season/$season?api_key=${enc(tmdbKey)}", timeoutMs = 12_000)).optJSONArray("episodes") ?: return false
        val now = System.currentTimeMillis()
        for (i in 0 until eps.length()) {
            val e = eps.optJSONObject(i) ?: continue
            val v = e.optDouble("vote_average"); val n = e.optLong("vote_count")
            episodes["$show:$season:${e.optInt("episode_number")}"] = now to (if (v.isNaN() || v <= 0 || n <= 0) null else Score(v, n))
        }
        return true
    }

    /** "★8.9" for an episode list (nothing if not known). */
    fun episodeShort(videoId: String, c: Config): String =
        knownEpisode(videoId)?.let { s -> "★" + if (c.percent) "${Math.round(s.value * 10)}%" else one(Math.round(s.value * 10) / 10.0) }.orEmpty()

    fun episodeLine(s: Score, c: Config): String =
        "TMDB " + (if (c.percent) "${Math.round(s.value * 10)}%" else one(Math.round(s.value * 10) / 10.0)) + if (c.votes) " (${votes(s.votes)} votes)" else ""
}
