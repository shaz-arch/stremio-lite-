package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Intro, recap and credits times for TV episodes, from the free community databases:
 * TheIntroDB (theintrodb.org) first, then IntroDB (introdb.app).
 */
object Skips {
    /** A part of an episode, in ms; [end] < 0 means "to the end". */
    class Seg(val kind: String, val start: Long, val end: Long)

    private val found = ConcurrentHashMap<String, List<Seg>>()

    /** What's been found for an episode ("tt…:2:5"), or null if not looked up yet. */
    fun known(video: String): List<Seg>? = found[video]

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Blocking; kept for the run. Only episodes ("tt…:season:episode"). */
    fun find(video: String): List<Seg> {
        found[video]?.let { return it }
        val p = video.split(':')
        if (p.size < 3 || !p[0].startsWith("tt")) return emptyList<Seg>().also { found[video] = it }
        val (tt, season, episode) = Triple(p[0], p[1], p[2])
        val segs = runCatching { tidb(tt, season, episode) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { introdb(tt, season, episode) }.getOrNull().orEmpty()
        found[video] = segs
        return segs
    }

    private fun ms(o: JSONObject, vararg keys: String, secs: Boolean = false): Long? {
        for (k in keys) if (o.has(k) && !o.isNull(k)) {
            val d = o.optDouble(k); if (!d.isNaN()) return if (secs || k.endsWith("sec") || k == "start" || k == "end") (d * 1000).toLong() else d.toLong()
        }
        return null
    }

    private fun tidb(tt: String, season: String, episode: String): List<Seg> {
        val url = "https://api.theintrodb.org/v4/media?imdb_id=${enc(tt)}&imdbId=${enc(tt)}&season=$season&episode=$episode"
        val o = JSONObject(Http.get(url, timeoutMs = 8_000))
        return listOf("recap", "intro", "credits", "preview").flatMap { kind ->
            val a = o.optJSONArray(kind) ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { s ->
                val start = ms(s, "start_ms", "startMs") ?: 0L
                val end = ms(s, "end_ms", "endMs") ?: -1L
                if (end in 0..start) null else Seg(kind, start, end)
            }
        }
    }

    private fun introdb(tt: String, season: String, episode: String): List<Seg> {
        val raw = runCatching { Http.get("https://api.introdb.app/segments?imdb_id=${enc(tt)}&season=$season&episode=$episode", timeoutMs = 8_000) }.getOrNull()
        val out = mutableListOf<Seg>()
        if (raw != null) {
            val o = runCatching { JSONObject(raw) }.getOrNull()
            // Either { intro: {start_sec,end_sec}, recap: …, outro: … } or a list of { segment_type, start_sec, end_sec }.
            val list = o?.optJSONArray("segments") ?: runCatching { JSONArray(raw) }.getOrNull()
            if (list != null) for (i in 0 until list.length()) list.optJSONObject(i)?.let { s ->
                val kind = s.optString("segment_type", s.optString("type"))
                val a = ms(s, "start_ms", "start_sec", "start"); val b = ms(s, "end_ms", "end_sec", "end")
                if (kind.isNotEmpty() && b != null) out += Seg(if (kind == "outro") "credits" else kind, a ?: 0, b)
            }
            else if (o != null) for (kind in listOf("recap", "intro", "outro")) o.optJSONObject(kind)?.let { s ->
                val a = ms(s, "start_ms", "start_sec", "start"); val b = ms(s, "end_ms", "end_sec", "end")
                if (b != null) out += Seg(if (kind == "outro") "credits" else kind, a ?: 0, b)
            }
        }
        if (out.isEmpty()) {
            val o = JSONObject(Http.get("https://api.introdb.app/intro?imdb=${enc(tt)}&season=$season&episode=$episode", timeoutMs = 8_000))
            val a = ms(o, "start", secs = true); val b = ms(o, "end", secs = true)
            if (b != null && b > 0) out += Seg("intro", a ?: 0, b)
        }
        return out
    }

    /** Where to start an episode from the beginning: after a recap or intro that opens it (else 0). */
    fun startAfterOpening(segs: List<Seg>): Long {
        var at = 0L
        segs.filter { it.kind == "recap" || it.kind == "intro" }.sortedBy { it.start }.forEach { s ->
            if (s.start <= at + 5_000 && s.end > at) at = s.end
        }
        return at
    }

    /** The recap or intro playing at [pos] (ms), to skip to its end; null if none. */
    fun skipAt(segs: List<Seg>, pos: Long): Seg? =
        segs.firstOrNull { (it.kind == "recap" || it.kind == "intro") && it.end > 0 && pos >= it.start && pos < it.end - 1_000 }

    /** Where the credits start (ms), or null. */
    fun creditsAt(segs: List<Seg>): Long? = segs.firstOrNull { it.kind == "credits" }?.start?.takeIf { it > 0 }
}
