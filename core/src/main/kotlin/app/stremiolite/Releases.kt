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

    /** Episodes (not specials) due after today and within [days] days, soonest first. */
    fun upcoming(show: Full, days: Int = 30): List<Video> {
        val today = day()
        val until = day(-days)
        return show.videos.filter { it.season > 0 && it.released.length == 10 && it.released > today && it.released <= until }
            .sortedBy { it.released }
    }

    /** "Today", "Tomorrow" or e.g. "Fri 28 Nov" for a "yyyy-MM-dd" date. */
    fun dayName(date: String): String {
        if (date == day()) return "Today"
        if (date == day(-1)) return "Tomorrow"
        return runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
            SimpleDateFormat("EEE d MMM", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(f.parse(date)!!)
        }.getOrDefault(date)
    }

    /**
     * Whether [ep] is news to you, not just the next one while you work through a show. A season's
     * first episode always is. Any other only if you'd caught up when it came out: every earlier
     * episode of its season watched, and nothing watched since the day after it aired
     * ([lastWatched], the show's latest watch time). So S2E10 isn't "new" while you're on S2E9.
     */
    fun isNews(show: Full, ep: Video, watched: Set<String>, lastWatched: Long): Boolean {
        if (ep.episode <= 1) return true
        val earlier = show.videos.filter { it.season == ep.season && it.episode in 1 until ep.episode }
        if (earlier.any { it.id !in watched }) return false
        val aired = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(ep.released)!!.time
        }.getOrNull() ?: return true
        return lastWatched <= aired + 86_400_000L
    }

    /**
     * Whether a new episode belongs in Ready (and the Home line). Never a special, nor one out
     * before you followed the show ([since]), nor a new show's first episode you weren't
     * following for. A show you're watching ([continuing]) or follow for every episode:
     * news by [isNews]. One followed for new seasons: only a season's first episode.
     */
    fun isReady(show: Full, ep: Video, watched: Set<String>, lastWatched: Long, continuing: Boolean, mode: String, since: Long): Boolean {
        if (ep.season <= 0 || ep.episode <= 0) return false
        if (since > 0) {
            val from = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(since))
            if (ep.released < from) return false
        }
        if (ep.season == 1 && ep.episode == 1 && since == 0L) return false
        if (continuing || mode == Following.EPISODES) return isNews(show, ep, watched, lastWatched)
        return ep.episode == 1
    }

    /** The first episode of a season after the first. */
    fun opensSeason(v: Video) = v.season > 1 && v.episode == 1

    /** Shows the add-on marks as ended, with nothing out lately, needn't be checked in the background. */
    fun finished(show: Full) = show.status.equals("Ended", ignoreCase = true) && recent(show, day(60)).isEmpty()

    private fun key(v: Video) = "%04d%04d".format(v.season, v.episode)

    /** What to tell someone about one show since the last check. */
    class Alert(val show: Full, val episodes: List<Video>) {
        val newSeason: Int? get() = episodes.firstOrNull { opensSeason(it) }?.season

        /** e.g. "ready to watch" or "no stream yet". */
        var note: String = ""

        val text: String get() = when {
            newSeason != null -> "New season: ${show.name} season $newSeason"
            episodes.size == 1 -> "New episode: ${show.name} ${MetaParse.epLabel(episodes[0])}"
            else -> "${show.name}: ${episodes.size} new episodes"
        } + if (note.isNotEmpty()) ", $note" else ""
    }

    /** Days since [date] ("yyyy-MM-dd"), or 0 if it can't be read. */
    fun daysSince(date: String): Int = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
        ((System.currentTimeMillis() - f.parse(date)!!.time) / 86_400_000L).toInt()
    }.getOrDefault(0)

    /** How long an episode can be out without a stream before you're told anyway. */
    const val WAIT_FOR_STREAM_DAYS = 2

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
