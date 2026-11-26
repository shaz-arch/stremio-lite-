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
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Waiting": films that are out but have no good stream yet, or that you want a better version of.
 * Films not out to stream yet, and shows, are in Following (screen below, list in [Follow]).
 *
 * The list lives on the Stremio account as one hidden record (removed, so Stremio's own apps never
 * show it), so the phone and the stick share it. The phone checks every few hours in the background
 * and sends a notification; either device also checks when the app is opened, if a check is due.
 */
object Waiting {
    private const val ID = "stremiolite:waiting"
    private const val EVERY_MS = 6 * 60 * 60_000L
    private const val MAX_CHECKS = 20
    private const val JOB_ID = 4201
    private const val CHANNEL = "waiting"

    /**
     * [ready]: when a stream was first found (0 = still waiting).
     * [better]: waiting for a better version than [have] (a stream scoring [bar]); [found] is what turned up.
     */
    class Entry(
        val id: String, val type: String, val name: String, val year: String, val ready: Long,
        val better: Boolean = false, val bar: Double = 0.0, val have: String = "", val found: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("year", year).put("ready", ready)
            .apply { if (better) put("better", true).put("bar", bar).put("have", have).put("found", found) }

        fun readyWith(now: Long, what: String) = Entry(id, type, name, year, now, better, bar, have, what)
    }

    private fun parse(raw: String?): List<Entry> {
        val name = raw?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() } ?: return emptyList()
        val arr = runCatching { JSONArray(name) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Entry(
                    it.optString("id"), it.optString("type"), it.optString("name"), it.optString("year"), it.optLong("ready"),
                    it.optBoolean("better"), it.optDouble("bar", 0.0), it.optString("have"), it.optString("found"),
                )
            }
        }.filter { it.id.isNotEmpty() }
    }

    private fun record(base: String?, list: List<Entry>): JSONObject {
        val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "[]")
        return item.put("name", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            .put("removed", true).put("temp", false)
            .put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    fun list(c: Context): List<Entry> = parse(History.rawRemote(c, ID))

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
        Net.async({ StreamParse.bestPlayable(Home.allAddons(), type, id) }) { r ->
            val now = r.getOrNull()
            val have = now?.first?.summary?.replace(Regex("\\s+"), " ")?.trim() ?: "nothing playable yet"
            save(app, list(app).filter { it.id != id } + Entry(id, type, name, year, 0, true, now?.second ?: 0.0, have, ""))
            askToNotify(app)
            done(have)
        }
    }

    private fun save(c: Context, list: List<Entry>) = History.putRemote(c, record(History.rawRemote(c, ID), list))

    /**
     * Next watch films that aren't ready yet, followed quietly (no check now: it just found none):
     * ones not out to stream yet go to Following, ones out with no good stream to Waiting.
     */
    fun follow(app: MainActivity, films: List<NextWatch.Entry>) {
        val movies = films.filter { it.type == "movie" }
        val soon = movies.filter { NextStatus.of(it.id)?.group == NextStatus.Group.SOON }
        val have = list(app)
        val followed = Follow.films(app).map { it.id }.toSet()
        Follow.followFilms(app, soon.filter { it.id !in followed }.map { Following.Film(it.id, it.name, it.year, NextStatus.of(it.id)?.detail.orEmpty()) })
        // Not out yet after all: it waits in Following, not here (a "better version" wait stays).
        val kept = have.filter { e -> e.better || soon.none { it.id == e.id } }
        val add = (movies - soon.toSet()).filter { f -> NextStatus.of(f.id)?.group == NextStatus.Group.WAITING && kept.none { it.id == f.id } && f.id !in followed }
        if (add.isEmpty() && kept.size == have.size) return
        save(app, kept + add.map { Entry(it.id, it.type, it.name, it.year, 0) })
        askToNotify(app)
    }

    fun add(app: MainActivity, e: Entry) {
        save(app, list(app).filter { it.id != e.id } + e)
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
    private fun following(c: Context) = NewEpisodes.followedNow(c)

    /** Episodes of [show] you've watched, from the account plus this device. */
    private fun watchedOf(c: Context, show: Full, infos: Map<String, LibraryItems.Info>): Set<String> {
        val order = MetaParse.stremioOrder(show)
        return (infos[show.id]?.let { Sync.decodeWatched(it.watchedField, order) } ?: emptySet()) +
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
            app.push(if (new.size == 1) entry(app, new[0]) else screen(app))
        })
    }

    /**
     * The check itself (blocking): reads the list from the account, asks the stream add-ons about
     * each film still waiting, marks the ones now available, and returns those.
     */
    fun checkNow(c: Context): List<Entry> {
        val key = Account.authKey(c) ?: return emptyList()
        val arr = Sync.pullIds(key, listOf(ID, Following.ID))
        val objs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val raw = objs.firstOrNull { it.optString("_id") == ID }?.toString()
        val fol = objs.firstOrNull { it.optString("_id") == Following.ID }
        var all = parse(raw)
        prefs(c).edit().putLong("waitingChecked", System.currentTimeMillis()).apply()
        val now = System.currentTimeMillis()
        val addons = runCatching { Stremio.parseAddons(Account.addonCache(c).readText()) }.getOrNull() ?: return emptyList()
        // Followed films now out to stream move here, to be checked for a good stream.
        var moved = false
        var folNow = fol
        if (Following.films(fol).isNotEmpty()) {
            val (newFol, newAll) = outCheck(c, addons, fol, all, now)
            if (newFol != null) { Sync.push(key, listOf(newFol)); folNow = newFol }
            if (newAll != null) { all = newAll; moved = true }
        }
        val notOut = mutableListOf<Following.Film>()
        val pending = all.filter { it.ready == 0L }.take(MAX_CHECKS)
        val got = HashMap<String, String>()
        val tmdb = Settings.tmdbKey(c)
        val today = ComingSoon.today(now)
        pending.forEach { e ->
            // Only in cinemas, digital later: anything found now is a fake or a cam, not "ready".
            if (tmdb.isNotBlank() && e.type == "movie" &&
                runCatching { WhereToWatch.load(tmdb, "movie", e.id, "", Settings.watchRegion(c)) }.getOrNull()?.takeIf { it.notOutYet(today) }?.let { w ->
                    // Not out to stream yet: it moves to Following (a "better version" wait stays here).
                    if (!e.better) notOut += Following.Film(e.id, e.name, e.year, w.notOutLabel(today))
                    true
                } == true) return@forEach
            if (e.better) runCatching { StreamParse.betterThan(addons, e.type, e.id, e.bar) }.getOrNull()?.let { got[e.id] = it.first.summary.replace(Regex("\\s+"), " ").trim() }
            else if (runCatching { StreamParse.availableNow(addons, e.type, e.id) }.getOrDefault(false)) got[e.id] = ""
        }
        if (notOut.isNotEmpty()) {
            Sync.push(key, listOf(Following.followFilms(folNow, notOut)))
            val ids = notOut.map { it.id }.toSet()
            all = all.filter { it.better || it.id !in ids }; moved = true
        }
        val found = got.keys
        if (found.isEmpty()) { if (moved) Sync.push(key, listOf(record(raw, all))); return emptyList() }
        val updated = all.map { if (it.id in found) it.readyWith(now, got.getValue(it.id)) else it }
        Sync.push(key, listOf(record(raw, updated)))
        return updated.filter { it.id in found }
    }

    /**
     * Followed films (blocking): ones now out to stream come off Following and join [wait];
     * the rest get a fresh "when and where". Returns the changed record and list (null = unchanged).
     */
    private fun outCheck(c: Context, addons: List<Addon>, fol: JSONObject?, wait: List<Entry>, now: Long): Pair<JSONObject?, List<Entry>?> {
        val tmdb = Settings.tmdbKey(c); val region = Settings.watchRegion(c)
        val out = mutableListOf<Following.Film>(); val notes = mutableListOf<Following.Film>()
        Following.films(fol).take(MAX_CHECKS).forEach { f ->
            val (isOut, note) = runCatching { FilmOut.check(addons, tmdb, region, f.id, now) }.getOrNull() ?: return@forEach
            if (isOut) out += f else if (note != f.note) notes += Following.Film(f.id, f.name, f.year, note)
        }
        var rec = fol
        if (notes.isNotEmpty()) rec = Following.followFilms(rec, notes)
        if (out.isNotEmpty()) rec = Following.unfollowFilms(rec, out.map { it.id })
        val add = out.filter { o -> wait.none { it.id == o.id && !it.better } }.map { Entry(it.id, "movie", it.name, it.year, 0) }
        return (if (rec !== fol) rec else null) to (if (add.isNotEmpty()) wait + add else null)
    }

    /** In the app: re-checks the followed films now (when Following opens), then [done]. */
    fun checkFollowedFilms(app: MainActivity, done: () -> Unit) {
        if (Follow.films(app).isEmpty()) return
        val fol = Follow.raw(app); val wait = list(app); val addons = Home.allAddons()
        Net.async({ outCheck(app, addons, fol, wait, System.currentTimeMillis()) }) { r ->
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
    }

    class Job : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
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
        val l = current(c)
        if (l.isEmpty()) return "empty"
        val ready = l.count { it.ready > 0 }
        val waiting = l.count { it.ready == 0L && !it.better }
        val upgrades = l.count { it.ready == 0L && it.better }
        return listOfNotNull(
            if (ready > 0) "$ready ready" else null,
            if (waiting > 0) "$waiting for a stream" else null,
            if (upgrades > 0) "$upgrades for better" else null,
        ).joinToString(" · ").ifEmpty { "${l.size}" } + "  ►"
    }

    /** Shows that matter in Following: not the ended ones you've finished, nor ended favourites you never chose. */
    private fun shownShows(c: Context): List<Full> {
        val ids = following(c).map { it.first }.toSet()
        val infos = History.infos(c).associateBy { it.id }
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
            rows += Row("──── Waiting for a stream ────")
            if (keep.none { !it.better }) rows += Row("None. On a film, choose 'Tell me when a stream is available'.")
            keep.filter { !it.better }.sortedWith(compareByDescending<Entry> { it.ready }.thenBy { it.name }).forEach { e ->
                val label = Marks.title(if (e.ready > 0) Marks.READY else if (e.better) Marks.BETTER else Marks.WAITING, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else ""
                rows += Row(label, if (e.ready > 0) "ready  ►" else "waiting  ►", opens = { entry(app, e) })
            }
            val upgrades = keep.filter { it.better }
            if (upgrades.isNotEmpty()) {
                rows += Row("──── Better versions ────")
                upgrades.sortedWith(compareByDescending<Entry> { it.ready }.thenBy { it.name }).forEach { e ->
                    val label = Marks.title(if (e.ready > 0) Marks.READY else if (e.better) Marks.BETTER else Marks.WAITING, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else ""
                    rows += Row(label, (if (e.ready > 0) "found: ${e.found}" else "have: ${e.have}") + "  ►", opens = { entry(app, e) })
                }
            }
            rows += Row("────────────────────")
            rows += Row("Following: shows and films not out yet", Waiting.followingSummary(app), opens = { followingScreen(app) })
            if (keep.isNotEmpty()) rows += Row("Clear the list", "►", opens = {
                confirmScreen(app, "Clear Waiting?", "Stops waiting for all ${keep.size} films, on every device.", "Yes, clear all ${keep.size}") {
                    save(app, emptyList()); app.toast("Waiting cleared"); app.back()
                }
            })
            return rows
        }
        screen = Screen("Waiting", build())
        screen.onShow = {
            markSeen(app)
            screen.rows = build(); app.refresh(screen)
            // Changes made on another device (follows, the waiting list) are fetched on the way in.
            History.pull(app, force = true) { if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) } }
        }
        return screen
    }

    /** Shows you follow (favourites by themselves), and films followed until they're out to stream. */
    fun followingScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val rows = mutableListOf<Row>()
            val films = Follow.films(app)
            rows += Row("──── Films not out to stream yet ────")
            if (films.isEmpty()) rows += Row("None. Films in Next watch that aren't out yet come here by themselves.")
            films.sortedBy { it.name }.forEach { f ->
                rows += Row(Marks.title(Marks.FOLLOWING, f.name) + if (f.year.isNotEmpty()) " (${f.year})" else "", f.note.ifBlank { "not out yet" } + "  ►", opens = {
                    Screen(f.name, listOfNotNull(
                        Row(if (f.note.isBlank()) "Not out to stream yet." else "Not out to stream yet: ${f.note}."),
                        Row("Moves to Waiting when it's out, then you're told once a good stream turns up."),
                        Row("────────────────────"),
                        Details.screenFor(app, f.id, "movie", f.name, f.year)?.let { d -> Row("Open", "►", opens = { d }) },
                        Row("Stop following", action = { Follow.unfollowFilms(app, listOf(f.id)); app.toast("Stopped following ${f.name}"); app.back() }),
                    ))
                })
            }
            // Shows you follow, by where you're up to (details from the shared hourly check).
            rows += showSections(app) { if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) } }
            rows += Row("────────────────────")
            rows += Row("Episodes out in the last week", "►", opens = { NewEpisodes.screen(app) })
            rows += Row("Calendar: coming up", "►", opens = { NewEpisodes.calendar(app) })
            val shows = following(app)
            if (shows.isNotEmpty()) rows += Row("Stop following all shows", "►", opens = {
                confirmScreen(app, "Stop following all shows?", "Stops all ${shows.size} shows, on every device. They stay stopped even if you watch more or they're favourites; follow one again from its page.", "Yes, stop all ${shows.size}") {
                    Follow.unfollowAll(app, shows.map { it.first }); app.toast("Stopped following all shows"); app.back()
                }
            })
            if (films.isNotEmpty()) rows += Row("Stop following the films", "►", opens = {
                confirmScreen(app, "Stop following the films?", "Stops all ${films.size} films, on every device.", "Yes, stop all ${films.size}") {
                    Follow.unfollowFilms(app, films.map { it.id }); app.toast("Stopped following the films"); app.back()
                }
            })
            return rows
        }
        screen = Screen("Following", build())
        screen.onShow = {
            screen.rows = build(); app.refresh(screen)
            History.pull(app, force = true) {
                if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) }
                checkFollowedFilms(app) { if (app.isTop(screen)) { screen.rows = build(); app.refresh(screen) } }
            }
        }
        return screen
    }

    private fun showSections(app: MainActivity, ready: () -> Unit): List<Row> {
        val rows = mutableListOf<Row>()
        val followed = following(app)
        if (followed.isEmpty()) {
            rows += Row("──── Shows ────")
            rows += Row("None yet. Your favourite shows are followed by themselves.")
            return rows
        }
        if (!NewEpisodes.fresh() && NewEpisodes.shows().isEmpty()) {
            rows += Row("──── Shows: ${followed.size} followed ────")
            rows += Row(if (NewEpisodes.isLoading()) "Checking where you're up to…" else "Checking where you're up to… (back and in again to refresh)")
            NewEpisodes.load(app) { ready() }
            return rows
        }
        // A show followed since the last check is fetched now, not at the next hourly refresh.
        val coming = if (NewEpisodes.isLoading()) emptySet() else NewEpisodes.ensure(app, followed) { ready() }
        val infos = History.infos(app).associateBy { it.id }
        val ids = followed.map { it.first }.toSet()
        val watchedOf = NewEpisodes.shows().filter { it.id in ids }.associate { show -> show.id to watchedOf(app, show, infos) }
        val shown = shownShows(app).map { it.id }.toSet()
        val bySection = NewEpisodes.shows().filter { it.id in shown }.groupBy { show ->
            Following.section(show, watchedOf[show.id] ?: emptySet(), infos[show.id])
        }
        fun section(title: String, list: List<Full>?, detail: (Full) -> String) {
            if (list.isNullOrEmpty()) return
            rows += Row("──── $title ────")
            list.sortedBy { it.name }.forEach { sh ->
                rows += Row(Marks.title(History.marks(app)[sh.id], sh.name), detail(sh) + "  ►", opens = {
                    Details.screenFor(app, sh.id, sh.type, sh.name, sh.year) ?: Screen(sh.name, listOf(Row("Your add-ons are still loading.")))
                })
            }
        }
        fun next(sh: Full) = Following.nextText(sh)
        section("Caught up: waiting for more", bySection[Following.Section.CAUGHT_UP]) { next(it) }
        section("Watching", bySection[Following.Section.WATCHING]) { sh ->
            Following.progressText(sh, watchedOf[sh.id] ?: emptySet(), infos[sh.id])
        }
        section("Not started", bySection[Following.Section.NOT_STARTED]) { next(it) }
        val pending = followed.filter { it.first in coming }
        if (pending.isNotEmpty()) {
            rows += Row("──── Checking ────")
            pending.forEach { (_, name) -> rows += Row(name, "checking…") }
        }
        return rows
    }

    private fun entry(app: MainActivity, e: Entry): Screen = Screen(
        e.name,
        listOfNotNull(
            Row(
                when {
                    e.better && e.ready > 0 -> "Better version found: ${e.found}"
                    e.better -> "Watching for something better than: ${e.have}"
                    e.ready > 0 -> "A stream is available now."
                    else -> "No good stream yet. Still waiting."
                },
            ),
            Row("────────────────────"),
            Details.screenFor(app, e.id, e.type, e.name, e.year)?.let { d -> Row("Open", "►", opens = { d }) },
            Row("Remove from the list", action = { remove(app, e.id); app.toast("Removed"); app.back() }),
        ),
    )
}
