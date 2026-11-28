package app.stremiolite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import app.stremiolite.WaitingList.Entry

/**
 * "Waiting": films that are out but have no good stream yet, or that you want a better version of.
 * Films not out to stream yet, and shows, are in Following (screen below, list in [Follow]).
 *
 * The list, and checking it, are shared with the Linux app ([WaitingList]). The phone checks every
 * few hours in the background and sends a notification; either device also checks when the app is
 * opened, if a check is due.
 */
object Waiting {
    private const val ID = WaitingList.ID
    private const val EVERY_MS = 6 * 60 * 60_000L
    private const val JOB_ID = 4201
    private const val CHANNEL = "waiting"

    fun list(c: Context): List<Entry> = Pass.memo("waiting") { WaitingList.parse(History.rawRemote(c, ID)) }

    /** The list without films that are done with ([WaitRules]); saved back if any left. */
    fun current(c: Context): List<Entry> {
        val all = list(c)
        val marks = History.marks(c)
        val keep = all.filter { e ->
            val m = marks[e.id]
            !WaitRules.done(e.better, e.ready, m == Marks.CONTINUE, m == Marks.WATCHED, if (e.better) History.lastPlayed(c, e.id) else 0)
        }
        if (keep.size != all.size) save(c, keep)
        return keep
    }

    fun has(c: Context, id: String) = list(c).any { it.id == id && !it.better }

    fun watchingForBetter(c: Context, id: String) = list(c).any { it.id == id && it.better }

    /**
     * "Tell me when a better version appears": notes what Play best would pick now (so the check
     * knows what to beat), then adds the title to the list.
     */
    fun addBetter(app: MainActivity, id: String, type: String, name: String, year: String, done: (String) -> Unit) {
        val addons = Home.allAddons()
        Net.async({ WaitingList.better(addons, id, type, name, year) }) { r ->
            val e = r.getOrNull() ?: return@async
            save(app, WaitingList.with(list(app), e))
            askToNotify(app)
            done(e.have)
        }
    }

    private fun save(c: Context, list: List<Entry>) = History.putRemote(c, WaitingList.record(History.rawRemote(c, ID), list))

    fun add(app: MainActivity, e: Entry) {
        save(app, WaitingList.with(list(app), e))
        askToNotify(app)
        checkSoon(app, force = true)
    }

    /** Android 13+: notifications need the user's OK (phones only; the stick shows things on Home). */
    fun askToNotify(app: MainActivity) {
        if (Build.VERSION.SDK_INT >= 33 && isPhone(app) &&
            app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) app.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        schedule(app)
    }

    /** Shows you follow: the same list New episodes and the Calendar use. */
    fun following(c: Context) = NewEpisodes.followedNow(c)

    /** Episodes of [show] you've watched, from the account plus this device. */
    fun watchedOf(c: Context, show: Full, infos: Map<String, LibraryItems.Info>): Set<String> = Pass.memo("watched:" + show.id) {
        val order = MetaParse.stremioOrder(show)
        (infos[show.id]?.let { Sync.decodeWatched(it.watchedField, order) } ?: emptySet()) +
            show.videos.filter { History.progress(c, it.id)?.done == true }.map { it.id }
    }

    fun remove(c: Context, id: String) = save(c, list(c).filter { it.id != id })

    fun isPhone(c: Context) = c.packageManager.hasSystemFeature("android.hardware.touchscreen")

    // ---- checking ---------------------------------------------------------------------------

    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")

    fun lastCheck(c: Context) = prefs(c).getLong("waitingChecked", 0)

    /** Films that became ready since you last looked (newest first), for the line on Home. */
    fun unseen(c: Context): List<Entry> {
        val seen = prefs(c).getLong("readySeen", 0)
        return list(c).filter { it.ready > seen }.sortedByDescending { it.ready }
    }

    /** You've looked: the line on Home goes until something else becomes ready. */
    fun markSeen(c: Context) { prefs(c).edit().putLong("readySeen", System.currentTimeMillis()).apply() }

    /** The line for the top of Home, or null when there's nothing new. */
    fun homeRow(app: MainActivity): Row? {
        current(app)
        val new = unseen(app)
        val label = ReadyNotice.label(new.map { it.name }) ?: return null
        return Row(label, "►", action = {
            markSeen(app)
            app.push(if (new.size == 1) Details.screenFor(app, new[0].id, new[0].type, new[0].name, new[0].year) ?: Ready.screen(app) else Ready.screen(app))
        })
    }

    /**
     * The check itself (blocking): reads the list from the account, asks the stream add-ons about
     * each film still waiting, marks the ones now available, and returns those.
     */
    fun checkNow(c: Context): List<Entry> {
        val key = Account.authKey(c) ?: return emptyList()
        prefs(c).edit().putLong("waitingChecked", System.currentTimeMillis()).apply()
        val addons = runCatching { Stremio.parseAddons(Account.addonCache(c).readText()) }.getOrNull() ?: return emptyList()
        return WaitingList.check(key, addons, Settings.tmdbKey(c), Settings.watchRegion(c))
    }

    /**
     * Play best found nothing that plays for a Next watch film: it goes (back) into Waiting, not
     * ready, so Next watch stops calling it ready until a stream that plays turns up.
     */
    fun unplayable(app: MainActivity, id: String, name: String) {
        save(app, WaitingList.unplayable(list(app), id, name))
    }

    /** In the app: re-checks the followed films now (when Following opens), then [done]. */
    fun checkFollowedFilms(app: MainActivity, done: () -> Unit) {
        if (Follow.films(app).isEmpty()) return
        val fol = Follow.raw(app); val wait = list(app); val addons = Home.allAddons()
        val tmdb = Settings.tmdbKey(app); val region = Settings.watchRegion(app)
        Net.async({ WaitingList.outCheck(addons, tmdb, region, fol, wait, System.currentTimeMillis()) }) { r ->
            val (rec, list) = r.getOrNull() ?: return@async
            if (rec == null && list == null) return@async
            rec?.let { History.putRemote(app, it) }
            list?.let { save(app, it); checkSoon(app, force = true) }
            done()
        }
    }

    /** In the app: checks if one is due (or [force]), then refreshes the list and calls [changed]. */
    fun checkSoon(app: MainActivity, force: Boolean = false, changed: (() -> Unit)? = null) {
        if (Account.authKey(app) == null) return
        if (!force && System.currentTimeMillis() - lastCheck(app) < EVERY_MS) return
        if (!force && list(app).none { it.ready == 0L }) return
        Net.async({ checkNow(app) }) { r ->
            val ready = r.getOrNull().orEmpty()
            if (ready.isNotEmpty()) {
                notify(app, ready)
                History.pull(app, force = true) { changed?.invoke() }
            }
        }
    }

    // ---- phone: background check and notification ------------------------------------------

    /** Phones only: a check every 6 hours while the phone has a connection, kept after a restart. */
    fun schedule(c: Context) {
        if (!isPhone(c)) return
        val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (js.allPendingJobs.any { it.id == JOB_ID }) return
        runCatching {
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, Job::class.java))
                    .setPeriodic(EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    fun notify(c: Context, ready: List<Entry>) {
        if (ready.isEmpty() || !isPhone(c)) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streams now available", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val title = when {
            ready.size == 1 && ready[0].better -> "Better version: ${ready[0].name}"
            ready.size == 1 -> "Now available: ${ready[0].name}"
            else -> "${ready.size} films ready to watch"
        }
        val text = ready.joinToString(", ") { if (it.better) "${it.name} (${it.found})" else it.name }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
        val n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(JOB_ID, n) }
        Ntfy.send(c, text, title)
    }

    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            // The app may not be open: the shared code still needs its storage and threads.
            AndroidPlatform.install(applicationContext)
            Thread {
                val ready = runCatching { checkNow(applicationContext) }.getOrDefault(emptyList())
                notify(applicationContext, ready)
                // New episodes and seasons for followed shows (Library, Continue watching, your watchlist).
                val alerts = runCatching { NewEpisodes.checkNow(applicationContext) }.getOrDefault(emptyList())
                NewEpisodes.notify(applicationContext, alerts)
                jobFinished(params, false)
            }.start()
            return true
        }

        override fun onStopJob(params: JobParameters) = true
    }

    // ---- screens ------------------------------------------------------------------------------

    /** The Home line's right-hand text. */
    fun summary(c: Context): String {
        val films = current(c).count { it.ready == 0L } + Follow.films(c).size
        val shows = if (NewEpisodes.shows().isEmpty()) following(c).size else shownShows(c).count { !Following.ended(it) }
        if (films == 0 && shows == 0) return "empty"
        return listOfNotNull(
            if (films > 0) "$films film" + (if (films == 1) "" else "s") else null,
            if (shows > 0) "$shows show" + (if (shows == 1) "" else "s") else null,
        ).joinToString(" · ") + "  ►"
    }

    /** Shows that matter in Following: not the ended ones you've finished, nor ended favourites you never chose. */
    private fun shownShows(c: Context): List<Full> {
        val ids = following(c).map { it.first }.toSet()
        val infos = History.infosById(c)
        val chosen = Follow.chosen(c)
        return NewEpisodes.shows().filter { sh ->
            if (sh.id !in ids) return@filter false
            val sec = Following.section(sh, watchedOf(c, sh, infos), infos[sh.id])
            sec != Following.Section.FINISHED && !(sec == Following.Section.NOT_STARTED && Following.ended(sh) && sh.id !in chosen)
        }
    }

    /** The Following line's right-hand text. */
    fun followingSummary(c: Context): String {
        val shows = if (NewEpisodes.shows().isEmpty()) following(c).size else shownShows(c).size
        val films = Follow.films(c).size
        if (shows == 0 && films == 0) return "empty"
        return listOfNotNull(
            if (shows > 0) "$shows show" + (if (shows == 1) "" else "s") else null,
            if (films > 0) "$films film" + (if (films == 1) "" else "s") else null,
        ).joinToString(" · ") + "  ►"
    }

    fun screen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            // Films you've since watched drop off the list.
            val marks = History.marks(app)
            // Films you've finished or started leave the list; better versions once you've played the one found.
            val keep = current(app)
            val last = lastCheck(app)
            val rows = mutableListOf(
                // When on the left, how often on the right (on a phone in portrait, the line below).
                Row(
                    if (last == 0L) "Not checked yet" else "Checked ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK).format(java.util.Date(last))}",
                    if (isPhone(app)) "every 6h, with alerts" else "when the app opens",
                ),
                Row("Check now", action = {
                    app.toast("Checking…")
                    Net.async({ checkNow(app) }) { r ->
                        r.onSuccess { ready ->
                            app.toast(if (ready.isEmpty()) "Nothing new yet" else "Now available: " + ready.joinToString(", ") { it.name })
                            notify(app, ready)
                            History.pull(app, force = true) { screen.rows = build(); app.refresh(screen) }
                            screen.rows = build(); app.refresh(screen)
                        }.onFailure { app.toast("Couldn't check: ${Net.describe(it)}") }
                    }
                }),
                Row("────────────────────"),
            )
            fun filmLabel(e: Entry) = Marks.title(if (e.better) Marks.BETTER else Marks.WAITING, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else ""
            // Films: no stream yet (out, or not out to stream yet with its date), and better versions.
            val notOut = Follow.films(app)
            val plain = keep.filter { !it.better && it.ready == 0L && notOut.none { n -> n.id == it.id } }
            rows += Row("──── Films: waiting for a stream ────")
            if (plain.isEmpty() && notOut.isEmpty()) rows += Row("None.")
            plain.sortedBy { it.name }.forEach { e -> rows += Row(filmLabel(e), "no stream yet  ►", opens = { Details.screenFor(app, e.id, e.type, e.name, e.year) ?: Screen(e.name, listOf(Row("Your add-ons are still loading. Try again in a moment."))) }) }
            notOut.sortedBy { it.name }.forEach { f ->
                rows += Row(Marks.title(Marks.WAITING, f.name) + if (f.year.isNotEmpty()) " (${f.year})" else "", f.note.ifBlank { "not out yet" } + "  ►",
                    opens = { Details.screenFor(app, f.id, "movie", f.name, f.year) ?: Screen(f.name, listOf(Row("Your add-ons are still loading."))) })
            }
            val upgrades = keep.filter { it.better && it.ready == 0L }
            rows += Row("──── Films: waiting for a better version ────")
            if (upgrades.isEmpty()) rows += Row("None.")
            upgrades.sortedBy { it.name }.forEach { e -> rows += Row(filmLabel(e), "have: ${e.have}  ►", opens = { Details.screenFor(app, e.id, e.type, e.name, e.year) ?: Screen(e.name, listOf(Row("Your add-ons are still loading. Try again in a moment."))) }) }
            // Shows you follow: waiting for a new season, or for the next episode of this one.
            val shows = shownShows(app).filter { !Following.ended(it) }
            // Each show sits where you follow it: for new seasons, or for every new episode.
            val rec = Follow.raw(app)
            val (season, episodes) = shows.partition { sh -> Following.mode(rec, sh.id) == Following.SEASONS }
            fun showRow(sh: Full) = Row(Marks.title(Marks.FOLLOWING, sh.name), Following.nextText(sh) + "  ►",
                opens = { Details.screenFor(app, sh.id, "series", sh.name, "") ?: Screen(sh.name, listOf(Row("Your add-ons are still loading."))) })
            val loadingShows = NewEpisodes.isLoading()
            // Followed shows whose details haven't come in yet (just followed, or a fetch failed).
            val unknown = following(app).filter { (id, _) -> NewEpisodes.shows().none { it.id == id } }
            rows += Row("──── Shows: waiting for a new season ────")
            if (loadingShows) rows += Row("Checking the shows you follow…")
            else if (season.isEmpty() && unknown.isEmpty()) rows += Row("None.")
            season.sortedBy { it.name }.forEach { rows += showRow(it) }
            if (!loadingShows) unknown.sortedBy { it.second }.forEach { (id, name) ->
                rows += Row(Marks.title(Marks.FOLLOWING, name), "couldn't load its dates yet  ►",
                    opens = { Details.screenFor(app, id, "series", name, "") ?: Screen(name, listOf(Row("Your add-ons are still loading."))) })
            }
            rows += Row("──── Shows: waiting for new episodes ────")
            if (!loadingShows && episodes.isEmpty()) rows += Row("None right now.")
            episodes.sortedBy { Releases.upcoming(it, 3650).firstOrNull()?.released ?: "9" }.forEach { rows += showRow(it) }
            rows += Row("────────────────────")
            rows += Row("Ready", Ready.summary(app), opens = { Ready.screen(app) })
            val followed = following(app)
            if (followed.isNotEmpty()) rows += Row("Stop following all shows", "►", opens = {
                confirmScreen(app, "Stop following all shows?", "Stops all ${followed.size} shows, on every device. They stay stopped even if you watch more or they're favourites; follow one again from its page.", "Yes, stop all ${followed.size}") {
                    Follow.unfollowAll(app, followed.map { it.first }); app.toast("Stopped following all shows"); app.back()
                }
            })
            val filmCount = plain.size + upgrades.size + notOut.size
            if (filmCount > 0) rows += Row("Stop waiting for all films", "►", opens = {
                confirmScreen(app, "Stop waiting for all films?", "Stops all $filmCount films, on every device.", "Yes, stop all $filmCount") {
                    save(app, list(app).filter { it.ready > 0 }); Follow.unfollowFilms(app, notOut.map { it.id }); app.toast("Stopped waiting for the films"); app.back()
                }
            })
            return rows
        }
        screen = Screen("Waiting", build())
        var loaded = false
        screen.onShow = {
            screen.rows = build(); app.refresh(screen)
            // Changes made on another device (follows, the waiting list) are fetched on the way in.
            History.pull(app, force = true) { if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) } }
            if (!loaded) {
                loaded = true
                val missing = following(app).any { (id, _) -> NewEpisodes.shows().none { it.id == id } }
                NewEpisodes.load(app, force = missing, progress = { if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) } }) {
                    if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) }
                }
            }
            checkFollowedFilms(app) { if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) } }
        }
        return screen
    }

}
