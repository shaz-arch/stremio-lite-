package app.stremiolite

import java.util.concurrent.ConcurrentHashMap

/**
 * Where each Next watch title stands: ready to play, coming (with its date), or out but with no
 * streams yet. Worked out one title at a time from its details and a quick stream check, and
 * remembered for 6 hours.
 */
object NextStatus {
    enum class Group(val heading: String) { READY("Ready now"), SOON("Coming soon"), WAITING("Waiting") }

    /** [key] orders titles inside a group (Coming soon: the date, soonest first). */
    class Status(val group: Group, val detail: String, val key: Long = 0) {
        val shape get() = if (group == Group.READY) Marks.READY else Marks.WAITING
    }

    private const val KEEP_MS = 6 * 3600_000L
    private val known = ConcurrentHashMap<String, Pair<Status, Long>>()

    fun of(id: String, now: Long = System.currentTimeMillis()): Status? =
        known[id]?.takeIf { now - it.second < KEEP_MS }?.first

    fun forget(id: String) { known.remove(id) }

    /** Titles still to check. */
    fun missing(list: List<NextWatch.Entry>) = list.filter { of(it.id) == null }

    private fun metaFirst(addons: List<Addon>) = addons.filter { !it.isLocal }.sortedBy { if (AioRows.isAio(it)) 0 else 1 }

    private fun playable(addons: List<Addon>, type: String, id: String): Int {
        val streamers = addons.filter { !it.isLocal && it.supports("stream", type, id) && Health.isStreamProvider(it) }
        if (streamers.isEmpty()) return 0
        return StreamParse.fetchAll(streamers, type, id).count { Best.score(it, 120.0) != null }
    }

    /** Works out [e]'s status (blocking) and remembers it. */
    fun check(addons: List<Addon>, e: NextWatch.Entry, now: Long = System.currentTimeMillis(), tmdbKey: String = "", region: String = "GB"): Status {
        val today = ComingSoon.today(now)
        val full = runCatching { MetaParse.fetch(metaFirst(addons), e.type, e.id) }.getOrNull()
        val s = if (e.type == "series") show(addons, full, today) else film(addons, e, full, today, tmdbKey, region)
        known[e.id] = s to now
        return s
    }

    private fun film(addons: List<Addon>, e: NextWatch.Entry, full: Full?, today: Long, tmdbKey: String, region: String): Status {
        val day = ComingSoon.day(full?.releasedDay.orEmpty())
        // Not out to stream yet (only in cinemas, digital later): anything found now is a fake or a cam.
        if (tmdbKey.isNotBlank()) runCatching { WhereToWatch.load(tmdbKey, "movie", e.id, "", region) }.getOrNull()?.let { w ->
            if (w.notOutYet(today)) return Status(Group.SOON, w.notOutLabel(today), w.digitalFrom.takeIf { it > 0 } ?: Long.MAX_VALUE - 1)
        }
        if (day > today) return Status(Group.SOON, "out " + ComingSoon.label(day), day)
        val n = playable(addons, "movie", e.id)
        if (n > 0) return Status(Group.READY, "ready")
        // No streams and no date: a film announced for later.
        val year = (full?.year ?: e.year).take(4).toIntOrNull()
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        if (day == 0L && year != null && year > thisYear) return Status(Group.SOON, "date not set", Long.MAX_VALUE)
        return Status(Group.WAITING, if (day > 0 && today - day < 120 * 86_400_000L) "no streams yet · out " + ComingSoon.label(day) else "no streams yet")
    }

    private fun show(addons: List<Addon>, full: Full?, today: Long): Status {
        // Not started (or it would be in Continue watching): the first episode is the one that matters.
        val first = full?.videos?.filter { it.season >= 1 }?.minWithOrNull(compareBy({ it.season }, { it.episode }))
            ?: return Status(Group.SOON, "date not set", Long.MAX_VALUE)
        val ep = "S%dE%d".format(first.season, first.episode)
        val day = ComingSoon.day(first.released)
        if (day > today) return Status(Group.SOON, "starts " + ComingSoon.label(day), day)
        return if (playable(addons, "series", first.id) > 0) Status(Group.READY, "$ep ready")
        else Status(Group.WAITING, "$ep: no streams yet")
    }

    /**
     * What Waiting for streams says comes first: you asked to wait (no good copy yet, or for a
     * better one) unless it has since found one. [waiting] gives (better?, ready time) for titles there.
     */
    fun effective(id: String, waiting: (String) -> Pair<Boolean, Long>?): Status? {
        val s = of(id)
        val w = waiting(id) ?: return s
        val (better, ready) = w
        if (ready > 0) return Status(Group.READY, if (better) "better version found" else "ready")
        if (s?.group == Group.SOON) return s
        return Status(Group.WAITING, if (better) "waiting for a better version" else "no streams yet")
    }

    /** [list] grouped by status (unchecked ones last, as "checking…"), your order kept inside each group. */
    fun grouped(list: List<NextWatch.Entry>, status: (String) -> Status? = { of(it) }): List<Pair<Group?, List<NextWatch.Entry>>> {
        val by = list.groupBy { status(it.id)?.group }
        return Group.values().mapNotNull { g ->
            by[g]?.let { items -> g to (if (g == Group.SOON) items.sortedBy { status(it.id)?.key ?: Long.MAX_VALUE } else items) }
        } + listOfNotNull(by[null]?.let { null to it })
    }
}
