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

    private var found: List<Found> = emptyList()
    private var foundAt = 0L

    /** Shows you follow (ids and names). Reading your list rows needs the network, so call this in the background. */
    fun followed(c: Context, addons: List<Addon>): List<Pair<String, String>> {
        val mine = History.library(c).filter { it.type == "series" }.map { it.id to it.name } +
            History.continueWatching(c).filter { it.type == "series" }.map { it.id to it.name }
        val lists = runCatching { Releases.fromPersonalRows(addons) }.getOrDefault(emptyList())
        return (mine + lists).distinctBy { it.first }.take(MAX_SHOWS)
    }

    fun screen(app: MainActivity): Screen {
        val status = Row("")
        lateinit var screen: Screen
        val pretty = SimpleDateFormat("EEE d MMM", Locale.UK)
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

        fun build(list: List<Found>): List<Row> {
            val rows = mutableListOf(status)
            list.forEach { f ->
                val date = runCatching { pretty.format(iso.parse(f.ep.released)!!) }.getOrDefault(f.ep.released)
                val watched = if (History.progress(app, f.ep.id)?.done == true) "✓  " else ""
                val season = if (Releases.opensSeason(f.ep)) "new season · " else ""
                rows += Row(
                    "${f.show.name}  ${Details.epLabel(f.ep)}  ${f.ep.title}".trim(), watched + season + date,
                    opens = { Details.episodeScreen(app, f.show, f.ep) },
                    play = { app.push(Details.playEpisode(app, f.show, f.ep)) },
                )
            }
            return rows
        }

        fun show(list: List<Found>, done: Boolean, checked: Int, total: Int) {
            status.label = when {
                !done -> "Checking $total shows… $checked done"
                total == 0 -> "Follow shows to see new episodes here: add them to your Library or your watchlist."
                list.isEmpty() -> "No new episodes in the last ${Releases.DAYS} days ($total shows followed)."
                else -> "Out in the last ${Releases.DAYS} days · $total shows followed · ▶ plays"
            }
            screen.rows = build(list)
            app.refresh(screen)
        }

        fun check() {
            if (System.currentTimeMillis() - foundAt < KEEP_MS) { show(found, true, 0, followedCount); return }
            status.label = "Finding the shows you follow…"
            screen.rows = build(emptyList()); app.refresh(screen)
            Net.async({ followed(app, Home.allAddons()) }) { r ->
                val shows = r.getOrDefault(emptyList())
                followedCount = shows.size
                if (shows.isEmpty()) { show(emptyList(), true, 0, 0); return@async }
                val results = mutableListOf<Found>()
                var answered = 0
                show(emptyList(), false, 0, shows.size)
                shows.forEach { (id, _) ->
                    Net.async({ Details.loadFull("series", id) }) { rr ->
                        answered++
                        rr.onSuccess { s -> Releases.recent(s).forEach { results += Found(s, it) } }
                        val sorted = results.sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
                        val done = answered >= shows.size
                        if (done) { found = sorted; foundAt = System.currentTimeMillis() }
                        show(sorted, done, answered, shows.size)
                    }
                }
            }
        }

        screen = Screen("New episodes", listOf(status))
        var started = false
        screen.onShow = {
            if (!started) {
                started = true
                // Android 13+: ask once so the 6-hourly check can tell you about new episodes.
                if (Build.VERSION.SDK_INT >= 33 && Waiting.isPhone(app) &&
                    app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) app.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 8)
                Waiting.schedule(app)
                check()
            } else { screen.rows = build(found); app.refresh(screen) }
        }
        return screen
    }

    private var followedCount = 0

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
