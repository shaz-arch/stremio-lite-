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
 * sends a notification when a new episode or season comes out. Fetching and keeping the shows, and
 * the check itself, are shared with the Linux app ([FollowedShows]).
 */
object NewEpisodes {
    private const val NOTIFY_ID = 4202
    private const val CHANNEL = "episodes"
    private const val MAX_SHOWS = 60
    private val S = FollowedShows

    private class Found(val show: Full, val ep: Video)

    /** Shows you follow (ids and names). Reading your list rows needs the network, so call this in the background. */
    fun followed(c: Context, addons: List<Addon>): List<Pair<String, String>> {
        val lists = runCatching { Releases.fromPersonalRows(addons) }.getOrDefault(emptyList())
        return Follow.shows(c, lists).take(MAX_SHOWS)
    }

    // ---- the followed shows' episode lists ([FollowedShows]) ----------------------------------

    /** What the shared code needs from this app; set at start-up (and by the background job). */
    fun install(context: Context) {
        val c = context.applicationContext
        S.host = object : FollowedShows.Host {
            override fun addons() = Home.allAddons()
            override fun followed(lists: List<Pair<String, String>>) = Follow.shows(c, lists)
            override fun continuing() = History.continueWatching(c).filter { it.type == "series" }.map { it.id to it.name }
            override fun fetchShow(id: String) = Details.loadFull("series", id)
            override fun readSaved(): String? = cacheFile(c).takeIf { it.exists() }?.readText()
            override fun writeSaved(o: org.json.JSONObject) = cacheFile(c).writeText(o.toString())
            override fun calendarFilms() = NewEpisodes.calendarFilms(c)
            override fun tmdbKey() = Settings.tmdbKey(c)
            override fun region() = Settings.watchRegion(c)
        }
    }

    /** True once the shows have been checked in the last hour. */
    fun fresh() = S.fresh()

    /** Which profile's shows are in memory (each profile has its own file). */
    private var cacheName = ""

    private fun cacheFile(c: Context) = java.io.File(c.applicationContext.filesDir, Profiles.fileName(c, "shows"))

    /** Another profile in use since last time: its own shows, not these. */
    private fun sameProfile(c: Context) {
        val name = Profiles.fileName(c, "shows")
        if (name != cacheName) { cacheName = name; S.forget() }
    }

    /** Reads the saved copy if it hasn't been yet (no network), then runs [done]: counts on Home at once. */
    fun warm(app: MainActivity, done: () -> Unit) { sameProfile(app); S.warm(done) }

    /** Fetches the followed shows (if not fresh), then runs [done]. [progress] runs as each show arrives. */
    fun load(app: MainActivity, force: Boolean = false, progress: (() -> Unit)? = null, done: () -> Unit) {
        sameProfile(app)
        S.load(force, progress, done)
    }

    /**
     * The one list of followed shows that every screen and count uses: started and chosen shows
     * (read now, so a new follow shows at once) plus list-row shows from the last check, minus stopped ones.
     */
    fun followedNow(c: Context): List<Pair<String, String>> {
        val stopped = Follow.stopped(c)
        return (Follow.shows(c) + S.lastFollowed()).distinctBy { it.first }.filter { it.first !in stopped }
    }

    /**
     * Adds shows followed since the last check (e.g. one you've just followed) without waiting
     * for the hourly refresh. [done] runs as each arrives. Returns the ids still on their way.
     */
    fun ensure(app: MainActivity, shows: List<Pair<String, String>>, done: () -> Unit): Set<String> {
        sameProfile(app)
        return S.ensure(shows, done)
    }

    /** The followed shows' details from the last check. */
    fun shows(): List<Full> = S.shows()

    fun followedTotal() = S.followedTotal()

    fun isLoading() = S.isLoading()

    /** Episodes out in the last week (null until checked). */
    fun recentCount(): Int? = S.recentCount()

    /** Episodes due in the next 7 days (null until checked). */
    fun weekCount(): Int? = S.weekCount()

    private val pretty = SimpleDateFormat("EEE d MMM", Locale.UK)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

    private fun epRow(app: MainActivity, f: Found, detail: String) = Row(
        f.show.name,
        listOf(Details.epLabel(f.ep), detail, f.ep.title).filter { it.isNotBlank() }.joinToString(" · "),
        // Opens the show's own page (follow, basket, Next watch); Play plays the episode.
        opens = { Details.screenFor(app, f.show.id, "series", f.show.name, "") ?: Details.episodeScreen(app, f.show, f.ep) },
        play = { app.push(Details.playEpisode(app, f.show, f.ep)) },
    )

    // ---- the "New episode" line at the top of Home ---------------------------------------------

    private fun seenPrefs(c: Context) = Profiles.prefsFor(c, "settings")

    /** Episodes out in the last few days, not watched and not yet seen here, newest first. */
    private fun unseen(c: Context): List<Found> {
        val seen = ReadyNotice.readSeen(seenPrefs(c).getString("epSeen", null))
        val news = isNews(c)
        return S.shows().flatMap { sh -> Releases.recent(sh).map { Found(sh, it) } }
            .filter { it.ep.id !in seen && History.progress(c, it.ep.id)?.done != true && news(it.show, it.ep) }
            .sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
    }

    /**
     * Whether a new episode goes to Ready and the Home line ([Releases.isReady]): shows in Continue
     * watching or followed for every episode get each one that's news; shows followed for seasons,
     * only a new season. Not shows already lined up in Next watch.
     */
    fun isNews(c: Context, alsoFollowed: Collection<String> = emptyList()): (Full, Video) -> Boolean =
        if (alsoFollowed.isEmpty()) Pass.memo("isNews") { isNewsNow(c, alsoFollowed) } else isNewsNow(c, alsoFollowed)

    private fun isNewsNow(c: Context, alsoFollowed: Collection<String>): (Full, Video) -> Boolean {
        val infos = History.infosById(c)
        val cont = History.continueWatching(c).mapTo(HashSet()) { it.id }
        val followed = Waiting.following(c).mapTo(HashSet()) { it.first } + alsoFollowed
        val next = NextList.ids(c)
        val rec = Follow.raw(c)
        val cache = HashMap<String, Pair<Set<String>, Long>>()
        return { sh, ep ->
            if (sh.id in next || (sh.id !in cont && sh.id !in followed)) false
            else {
                val (w, last) = cache.getOrPut(sh.id) { Waiting.watchedOf(c, sh, infos) to (infos[sh.id]?.lastWatched ?: 0L) }
                Releases.isReady(sh, ep, w, last, sh.id in cont, Following.mode(rec, sh.id), Following.since(rec, sh.id))
            }
        }
    }

    /** You've looked: the line goes until another episode comes out. */
    fun markSeen(c: Context) {
        val now = S.shows().flatMap { sh -> Releases.recent(sh).map { it.id } }
        if (now.isEmpty()) return
        val p = seenPrefs(c)
        p.edit().putString("epSeen", ReadyNotice.writeSeen(ReadyNotice.readSeen(p.getString("epSeen", null)), now)).apply()
    }

    fun homeRow(app: MainActivity): Row? {
        val new = unseen(app)
        val label = ReadyNotice.episodesLabel(new.map { it.show.name to epLabel(it.ep) }) ?: return null
        return Row(label, "►", action = {
            markSeen(app)
            app.push(if (new.size == 1) Details.screenFor(app, new[0].show.id, "series", new[0].show.name, "") ?: Ready.screen(app) else Ready.screen(app))
        })
    }

    private fun epLabel(v: Video) = Details.epLabel(v)

    /** Episodes due in the next 30 days for the shows you follow, by day. */
    /** Films for the Calendar: ones you're waiting on, lined up in Next watch, or have in the basket. */
    private fun calendarFilms(c: Context): List<FilmDates.Film> =
        (Waiting.list(c).filter { it.type == "movie" }.map { FilmDates.Film(it.id, it.name, it.year) } +
            Follow.films(c).map { FilmDates.Film(it.id, it.name, it.year) } +
            NextList.all(c).filter { it.type == "movie" }.map { FilmDates.Film(it.id, it.name, it.year) } +
            BasketList.all(c).filter { it.type == "movie" }.map { FilmDates.Film(it.id, it.name, it.year) }).distinctBy { it.id }

    fun calendar(app: MainActivity): Screen {
        val status = Row("")
        lateinit var screen: Screen
        fun build() {
            val films = S.filmEvents()
            val rows = mutableListOf(status)
            val eps = S.shows().flatMap { sh -> Releases.upcoming(sh).map { Found(sh, it) } }
            // Episodes and films together, by day.
            val days = (eps.map { it.ep.released } + films.orEmpty().map { it.day }).distinct().sorted()
            val n = eps.size + films.orEmpty().size
            status.label = when {
                S.isLoading() -> "Checking ${S.followedTotal()} shows… ${S.checkedSoFar()} done"
                n == 0 && films == null -> "Finding film dates…"
                n == 0 -> "Nothing dated in the next 30 days. Shows you follow and films you're waiting on, lining up or have in the basket show here."
                else -> "Next 30 days · ${S.followedTotal()} shows followed" + (films?.map { it.film.id }?.distinct()?.size?.takeIf { it > 0 }?.let { " · $it films" } ?: "")
            }
            val marks = History.marks(app)
            days.forEach { day ->
                rows += Row("──── ${Releases.dayName(day)} ────")
                eps.filter { it.ep.released == day }.sortedWith(compareBy<Found> { it.show.name }.thenBy { it.ep.season }.thenBy { it.ep.episode })
                    .forEach { f -> rows += epRow(app, f, if (Releases.opensSeason(f.ep)) "new season" else "") }
                films.orEmpty().filter { it.day == day }.forEach { e ->
                    rows += Row(Marks.title(marks[e.film.id], e.film.name) + if (e.film.year.isNotEmpty()) " (${e.film.year})" else "", "film · ${e.what}  ►",
                        opens = { Details.screenFor(app, e.film.id, "movie", e.film.name, e.film.year) ?: Screen(e.film.name, listOf(Row("Your add-ons are still loading."))) })
                }
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
                // Fresh each time it opens: you may have added films since.
                sameProfile(app)
                S.refreshFilms(force = true) { S.filmsTried(); if (app.isTop(screen)) build() }
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
        // The same rule as Ready: how you follow it, and only news (not the next one while you catch up).
        val mine = followed(c, addons)
        val news = isNews(c, mine.map { it.first })
        val cont = History.continueWatching(c).filter { it.type == "series" }.map { it.id to it.name }
        return S.check(addons, mine + cont, news)
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
        val text = if (alerts.size == 1) "Open Ready to watch" else alerts.joinToString(" · ") { it.text }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
        val n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(alerts.joinToString("\n") { it.text }))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(NOTIFY_ID, n) }
        Ntfy.send(c, text, title)
    }
}
