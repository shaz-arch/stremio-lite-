package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * "For you": suggestions from what you watch, using TMDB's "people who liked this also liked".
 * Top picks are titles several of your recent watches point to; "Because you watched" rows follow
 * one title each; "More like your favourites" follows your ♡ titles.
 */
object ForYou {
    class Seed(val id: String, val type: String, val name: String)
    class Pick(val id: String, val type: String, val name: String, val year: String)
    class Result(val top: List<Pick>, val because: List<Pair<Seed, List<Pick>>>, val favourites: List<Pick>, val at: Long)

    private const val PER_ROW = 12
    private val imdbOf = ConcurrentHashMap<String, String>()

    private class Rec(val tmdb: String, val kind: String, val name: String, val year: String)

    /** Blocking: TMDB's recommendations for one title (best first). */
    private fun recs(key: String, s: Seed): List<Rec> {
        val tv = s.type == "series"
        val kind = if (tv) "tv" else "movie"
        val id = runCatching { WhereToWatch.tmdbId(key, tv, s.id, "") }.getOrNull() ?: return emptyList()
        val r = runCatching { WhereToWatch.get(key, "/$kind/$id/recommendations") }.getOrNull() ?: return emptyList()
        val a = r.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("adult") }.map {
            Rec(it.optLong("id").toString(), kind, it.optString(if (tv) "name" else "title"),
                it.optString(if (tv) "first_air_date" else "release_date").take(4))
        }.filter { it.name.isNotBlank() }
    }

    /** The title's IMDb id ("tt…"), which the add-ons and your history use; else "tmdb:…". */
    private fun pick(key: String, r: Rec): Pick {
        val ck = r.kind + ":" + r.tmdb
        val id = imdbOf[ck] ?: (runCatching { WhereToWatch.get(key, "/${r.kind}/${r.tmdb}/external_ids").optString("imdb_id") }.getOrNull()
            ?.takeIf { it.startsWith("tt") } ?: "tmdb:${r.tmdb}").also { imdbOf[ck] = it }
        return Pick(id, if (r.kind == "tv") "series" else "movie", r.name, r.year)
    }

    /**
     * Blocking. [recent] newest first; [exclude] is what you've watched, started or lined up.
     * Up to [rows] "Because you watched" rows.
     */
    fun build(key: String, recent: List<Seed>, favourites: List<Seed>, exclude: Set<String>, rows: Int = 3, avoid: Set<String> = emptySet()): Result {
        val seeds = recent.take(6)
        val got = seeds.associateWith { recs(key, it) }
        // Favourites you've also watched lately still count (their lists are already fetched).
        val favGot = favourites.take(5).associateWith { f -> seeds.firstOrNull { it.id == f.id }?.let { got[it] } ?: recs(key, f) }
        val seedIds = (recent + favourites).map { it.id }.toSet()
        fun ok(p: Pick) = p.id !in exclude && p.id !in seedIds

        // Top picks: a title pointed to by several recent watches, near the top of each list.
        val score = HashMap<String, Double>(); val byKey = HashMap<String, Rec>()
        got.values.forEachIndexed { si, list ->
            list.forEachIndexed { i, r ->
                val k = r.kind + ":" + r.tmdb
                byKey[k] = r
                score[k] = (score[k] ?: 0.0) + (1.0 / (1 + i * 0.15)) * (1.0 / (1 + si * 0.1))
            }
        }
        // Refresh suggestions: titles not in the last list first, the rest only to fill it.
        val ranked = score.entries.sortedByDescending { it.value }.asSequence().map { pick(key, byKey.getValue(it.key)) }.filter(::ok).take(PER_ROW * 3).toList()
        val top = (ranked.filter { it.id !in avoid } + ranked.filter { it.id in avoid }).take(PER_ROW + 8)
        val shown = top.map { it.id }.toMutableSet()
        val because = seeds.filter { got[it].orEmpty().isNotEmpty() }.take(rows).map { s ->
            s to got.getValue(s).asSequence().map { pick(key, it) }.filter { ok(it) }.take(PER_ROW).toList()
        }.filter { it.second.size >= 3 }
        because.forEach { (_, l) -> shown += l.map { it.id } }
        val favPool = favGot.values.flatMap { it.take(10) }.distinctBy { it.kind + it.tmdb }.map { pick(key, it) }.filter(::ok)
        // Fresh titles first; ones already in the rows above only to fill it out.
        val favs = (favPool.filter { it.id !in shown } + favPool.filter { it.id in shown }).take(PER_ROW)
        return Result(top, because, favs, System.currentTimeMillis())
    }

    // ---- "More like this" on a title's page ----------------------------------------------------

    private val similar = ConcurrentHashMap<String, Pair<Long, List<Pick>>>()

    /** What's already been found for [id] (kept a day), or null. */
    fun knownSimilar(id: String): List<Pick>? = similar[id]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second

    /** Blocking: titles like [id] (TMDB's recommendations), up to [max]. */
    fun findSimilar(key: String, type: String, id: String, name: String, max: Int = 15): List<Pick> {
        knownSimilar(id)?.let { return it }
        val list = recs(key, Seed(id, type, name)).take(max).map { pick(key, it) }.filter { it.id != id }
        similar[id] = System.currentTimeMillis() to list
        return list
    }

    // ---- kept on the device, so Home has them without asking TMDB every time -------------------

    private fun pj(p: Pick) = JSONObject().put("id", p.id).put("type", p.type).put("name", p.name).put("year", p.year)
    private fun pr(o: JSONObject) = Pick(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"))
    private fun list(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) }.map(::pr) }.orEmpty()

    fun json(r: Result): String = JSONObject().put("at", r.at).put("v", 2)
        .put("top", JSONArray(r.top.map(::pj)))
        .put("because", JSONArray(r.because.map { (s, l) -> JSONObject().put("id", s.id).put("type", s.type).put("name", s.name).put("list", JSONArray(l.map(::pj))) }))
        .put("favs", JSONArray(r.favourites.map(::pj))).toString()

    fun parse(raw: String?): Result? = runCatching {
        val o = JSONObject(raw!!)
        if (o.optInt("v") < 2) return null   // built before favourites counted: build again
        val b = o.optJSONArray("because")
        Result(list(o.optJSONArray("top")),
            (0 until (b?.length() ?: 0)).mapNotNull { b!!.optJSONObject(it) }.map { Seed(it.optString("id"), it.optString("type"), it.optString("name")) to list(it.optJSONArray("list")) },
            list(o.optJSONArray("favs")), o.optLong("at"))
    }.getOrNull()

    /** Rebuilt once a day. */
    const val KEEP_MS = 24 * 3_600_000L
}
