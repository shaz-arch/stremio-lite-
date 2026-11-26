package app.stremiolite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Episodes out in the last 7 days for the shows you follow: your Library, Continue watching and
 * your own list rows (e.g. the MDBList Watchlist). On phones, the 6-hourly background check also
 * sends a notification when a new episode or season comes out.
 */
object NewEpisodes {
    private const val KEEP_MS = 60 * 60_000L
    private const val NOTIFY_ID = 4202
    private const val CHANNEL = "episodes"
    private const val MAX_SHOWS = 60

    private class Found(val show: Full, val ep: Video)

    /** Shows you follow (ids and names). Reading your list rows needs the network, so call this in the background. */
    fun followed(c: Context, addons: List<Addon>): List<Pair<String, String>> {
        val lists = runCatching { Releases.fromPersonalRows(addons) }.getOrDefault(emptyList())
        return Follow.shows(c, lists).take(MAX_SHOWS)
    }

    // ---- the followed shows' episode lists, fetched once an hour and shared by
    //      New episodes, the Calendar and the counts on Home ---------------------------------

    private var fulls: List<Full> = emptyList()
    private var fullsAt = 0L
    private var followedCount = 0
    private var loading = false
    private var checked = 0
    private val waiting = mutableListOf<() -> Unit>()

    /** True once the shows have been checked in the last hour. */
    fun fresh() = System.currentTimeMillis() - fullsAt < KEEP_MS

    // ---- kept on the device, so a fresh start doesn't refetch every show -----------------------

    /** Which profile's saved copy is in memory (each profile has its own file). */
    private var cacheName = ""
    private var cacheRead = false

    private fun cacheFile(c: Context) = java.io.File(c.applicationContext.filesDir, Profiles.fileName(c, "shows"))

    private fun readCache(c: Context): Triple<List<Full>, Long, List<Pair<String, String>>>? = runCatching {
        val o = org.json.JSONObject(cacheFile(c).readText())
        val arr = o.optJSONArray("shows") ?: return null
        val shows = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { MetaParse.full(it, it.optString("source")) } }
        val fol = o.optJSONArray("followed")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } } } ?: emptyList()
        Triple(shows, o.optLong("at"), fol)
    }.getOrNull()

    private fun writeCache(c: Context) {
        val shows = fulls; val at = fullsAt; val fol = lastFollowed; val f = cacheFile(c)
        Net.async({
            val arr = org.json.JSONArray(); shows.forEach { arr.put(MetaParse.compact(it)) }
            val fa = org.json.JSONArray(); fol.forEach { fa.put(org.json.JSONArray().put(it.first).put(it.second)) }
            f.writeText(org.json.JSONObject().put("at", at).put("shows", arr).put("followed", fa).toString())
        }) { }
    }

    /** Reads the saved copy if it hasn't been yet (no network), then runs [done]: counts on Home at once. */
    fun warm(app: MainActivity, done: () -> Unit) {
        val name = Profiles.fileName(app, "shows")
        if (cacheRead && name == cacheName) { done(); return }
        if (name != cacheName) { cacheName = name; fulls = emptyList(); fullsAt = 0; lastFollowed = emptyList() }
        cacheRead = true
        Net.async({ readCache(app) }) { r ->
            r.getOrNull()?.let { (shows, at, fol) -> if (fulls.isEmpty()) { fulls = shows; fullsAt = at; lastFollowed = fol; followedCount = fol.size } }
            done()
        }
    }

    /** Fetches the followed shows (if not fresh), then runs [done]. [progress] runs as each show arrives. */
    fun load(app: MainActivity, force: Boolean = false, progress: (() -> Unit)? = null, done: () -> Unit) {
        val name = Profiles.fileName(app, "shows")
        if (name != cacheName) {
            cacheName = name; cacheRead = false
            fulls = emptyList(); fullsAt = 0; lastFollowed = emptyList()
        }
        // First time: read the saved copy (in the background), then carry on as if it had just been fetched.
        if (!cacheRead) {
            cacheRead = true
            Net.async({ readCache(app) }) { r ->
                r.getOrNull()?.let { (shows, at, fol) -> if (fulls.isEmpty()) { fulls = shows; fullsAt = at; lastFollowed = fol; followedCount = fol.size } }
                load(app, force, progress, done)
            }
            return
        }
        if (!force && fresh()) { done(); return }
        waiting += done
        progressHook = progress ?: progressHook
        if (loading) return
        loading = true
        checked = 0
        Net.async({ followed(app, Home.allAddons()) }) { r ->
            val shows = r.getOrDefault(emptyList())
            if (r.isSuccess) lastFollowed = shows
            followedCount = shows.size
            val got = mutableListOf<Full>()
            fun finish() {
                fulls = got.toList(); fullsAt = System.currentTimeMillis(); loading = false; failed.clear()
                writeCache(app)
                val list = waiting.toList(); waiting.clear(); list.forEach { it() }
            }
            if (shows.isEmpty()) { finish(); return@async }
            shows.forEach { (id, _) ->
                Net.async({ Details.loadFull("series", id) }) { rr ->
                    checked++
                    rr.getOrNull()?.let { got += it }
                    progressHook?.invoke()
                    if (checked >= shows.size) finish()
                }
            }
        }
    }

    private var progressHook: (() -> Unit)? = null

    /** Every followed show from the last full check, including your list rows (which need the network to read). */
    @Volatile private var lastFollowed: List<Pair<String, String>> = emptyList()

    /**
     * The one list of followed shows that every screen and count uses: started and chosen shows
     * (read now, so a new follow shows at once) plus list-row shows from the last check, minus stopped ones.
     */
    fun followedNow(c: Context): List<Pair<String, String>> {
        val stopped = Follow.stopped(c)
        return (Follow.shows(c) + lastFollowed).distinctBy { it.first }.filter { it.first !in stopped }
    }

    private val fetching = HashSet<String>()
    private val failed = HashSet<String>()

    /**
     * Adds shows followed since the last check (e.g. one you've just followed) without waiting
     * for the hourly refresh. [done] runs as each arrives. Returns the ids still on their way.
     */
    fun ensure(app: MainActivity, shows: List<Pair<String, String>>, done: () -> Unit): Set<String> {
        val have = fulls.map { it.id }.toSet()
        shows.filter { it.first !in have && it.first !in fetching && it.first !in failed }.forEach { (id, _) ->
            fetching += id
            Net.async({ Details.loadFull("series", id) }) { r ->
                fetching -= id
                val f = r.getOrNull()
                if (f == null) failed += id else if (fulls.none { it.id == f.id }) { fulls = fulls + f; writeCache(app) }
                done()
            }
        }
        return fetching.toSet()
    }

    /** The followed shows' details from the last check. */
    fun shows(): List<Full> = fulls

    fun followedTotal() = followedCount

    fun isLoading() = loading

    /** Episodes out in the last week (null until checked). */
    fun recentCount(): Int? = if (fullsAt == 0L) null else fulls.sumOf { Releases.recent(it).size }

    /** Episodes due in the next 7 days (null until checked). */
    fun weekCount(): Int? = if (fullsAt == 0L) null else fulls.sumOf { Releases.upcoming(it, 7).size }

    private val pretty = SimpleDateFormat("EEE d MMM", Locale.UK)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

    private fun epRow(app: MainActivity, f: Found, detail: String) = Row(
        f.show.name,
        listOf(Details.epLabel(f.ep), detail, f.ep.title).filter { it.isNotBlank() }.joinToString(" · "),
        opens = { Details.episodeScreen(app, f.show, f.ep) },
        play = { app.push(Details.playEpisode(app, f.show, f.ep)) },
    )

    // ---- the "New episode" line at the top of Home ---------------------------------------------

    private fun seenPrefs(c: Context) = Profiles.prefsFor(c, "settings")

    /** Episodes out in the last few days, not watched and not yet seen here, newest first. */
    private fun unseen(c: Context): List<Found> {
        val seen = ReadyNotice.readSeen(seenPrefs(c).getString("epSeen", null))
        val news = isNews(c)
        return fulls.flatMap { sh -> Releases.recent(sh).map { Found(sh, it) } }
            .filter { it.ep.id !in seen && History.progress(c, it.ep.id)?.done != true && news(it.show, it.ep) }
            .sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
    }

    /** Whether an episode is news to you ([Releases.isNews]): not just the next one while you catch up. */
    fun isNews(c: Context): (Full, Video) -> Boolean {
        val infos = History.infos(c).associateBy { it.id }
        val cache = HashMap<String, Pair<Set<String>, Long>>()
        return { sh, ep ->
            val (w, last) = cache.getOrPut(sh.id) { Waiting.watchedOf(c, sh, infos) to (infos[sh.id]?.lastWatched ?: 0L) }
            Releases.isNews(sh, ep, w, last)
        }
    }

    /** You've looked: the line goes until another episode comes out. */
    fun markSeen(c: Context) {
        val now = fulls.flatMap { sh -> Releases.recent(sh).map { it.id } }
        if (now.isEmpty()) return
        val p = seenPrefs(c)
        p.edit().putString("epSeen", ReadyNotice.writeSeen(ReadyNotice.readSeen(p.getString("epSeen", null)), now)).apply()
    }

    fun homeRow(app: MainActivity): Row? {
        val new = unseen(app)
        val label = ReadyNotice.episodesLabel(new.map { it.show.name to epLabel(it.ep) }) ?: return null
        return Row(label, "►", action = {
            markSeen(app)
            app.push(if (new.size == 1) Details.episodeScreen(app, new[0].show, new[0].ep) else screen(app))
        })
    }

    private fun epLabel(v: Video) = Details.epLabel(v)

    fun screen(app: MainActivity): Screen {
        val status = Row("")
        lateinit var screen: Screen
        fun build() {
            markSeen(app)   // looking at the list clears the line on Home
            val rows = mutableListOf(status)
            val list = fulls.flatMap { sh -> Releases.recent(sh).map { Found(sh, it) } }
                .sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
            status.label = when {
                loading -> "Checking $followedCount shows… $checked done"
                followedCount == 0 -> "Start watching a show, or on a show choose 'Tell me about new episodes and seasons', to see its new episodes here."
                list.isEmpty() -> "No new episodes in the last ${Releases.DAYS} days ($followedCount shows followed)."
                else -> "Out in the last ${Releases.DAYS} days · $followedCount shows followed · ► plays"
            }
            list.forEach { f ->
                val date = runCatching { pretty.format(iso.parse(f.ep.released)!!) }.getOrDefault(f.ep.released)
                val watched = if (History.progress(app, f.ep.id)?.done == true) "✓  " else ""
                val season = if (Releases.opensSeason(f.ep)) "new season · " else ""
                rows += epRow(app, f, watched + season + date)
            }
            rows += Row("────────────────────")
            rows += Row("Calendar: coming up", "►", opens = { calendar(app) })
            screen.rows = rows
            app.refresh(screen)
        }
        screen = Screen("New episodes", listOf(status))
        var started = false
        screen.onShow = {
            if (!started) {
                started = true
                Waiting.askToNotify(app)
                status.label = "Finding the shows you follow…"
                load(app, progress = { if (app.isTop(screen)) build() }) { build() }
            }
            build()
        }
        return screen
    }

    /** Episodes due in the next 30 days for the shows you follow, by day. */
    fun calendar(app: MainActivity): Screen {
        val status = Row("")
        lateinit var screen: Screen
        fun build() {
            val rows = mutableListOf(status)
            val list = fulls.flatMap { sh -> Releases.upcoming(sh).map { Found(sh, it) } }
                .sortedWith(compareBy<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.season }.thenBy { it.ep.episode })
            status.label = when {
                loading -> "Checking $followedCount shows… $checked done"
                followedCount == 0 -> "Start watching a show, or on a show choose 'Tell me about new episodes and seasons', to see what's coming."
                list.isEmpty() -> "Nothing dated in the next 30 days ($followedCount shows followed)."
                else -> "Next 30 days · $followedCount shows followed"
            }
            var lastDay = ""
            list.forEach { f ->
                if (f.ep.released != lastDay) {
                    lastDay = f.ep.released
                    rows += Row("──── ${Releases.dayName(lastDay)} ────")
                }
                rows += epRow(app, f, if (Releases.opensSeason(f.ep)) "new season" else "")
            }
            screen.rows = rows
            app.refresh(screen)
        }
        screen = Screen("Calendar", listOf(status))
        var started = false
        screen.onShow = {
            if (!started) {
                started = true
                status.label = "Finding the shows you follow…"
                load(app, progress = { if (app.isTop(screen)) build() }) { build() }
            }
            build()
        }
        return screen
    }

    // ---- background check (phones) ---------------------------------------------------------

    /**
     * Blocking: looks at every followed show and returns what's new since the last check.
     * The first look at a show only remembers where it's up to.
     */
    fun checkNow(c: Context): List<Releases.Alert> {
        if (Account.authKey(c) == null) return emptyList()
        val addons = runCatching { Stremio.parseAddons(Account.addonCache(c).readText()) }.getOrNull() ?: return emptyList()
        val prefs = Profiles.prefsFor(c, "episodes")
        val alerts = mutableListOf<Releases.Alert>()
        val e = prefs.edit()
        for ((id, _) in followed(c, addons)) {
            val show = runCatching { MetaParse.fetch(addons, "series", id) }.getOrNull() ?: continue
            val seen = prefs.getString(id, null)
            if (seen != null && Releases.finished(show)) continue
            val (alert, mark) = Releases.check(show, seen)
            if (alert == null) { if (mark != null) e.putString(id, mark); continue }
            // Only tell once it really plays; after a couple of days, tell anyway.
            val newest = alert.episodes.last()
            val plays = runCatching { StreamParse.availableNow(addons, "series", newest.id) }.getOrDefault(false)
            when {
                plays -> alert.note = "ready to watch"
                Releases.daysSince(newest.released) >= Releases.WAIT_FOR_STREAM_DAYS -> alert.note = "no stream yet"
                else -> continue // not playable yet: the marker stays, so it's checked again next time
            }
            if (mark != null) e.putString(id, mark)
            alerts += alert
        }
        e.apply()
        return alerts
    }

    fun notify(c: Context, alerts: List<Releases.Alert>) {
        if (alerts.isEmpty() || !Waiting.isPhone(c)) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes and seasons", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val title = if (alerts.size == 1) alerts[0].text else "New episodes for ${alerts.size} shows"
        val text = if (alerts.size == 1) "Open New episodes to watch" else alerts.joinToString(" · ") { it.text }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
        val n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(alerts.joinToString("\n") { it.text }))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }
}
