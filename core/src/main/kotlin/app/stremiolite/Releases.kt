package app.stremiolite

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * New episodes and seasons for the shows you follow: your Library, Continue watching, and your
 * own list rows (e.g. the MDBList Watchlist). Shared by the Android and Linux apps.
 */
object Releases {
    const val DAYS = 7

    /** A UTC date as "yyyy-MM-dd", [offsetDays] ago. */
    fun day(offsetDays: Int = 0): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() - offsetDays * 86_400_000L))

    /** Episodes (not specials) out between [from] and [to], inclusive. */
    fun recent(show: Full, from: String = day(DAYS), to: String = day()): List<Video> =
        show.videos.filter { it.season > 0 && it.released.length == 10 && it.released >= from && it.released <= to }

    /** The first episode of a season after the first. */
    fun opensSeason(v: Video) = v.season > 1 && v.episode == 1

    /** Shows the add-on marks as ended, with nothing out lately, needn't be checked in the background. */
    fun finished(show: Full) = show.status.equals("Ended", ignoreCase = true) && recent(show, day(60)).isEmpty()

    private fun key(v: Video) = "%04d%04d".format(v.season, v.episode)

    /** What to tell someone about one show since the last check. */
    class Alert(val show: Full, val episodes: List<Video>) {
        val newSeason: Int? get() = episodes.firstOrNull { opensSeason(it) }?.season

        val text: String get() = when {
            newSeason != null -> "New season: ${show.name} season $newSeason"
            episodes.size == 1 -> "New episode: ${show.name} ${MetaParse.epLabel(episodes[0])}"
            else -> "${show.name}: ${episodes.size} new episodes"
        }
    }

    /**
     * Compares a show with what was last seen ([seen]: the newest released episode then).
     * Returns the alert (if anything new is out) and the new marker to remember. The first look
     * at a show only sets the marker, so following a show doesn't announce its whole back catalogue.
     */
    fun check(show: Full, seen: String?, today: String = day()): Pair<Alert?, String?> {
        val out = show.videos.filter { it.season > 0 && it.released.length == 10 && it.released <= today }
        val newest = out.maxByOrNull { key(it) } ?: return null to seen
        val mark = key(newest)
        if (seen == null) return null to mark
        val fresh = out.filter { key(it) > seen }.sortedBy { key(it) }
        return (if (fresh.isEmpty()) null else Alert(show, fresh)) to mark
    }

    /** Series ids and names from your own list rows (first page of each; blocking). */
    fun fromPersonalRows(addons: List<Addon>): List<Pair<String, String>> =
        Folders.rowAddons(addons).filter { !it.isLocal }.flatMap { a ->
            a.catalogs.filter { Folders.isPersonal(it) && Folders.openable(it) && (it.type == "series" || it.type == "all") }.flatMap { c ->
                runCatching { MetaParse.page(a, c) }.getOrDefault(emptyList()).filter { it.type == "series" }.map { it.id to it.name }
            }
        }
}
