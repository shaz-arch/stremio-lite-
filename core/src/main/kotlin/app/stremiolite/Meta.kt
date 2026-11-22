package app.stremiolite

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
        )
    }


    fun epLabel(v: Video) = "S${v.season.toString().padStart(2, '0')}E${v.episode.toString().padStart(2, '0')}"

    /** Episodes in season order (specials, season 0, left out). */
    fun ordered(f: Full) = f.videos.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.episode }))

    /** Every video in Stremio's order (season, episode, release date), as its watched marks expect. */
    fun stremioOrder(f: Full) = f.videos.sortedWith(compareBy({ it.season }, { it.episode }, { it.released })).map { it.id }
}
