package app.stremiolite

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The extra "Label  value" lines a details page shows, worked out the same way on every device. */
object DetailExtras {
    /** Title facts the add-on sent: released date, country, language, writers, age rating, awards, show status. */
    fun facts(f: Full, today: String): List<Pair<String, String>> = buildList {
        if (f.released.isNotBlank()) add("Released" to f.released)
        if (f.certification.isNotBlank()) add("Age rating" to f.certification)
        if (f.country.isNotBlank()) add("Country" to f.country.split(',').joinToString(", ") { c -> c.trim().let { if (it.length <= 3) it.uppercase() else it.replaceFirstChar { ch -> ch.uppercase() } } })
        if (f.language.isNotBlank()) add("Language" to f.language)
        if (f.writers.isNotEmpty()) add("Writers" to f.writers.take(3).joinToString(", "))
        if (f.awards.isNotBlank()) add("Awards" to f.awards)
        if (f.type == "series") {
            if (f.statusText.isNotBlank()) add("Status" to f.statusText)
            f.nextToAir(today)?.let { v ->
                add("Next episode" to "S${v.season}E${v.episode.toString().padStart(2, '0')} · ${MetaParse.niceDate(v.released)}")
            }
        }
    }

    /** Your own history with the title, from the synced library record. */
    fun history(i: LibraryItems.Info?, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        if (i == null || i.type == "other") return emptyList()
        return buildList {
            if (i.type == "movie" && i.timesWatched > 1) add("Times watched" to "${i.timesWatched}")
            if (i.lastWatched > 0) add("Last watched" to day(i.lastWatched, now))
            if (i.added > 0 && !i.temp) add("Added" to day(i.added, now))
        }
    }

    /** "today", "yesterday", "12 Nov", or "12 Nov 2024" for other years. */
    fun day(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
        val a = d.format(Date(ms))
        return when (a) {
            d.format(Date(now)) -> "today"
            d.format(Date(now - 86_400_000)) -> "yesterday"
            else -> SimpleDateFormat(if (a.take(4) == d.format(Date(now)).take(4)) "d MMM" else "d MMM yyyy", Locale.UK).format(Date(ms))
        }
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

/** Where a catalogue row comes from: its add-on, and a best guess at the list behind it. */
object RowSource {
    class About(val addon: String, val id: String, val type: String, val source: String, val link: String)

    fun about(addonName: String, c: Addon.Catalog): About {
        val (source, link) = guess(c.id)
        return About(addonName.substringBefore('|').trim().ifEmpty { addonName }, c.id, MetaParse.typeLabel(c.type), source, link)
    }

    /** A guess from the row's ID, e.g. "mdblist.84213" → MDBList list 84213 (with its page). */
    fun guess(id: String): Pair<String, String> {
        val l = id.lowercase()
        val num = Regex("""\d{2,}""").find(id)?.value
        return when {
            "mdblist" in l || l.startsWith("mdb.") ->
                (if (num != null) "MDBList list $num" else "MDBList list") to ""
            "trakt" in l -> "Trakt" + kind(l) to ""
            "letterboxd" in l -> "Letterboxd list" to ""
            "imdb" in l -> "IMDb" + kind(l) to ""
            "tmdb" in l -> "TMDB" + kind(l) to ""
            "tvdb" in l -> "TheTVDB" + kind(l) to ""
            "anilist" in l -> "AniList" + kind(l) to ""
            "mal" in l.split('.', '_', '-', ':') -> "MyAnimeList" + kind(l) to ""
            "simkl" in l -> "Simkl" + kind(l) to ""
            "streaming" in l || l.startsWith("nfx") || l.startsWith("dnp") || l.startsWith("hbm") || l.startsWith("amp") ->
                "A streaming service's catalogue" to ""
            else -> "not stated by the add-on" to ""
        }
    }

    private fun kind(l: String) = when {
        "genre" in l -> " (genre filter)"
        "trend" in l -> " (trending)"
        "popular" in l -> " (popular)"
        "top" in l || "rated" in l -> " (top rated)"
        "watchlist" in l -> " (your watchlist)"
        "list" in l -> " list"
        "discover" in l -> " (discover filter)"
        else -> ""
    }
}
