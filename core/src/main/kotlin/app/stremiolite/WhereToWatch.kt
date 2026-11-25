package app.stremiolite

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * "Where to watch" on a title's details, from TMDB with your own TMDB key: how it was first
 * released in your country (cinemas, a streaming service, TV) and where it's available now
 * (included with a service, free, to rent or buy: TMDB's data comes from JustWatch).
 */
object WhereToWatch {
    const val KEY_PAGE = "https://www.themoviedb.org/settings/api"
    private const val API = "https://api.themoviedb.org/3"
    private const val KEEP_MS = 24 * 3600_000L

    /** [released]: label → value lines, e.g. "UK cinemas" → "27 Nov 2026". */
    class Info(
        val released: List<Pair<String, String>>,
        val included: List<String>,
        val free: List<String>,
        val rent: List<String>,
        val buy: List<String>,
        val link: String,
    ) {
        val nothing get() = released.isEmpty() && included.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty()
    }

    class KeyRefused : Exception("TMDB didn't accept the key")

    private val cache = ConcurrentHashMap<String, Pair<Long, Info>>()
    private val failed = ConcurrentHashMap<String, String>()

    private fun cacheKey(type: String, id: String, region: String) = "$type|$id|$region"

    /** What's known for a title (null if not looked up lately). */
    fun known(type: String, id: String, region: String): Info? =
        cache[cacheKey(type, id, region)]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second

    fun error(type: String, id: String, region: String): String? = failed[cacheKey(type, id, region)]

    fun forget() { cache.clear(); failed.clear() }

    /** Region names for the details page: "GB" → "UK". */
    fun regionName(r: String) = when (r) { "GB" -> "UK"; "US" -> "US"; else -> r }

    private fun get(key: String, path: String): JSONObject {
        val bearer = key.startsWith("eyJ") // a TMDB "read access token" rather than the short API key
        val url = API + path + (if (bearer) "" else (if ('?' in path) "&" else "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"))
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            val code = c.responseCode
            if (code == 401) throw KeyRefused()
            if (code >= 400) throw Http.HttpError(code)
            return JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }

    /** TMDB's id for a title: from "tmdb:123", or by looking up its IMDb id ("tt…"). */
    private fun tmdbId(key: String, tv: Boolean, id: String, imdbUrl: String): String? {
        if (id.startsWith("tmdb:")) return id.removePrefix("tmdb:").substringBefore(':')
        val tt = Regex("tt\\d+").find(id)?.value ?: Regex("tt\\d+").find(imdbUrl)?.value ?: return null
        val r = get(key, "/find/$tt?external_source=imdb_id")
        val list = r.optJSONArray(if (tv) "tv_results" else "movie_results")
        return list?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }?.toString()
    }

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "2026-11-27…" → "27 Nov 2026". */
    fun nice(d: String): String {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(d) ?: return d
        return "${m.groupValues[3].toInt()} ${months[m.groupValues[2].toInt() - 1]} ${m.groupValues[1]}"
    }

    /** Looks a title up (blocking) and keeps the answer for a day. */
    fun load(key: String, type: String, id: String, imdbUrl: String, region: String): Info {
        val ck = cacheKey(type, id, region)
        known(type, id, region)?.let { return it }
        try {
            val tv = type == "series" || type == "tv"
            val tmdb = tmdbId(key, tv, id, imdbUrl) ?: throw IllegalStateException("TMDB doesn't know this title")
            val o = get(key, if (tv) "/tv/$tmdb?append_to_response=watch/providers" else "/movie/$tmdb?append_to_response=release_dates,watch/providers")
            val info = Info(
                if (tv) aired(o) else released(o, region),
                names(o, region, "flatrate"),
                (names(o, region, "free") + names(o, region, "ads")).distinct(),
                names(o, region, "rent"),
                names(o, region, "buy"),
                o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region)?.optString("link").orEmpty(),
            )
            cache[ck] = System.currentTimeMillis() to info
            failed.remove(ck)
            return info
        } catch (e: Exception) {
            failed[ck] = if (e is KeyRefused) "TMDB didn't accept your key" else Guide.why(e)
            throw e
        }
    }

    private fun names(o: JSONObject, region: String, kind: String): List<String> {
        val a = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region)?.optJSONArray(kind) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .sortedBy { it.optInt("display_priority", 999) }
            .map { it.optString("provider_name").replace(" Amazon Channel", "").replace(" Apple TV Channel", "") }
            .filter { it.isNotBlank() }.distinct().take(8)
    }

    /**
     * A film's first releases in [region]: cinemas (with the date), digital (with the service
     * when TMDB notes it, e.g. Netflix), TV. If TMDB has nothing for the region, its first
     * release anywhere.
     */
    private fun released(o: JSONObject, region: String): List<Pair<String, String>> {
        val where = regionName(region)
        val all = o.optJSONObject("release_dates")?.optJSONArray("results")
        val mine = (0 until (all?.length() ?: 0)).mapNotNull { all!!.optJSONObject(it) }
            .firstOrNull { it.optString("iso_3166_1") == region }?.optJSONArray("release_dates")
        val dates = (0 until (mine?.length() ?: 0)).mapNotNull { mine!!.optJSONObject(it) }
            .sortedBy { it.optString("release_date") }
        val out = mutableListOf<Pair<String, String>>()
        fun first(vararg types: Int) = dates.firstOrNull { it.optInt("type") in types }
        fun note(d: JSONObject) = d.optString("note").trim().takeIf { it.isNotEmpty() && it.length < 40 }
        first(3, 2)?.let { d ->
            out += "$where cinemas" to nice(d.optString("release_date")) + if (d.optInt("type") == 2) " (limited)" else ""
        }
        first(4)?.let { d -> out += (note(d)?.let { "$it (digital)" } ?: "Digital") to nice(d.optString("release_date")) }
        if (out.isEmpty()) first(6)?.let { d -> out += (note(d)?.let { "TV: $it" } ?: "TV") to nice(d.optString("release_date")) }
        if (out.isEmpty()) first(5)?.let { d -> out += "DVD / Blu-ray" to nice(d.optString("release_date")) }
        if (out.isEmpty()) o.optString("release_date").takeIf { it.isNotBlank() }?.let { out += "First released" to nice(it) }
        return out
    }

    /** A series: its network(s) and when it first aired. */
    private fun aired(o: JSONObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val nets = o.optJSONArray("networks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() } }.orEmpty()
        if (nets.isNotEmpty()) out += "Network" to nets.take(3).joinToString(", ")
        o.optString("first_air_date").takeIf { it.isNotBlank() }?.let { out += "First aired" to nice(it) }
        return out
    }
}
