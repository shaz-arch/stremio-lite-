package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * The followed shows' episode lists, shared by every front-end: fetched at most once an hour and
 * used by New episodes, Ready, the Calendar and the counts on Home. Shows in Continue watching are
 * checked too, so their new episodes come to Ready. A saved copy (in the profile's own file) means a
 * fresh start doesn't refetch every show. Also the Calendar's film dates, and the background check
 * for new episodes that the phone runs.
 *
 * Each front-end supplies a [Host] at start-up, and calls [forget] when the profile changes.
 */
object FollowedShows {
    private const val KEEP_MS = 60 * 60_000L
    private const val MAX_SHOWS = 60
    /** Where the background check keeps how far each show had got (one value per show id). */
    private const val CHECK_STORE = "episodes"

    interface Host {
        fun addons(): List<Addon>
        /** Shows you follow, with the shows in your list rows [lists] (blocking: runs in the background). */
        fun followed(lists: List<Pair<String, String>>): List<Pair<String, String>>
        /** Shows in Continue watching (id, name). */
        fun continuing(): List<Pair<String, String>>
        /** One show's details and episodes (blocking). */
        fun fetchShow(id: String): Full
        /** The saved copy's text, or null (blocking). */
        fun readSaved(): String?
        /** Saves the copy (blocking: runs in the background). */
        fun writeSaved(o: JSONObject)
        /** Films for the Calendar: ones you're waiting on, following, lining up or have in the basket. */
        fun calendarFilms(): List<FilmDates.Film>
        fun tmdbKey(): String
        fun region(): String
        /** Shows you've stopped following (Following's list), left out at once, not at the next hourly check. */
        fun stopped(): Set<String> = emptySet()
    }

    lateinit var host: Host

    private var shows: List<Full> = emptyList()
    private var at = 0L
    private var followedCount = 0
    private var loading = false
    private var checked = 0
    private val waiting = mutableListOf<() -> Unit>()
    private var progress: (() -> Unit)? = null
    /** Every followed show from the last full check, including your list rows (which need the network to read). */
    @Volatile private var lastFollowed: List<Pair<String, String>> = emptyList()
    private var savedRead = false
    private val fetching = HashSet<String>()
    private val failed = HashSet<String>()
    /** Goes up when the profile changes, so answers for the one before are dropped. */
    private var generation = 0

    /** Drops everything in memory (another profile was chosen): the next use reads its saved copy. */
    fun forget() {
        generation++
        shows = emptyList(); at = 0; followedCount = 0; checked = 0; loading = false
        lastFollowed = emptyList(); savedRead = false
        fetching.clear(); failed.clear()
        filmEvents = null; filmsAt = 0; filmsLoading = false
    }

    // ---- reading -----------------------------------------------------------------------------

    /** The followed shows' details from the last check, minus any you've stopped following since. */
    fun shows(): List<Full> {
        val stopped = runCatching { host.stopped() }.getOrDefault(emptySet())
        return if (stopped.isEmpty()) shows else shows.filter { it.id !in stopped }
    }

    /** Every followed show from the last full check, including list-row shows. */
    fun lastFollowed(): List<Pair<String, String>> = lastFollowed

    fun followedTotal() = followedCount
    fun checkedSoFar() = checked
    fun isLoading() = loading

    /** True once the shows have been checked in the last hour. */
    fun fresh() = System.currentTimeMillis() - at < KEEP_MS

    /** Episodes out in the last week (null until checked). */
    fun recentCount(): Int? = if (at == 0L) null else shows().sumOf { Releases.recent(it).size }

    /** Episodes and film dates in the next 7 days (null until checked). */
    fun weekCount(): Int? = if (at == 0L) null else shows().sumOf { Releases.upcoming(it, 7).size } +
        filmEvents().orEmpty().count { it.day <= Releases.day(-7) }

    // ---- the saved copy ----------------------------------------------------------------------

    private class Saved(val shows: List<Full>, val at: Long, val followed: List<Pair<String, String>>)

    private fun parse(text: String?): Saved? = runCatching {
        val o = JSONObject(text ?: return null)
        val arr = o.optJSONArray("shows") ?: return null
        val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { MetaParse.full(it, it.optString("source")) } }
        val fol = o.optJSONArray("followed")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } } } ?: emptyList()
        Saved(list, o.optLong("at"), fol)
    }.getOrNull()

    private fun take(s: Saved?) {
        if (s == null || shows.isNotEmpty()) return
        shows = s.shows; at = s.at; lastFollowed = s.followed; followedCount = s.followed.size
    }

    /** Reads the saved copy now (blocking), if it hasn't been yet. */
    fun readSavedNow() {
        if (savedRead) return
        savedRead = true
        take(parse(runCatching { host.readSaved() }.getOrNull()))
    }

    /** Reads the saved copy in the background if it hasn't been yet (no network), then runs [done]. */
    fun warm(done: () -> Unit) {
        if (savedRead) { done(); return }
        savedRead = true
        val g = generation
        Platform.async.background({ parse(host.readSaved()) }) { r ->
            if (g == generation) take(r.getOrNull())
            done()
        }
    }

    private fun save() {
        val list = shows; val time = at; val fol = lastFollowed
        Platform.async.background({
            val arr = JSONArray(); list.forEach { arr.put(MetaParse.compact(it)) }
            val fa = JSONArray(); fol.forEach { fa.put(JSONArray().put(it.first).put(it.second)) }
            host.writeSaved(JSONObject().put("at", time).put("shows", arr).put("followed", fa))
        }) { }
    }

    // ---- fetching ----------------------------------------------------------------------------

    /** Fetches the followed shows (unless checked in the last hour, or [force]), then runs [done]; [onProgress] as each arrives. */
    fun load(force: Boolean = false, onProgress: (() -> Unit)? = null, done: () -> Unit) {
        if (!savedRead) { warm { load(force, onProgress, done) }; return }
        if (!force && fresh()) { done(); return }
        waiting += done
        if (onProgress != null) progress = onProgress
        if (loading) return
        loading = true
        checked = 0
        val g = generation
        val addons = host.addons()
        val cont = host.continuing()
        Platform.async.background({
            val lists = runCatching { Releases.fromPersonalRows(addons) }.getOrDefault(emptyList())
            host.followed(lists).take(MAX_SHOWS)
        }) { r ->
            if (g != generation) return@background
            val mine = r.getOrDefault(emptyList())
            if (r.isSuccess) lastFollowed = mine
            followedCount = mine.size
            val want = (mine + cont).distinctBy { it.first }
            val got = mutableListOf<Full>()
            fun finish() {
                shows = got.toList(); at = System.currentTimeMillis(); loading = false; failed.clear()
                save()
                refreshFilms()
                val l = waiting.toList(); waiting.clear(); l.forEach { it() }
            }
            if (want.isEmpty()) { finish(); return@background }
            want.forEach { (id, _) ->
                Platform.async.background({ host.fetchShow(id) }) { rr ->
                    if (g != generation) return@background
                    checked++
                    rr.getOrNull()?.let { got += it }
                    progress?.invoke()
                    if (checked >= want.size) finish()
                }
            }
        }
    }

    /**
     * Adds shows followed since the last check (e.g. one you've just followed) without waiting for
     * the hourly refresh. [done] runs as each arrives. Returns the ids still on their way.
     */
    fun ensure(want: List<Pair<String, String>>, done: () -> Unit): Set<String> {
        val have = shows.map { it.id }.toSet()
        val g = generation
        want.filter { it.first !in have && it.first !in fetching && it.first !in failed }.forEach { (id, _) ->
            fetching += id
            Platform.async.background({ host.fetchShow(id) }) { r ->
                if (g != generation) return@background
                fetching -= id
                val f = r.getOrNull()
                if (f == null) failed += id else if (shows.none { it.id == f.id }) { shows = shows + f; save() }
                done()
            }
        }
        return fetching.toSet()
    }

    // ---- film dates for the Calendar, fetched at most once an hour --------------------------------

    private var filmEvents: List<FilmDates.Event>? = null
    private var filmsAt = 0L
    private var filmsLoading = false

    /** The Calendar's film dates (null until fetched), minus films no longer waited on, followed or lined up. */
    fun filmEvents(): List<FilmDates.Event>? {
        val all = filmEvents ?: return null
        val now = runCatching { host.calendarFilms().map { it.id }.toSet() }.getOrNull() ?: return all
        return all.filter { it.film.id in now }
    }

    /** The Calendar has asked once: no dates now means none, not "still finding". */
    fun filmsTried() { if (filmEvents == null) filmEvents = emptyList() }

    /** Fetches the film dates (unless done in the last hour, or [force]), then runs [done]. */
    fun refreshFilms(force: Boolean = false, done: () -> Unit = {}) {
        if (filmsLoading || (!force && System.currentTimeMillis() - filmsAt < KEEP_MS)) { done(); return }
        filmsLoading = true
        val g = generation
        val want = host.calendarFilms(); val addons = host.addons(); val key = host.tmdbKey(); val region = host.region()
        Platform.async.background({ FilmDates.events(addons, key, region, want) }) { r ->
            if (g != generation) return@background
            filmsLoading = false
            r.getOrNull()?.let { filmEvents = it; filmsAt = System.currentTimeMillis() }
            done()
        }
    }

    // ---- the background check -----------------------------------------------------------------

    /**
     * Blocking: looks at each of [shows] (followed and in Continue watching) and returns what's new
     * since the last check, by [news] (the same rule as Ready). The first look at a show only
     * remembers where it's up to. An episode is only told once it really plays, or after a couple of days.
     */
    fun check(addons: List<Addon>, shows: List<Pair<String, String>>, news: (Full, Video) -> Boolean): List<Releases.Alert> {
        val alerts = mutableListOf<Releases.Alert>()
        val marks = LinkedHashMap<String, String?>()
        for ((id, _) in shows.distinctBy { it.first }) {
            val show = runCatching { MetaParse.fetch(addons, "series", id) }.getOrNull() ?: continue
            val seen = Platform.storage.get(CHECK_STORE, id)
            if (seen != null && Releases.finished(show)) continue
            val (found, mark) = Releases.check(show, seen)
            val alert = found?.let { a -> a.episodes.filter { news(show, it) }.takeIf { it.isNotEmpty() }?.let { Releases.Alert(show, it) } }
            if (alert == null) { if (mark != null) marks[id] = mark; continue }
            val newest = alert.episodes.last()
            val plays = runCatching { StreamParse.availableNow(addons, "series", newest.id) }.getOrDefault(false)
            when {
                plays -> alert.note = "ready to watch"
                Releases.daysSince(newest.released) >= Releases.WAIT_FOR_STREAM_DAYS -> alert.note = "no stream yet"
                else -> continue // not playable yet: the marker stays, so it's checked again next time
            }
            if (mark != null) marks[id] = mark
            alerts += alert
        }
        if (marks.isNotEmpty()) Platform.storage.edit(CHECK_STORE, marks)
        return alerts
    }
}
