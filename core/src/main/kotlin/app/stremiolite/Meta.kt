package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/** One title as a catalogue row lists it. */
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
    /** e.g. "Continuing", "Ended" (when the add-on says). */
    val status: String = "",
)


/** Reading catalogue rows and title details, plus small text helpers both apps share. */
object MetaParse {
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

    fun cleanRating(r: String) = r.trim().takeUnless { it.isEmpty() || it == "null" || it.equals("N/A", true) || it == "0" } ?: ""

    fun typeLabel(type: String) = when (type) {
        "movie" -> "Movies"
        "series" -> "Shows"
        "all" -> "Movies & shows"
        else -> type.replaceFirstChar { it.uppercase() }
    }

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

    /**
     * A compact copy of a show for saving on the device: only what episode lists, counts and the
     * calendar need (no cast, descriptions or episode summaries). Read back with [full].
     */
    fun compact(f: Full): JSONObject {
        val vids = JSONArray()
        f.videos.forEach { v -> vids.put(JSONObject().put("id", v.id).put("title", v.title).put("season", v.season).put("episode", v.episode).put("released", v.released)) }
        return JSONObject().put("id", f.id).put("type", f.type).put("name", f.name).put("releaseInfo", f.year)
            .put("imdbRating", f.rating).put("runtime", f.runtime).put("status", f.status).put("source", f.source).put("videos", vids)
    }

    fun full(m: JSONObject, source: String): Full {
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
            rating = cleanRating(m.optString("imdbRating")),
            runtime = m.optString("runtime"),
            genres = strings(m, "genres", "genre"),
            director = strings(m, "director", "directors"),
            cast = strings(m, "cast"),
            description = m.optString("description"),
            videos = videos,
            source = source,
            status = m.optString("status"),
        )
    }


    /** Title details from the first add-on that can describe it (blocking). */
    fun fetch(addons: List<Addon>, type: String, id: String): Full {
        val candidates = addons.filter { it.supports("meta", type, id) }
        if (candidates.isEmpty()) throw IllegalStateException("none of your add-ons can describe this title")
        var last: Throwable? = null
        for (a in candidates) {
            try {
                val url = a.base + "/meta/" + enc(type) + "/" + enc(id) + ".json"
                val m = JSONObject(Http.get(url)).optJSONObject("meta") ?: continue
                return full(m, a.name)
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("no details found")
    }

    /** One page of a catalogue row (blocking). */
    fun page(a: Addon, c: Addon.Catalog, skip: Int = 0): List<Meta> =
        parse(Http.get(a.base + "/catalog/" + enc(c.type) + "/" + enc(c.id) + (if (skip > 0) "/skip=$skip" else "") + ".json"))

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun epLabel(v: Video) = "S${v.season.toString().padStart(2, '0')}E${v.episode.toString().padStart(2, '0')}"

    /** Episodes in season order (specials, season 0, left out). */
    fun ordered(f: Full) = f.videos.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))

    /** Every video in Stremio's order (season, episode, release date), as its watched marks expect. */
    fun stremioOrder(f: Full) = f.videos.sortedWith(compareBy({ it.season }, { it.episode }, { it.released })).map { it.id }
}
