package app.stremiolite

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Film dates for the Calendar: when films you're interested in (waiting on, lined up, in the
 * basket) open in cinemas and come out to stream, from TMDB (or the add-ons' release day).
 */
object FilmDates {
    class Film(val id: String, val name: String, val year: String)

    /** One dated thing: [day] "yyyy-MM-dd", [what] "in cinemas", "Netflix", "out to stream". */
    class Event(val day: String, val film: Film, val what: String)

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** Blocking: the films' dates from today up to [days] ahead, soonest first. */
    fun events(addons: List<Addon>, tmdbKey: String, region: String, films: List<Film>, days: Int = 30): List<Event> {
        val from = Releases.day(); val to = Releases.day(-days)
        val out = mutableListOf<Event>()
        films.distinctBy { it.id }.forEach { f ->
            val w = if (tmdbKey.isBlank()) null else runCatching { WhereToWatch.load(tmdbKey, "movie", f.id, "", region) }.getOrNull()
            if (w != null && (w.cinemaFrom > 0 || w.digitalFrom > 0)) {
                if (w.cinemaFrom > 0) out += Event(iso(w.cinemaFrom), f, "in cinemas")
                if (w.digitalFrom > 0) out += Event(iso(w.digitalFrom), f, w.where.takeIf { it.isNotBlank() && it != "Cinemas" } ?: "out to stream")
            } else {
                val day = runCatching { MetaParse.fetch(addons, "movie", f.id) }.getOrNull()?.releasedDay.orEmpty().take(10)
                if (day.length == 10) out += Event(day, f, "released")
            }
        }
        return out.filter { it.day in from..to }.sortedWith(compareBy<Event> { it.day }.thenBy { it.film.name })
    }
}
