package app.stremiolite

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Coming soon: films out in the next few weeks, from the "Upcoming" list in your own catalogues
 * (AIOMetadata's TMDB Upcoming, for example), with each one's release date looked up and the
 * list grouped into this week, next week and later.
 */
object ComingSoon {
    class Entry(val addon: Addon, val meta: Meta) {
        /** Release date (UTC midnight), 0 when the add-on doesn't say, -1 when not looked up yet. */
        @Volatile var date: Long = -1
    }

    private const val KEEP_MS = 6 * 3600_000L
    @Volatile private var cached: Pair<Long, List<Entry>>? = null
    @Volatile private var cachedFrom = ""

    private fun upcoming(c: Addon.Catalog) =
        c.id.contains("upcoming", true) || c.name.contains("upcoming", true) || c.name.contains("coming soon", true)

    private fun inCinemas(c: Addon.Catalog) =
        c.id.contains("now_playing", true) || Regex("in cinemas|in theat|now playing|box office", RegexOption.IGNORE_CASE).containsMatchIn(c.name)

    private fun anime(c: Addon.Catalog) = c.id.contains("anime", true) || c.name.contains("anime", true)

    /** Film lists Coming soon can use (including rows hidden from Stremio's home: those need a genre, which AIOMetadata fills in). */
    fun choices(addons: List<Addon>): List<Pair<Addon, Addon.Catalog>> =
        addons.flatMap { a -> a.catalogs.filter { it.type == "movie" && Folders.openable(it) }.map { a to it } }
            // The same list can come from two installs (your rows and the stick-rows copy): once is enough.
            .distinctBy { (_, c) -> c.id + "|" + c.type }

    /** The list [chosen] (a row key), if it's still there. */
    fun find(addons: List<Addon>, chosen: String): Pair<Addon, Addon.Catalog>? =
        choices(addons).firstOrNull { (a, c) -> Folders.rowKey(a, c) == chosen }

    /**
     * The lists to read: the one you chose, or else your Upcoming list (not anime: those have
     * their own rows) and your In Cinemas list, so films just out show too.
     */
    fun catalogs(addons: List<Addon>, chosen: String = ""): List<Pair<Addon, Addon.Catalog>> {
        if (chosen.isNotEmpty()) find(addons, chosen)?.let { return listOf(it) }
        val all = choices(addons).filter { (_, c) -> !anime(c) }
        return (all.filter { upcoming(it.second) }.take(1) + all.filter { inCinemas(it.second) }.take(1))
    }

    /** The upcoming films (dates not yet looked up), kept for six hours. Blocking. */
    fun entries(addons: List<Addon>, chosen: String = ""): List<Entry> {
        cached?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS && cachedFrom == chosen }?.let { return it.second }
        val seen = HashSet<String>()
        val list = catalogs(addons, chosen).flatMap { (a, c) ->
            (runCatching { MetaParse.page(a, c) }.getOrDefault(emptyList()) +
                runCatching { MetaParse.page(a, c, 20) }.getOrDefault(emptyList())).map { Entry(a, it) }
        }.filter { seen.add(it.meta.id) }
        if (list.isNotEmpty()) { cached = System.currentTimeMillis() to list; cachedFrom = chosen }
        return list
    }

    /** Looks up one film's release date from its details. Blocking. */
    fun lookUp(addons: List<Addon>, e: Entry) {
        if (e.date >= 0) return
        e.date = runCatching { day(MetaParse.fetch(addons, e.meta.type, e.meta.id).released) }.getOrDefault(0L)
    }

    /** "2026-12-18…" → that day (UTC midnight), or 0. */
    fun day(released: String): Long {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(released) ?: return 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        return cal.timeInMillis
    }

    /** Today at UTC midnight (release dates are days, not times). */
    fun today(now: Long = System.currentTimeMillis()): Long = now - now % 86_400_000L

    /**
     * The films grouped: out this week, next week, later this month, later; then those already
     * out (from the last fortnight) and those with no date yet. Each group is in date order.
     */
    fun groups(entries: List<Entry>, now: Long = System.currentTimeMillis()): List<Pair<String, List<Entry>>> {
        val t = today(now)
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.UK).apply { timeInMillis = t }
        // Weeks run Monday to Sunday, as on UK listings.
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val weekEnd = t + (7 - dow) * 86_400_000L
        val nextWeekEnd = weekEnd + 7 * 86_400_000L
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        val monthEnd = cal.timeInMillis + 86_400_000L
        val dated = entries.filter { it.date > 0 }.sortedBy { it.date }
        fun pick(name: String, from: Long, until: Long) = name to dated.filter { it.date in from until until }
        return listOf(
            pick("This week", t, weekEnd),
            pick("Next week", weekEnd, nextWeekEnd),
            pick("Later this month", nextWeekEnd, monthEnd),
            pick("Later", maxOf(monthEnd, nextWeekEnd), Long.MAX_VALUE),
            "Just out" to dated.filter { it.date in (t - 14 * 86_400_000L) until t }.reversed(),
            "Date not set yet" to entries.filter { it.date == 0L },
        ).filter { it.second.isNotEmpty() }
    }

    /** "Fri 18 Dec" */
    fun label(date: Long): String = java.text.SimpleDateFormat("EEE d MMM", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(date))
}
