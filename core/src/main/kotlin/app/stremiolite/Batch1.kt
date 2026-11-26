package app.stremiolite

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A title's trailer: the add-on's, else TMDB's (YouTube). */
object Trailer {
    /** The YouTube id in a trailer link, or null. */
    fun youtubeId(url: String): String? =
        Regex("""(?:v=|youtu\.be/|/embed/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)

    /** Blocking: TMDB's trailer (official ones first) as a YouTube link, or null. */
    fun fromTmdb(key: String, type: String, id: String): String? {
        if (key.isBlank()) return null
        val tv = type == "series"
        val tmdb = runCatching { WhereToWatch.tmdbId(key, tv, id, "") }.getOrNull() ?: return null
        val r = runCatching { WhereToWatch.get(key, "/${if (tv) "tv" else "movie"}/$tmdb/videos") }.getOrNull() ?: return null
        val all = r.optJSONArray("results") ?: return null
        val vids = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
        val pick = vids.sortedWith(compareBy<JSONObject>({ it.optString("type") != "Trailer" }, { !it.optBoolean("official") })).firstOrNull() ?: return null
        return "https://www.youtube.com/watch?v=" + pick.optString("key")
    }
}

/**
 * "Send to TV": a title (or one episode) picked on the phone, kept on the account; a TV-side
 * device (Fire Stick, Linux) sees it within seconds and plays it.
 */
object SendToTv {
    const val ID = "stremiolite:sendtotv"

    class Request(val id: String, val type: String, val name: String, val year: String, val video: String, val at: Long, val from: String)

    fun parse(raw: String?): Request? {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val r = runCatching { JSONObject(o.optString("name")) }.getOrNull() ?: return null
        if (r.optString("id").isEmpty()) return null
        return Request(r.optString("id"), r.optString("type"), r.optString("title"), r.optString("year"), r.optString("video"), r.optLong("at"), r.optString("from"))
    }

    fun record(base: String?, r: Request): JSONObject {
        val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "{}")
        val body = JSONObject().put("id", r.id).put("type", r.type).put("title", r.name).put("year", r.year)
            .put("video", r.video).put("at", r.at).put("from", r.from)
        return item.put("name", body.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** Requests older than this are ignored (the TV was off when it was sent). */
    const val FRESH_MS = 10 * 60_000L
}

/** The Calendar as an .ics file your calendar app can import, and one event as a calendar entry. */
object CalendarFile {
    class Item(val day: String, val title: String, val note: String)

    private fun stamp(ms: Long) = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\;").replace(",", "\\,").replace("\n", "\\n")

    /** All-day events, one per item. */
    fun ics(items: List<Item>): String {
        val now = stamp(System.currentTimeMillis())
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Stremonino//Calendar//EN\r\nX-WR-CALNAME:Stremonino\r\n")
        items.forEach { i ->
            val d = i.day.replace("-", "")
            val next = runCatching {
                val f = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
                SimpleDateFormat("yyyyMMdd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(f.parse(i.day)!!.time + 86_400_000L))
            }.getOrDefault(d)
            sb.append("BEGIN:VEVENT\r\nUID:${esc(i.title + i.day).hashCode().toUInt()}-${d}@stremonino\r\nDTSTAMP:$now\r\n")
            sb.append("DTSTART;VALUE=DATE:$d\r\nDTEND;VALUE=DATE:$next\r\nSUMMARY:${esc(i.title)}\r\n")
            if (i.note.isNotBlank()) sb.append("DESCRIPTION:${esc(i.note)}\r\n")
            sb.append("TRANSP:TRANSPARENT\r\nEND:VEVENT\r\n")
        }
        return sb.append("END:VCALENDAR\r\n").toString()
    }
}
