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

    /** Fetches the followed shows (if not fresh), then runs [done]. [progress] runs as each show arrives. */
    fun load(app: MainActivity, force: Boolean = false, progress: (() -> Unit)? = null, done: () -> Unit) {
        if (!force && fresh()) { done(); return }
        waiting += done
        progressHook = progress ?: progressHook
        if (loading) return
        loading = true
        checked = 0
        Net.async({ followed(app, Home.allAddons()) }) { r ->
            val shows = r.getOrDefault(emptyList())
            followedCount = shows.size
            val got = mutableListOf<Full>()
            fun finish() {
                fulls = got.toList(); fullsAt = System.currentTimeMillis(); loading = false
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
        listOf("${Details.epLabel(f.ep)}  ${f.ep.title}".trim(), detail).filter { it.isNotBlank() }.joinToString(" · "),
        opens = { Details.episodeScreen(app, f.show, f.ep) },
        play = { app.push(Details.playEpisode(app, f.show, f.ep)) },
    )

    fun screen(app: MainActivity): Screen {
        val status = Row("")
        lateinit var screen: Screen
        fun build() {
            val rows = mutableListOf(status)
            val list = fulls.flatMap { sh -> Releases.recent(sh).map { Found(sh, it) } }
                .sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
            status.label = when {
                loading -> "Checking $followedCount shows… $checked done"
                followedCount == 0 -> "Start watching a show, or on a show choose 'Tell me about new episodes and seasons', to see its new episodes here."
                list.isEmpty() -> "No new episodes in the last ${Releases.DAYS} days ($followedCount shows followed)."
                else -> "Out in the last ${Releases.DAYS} days · $followedCount shows followed · ▶ plays"
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
