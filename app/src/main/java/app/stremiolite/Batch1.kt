package app.stremiolite

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.speech.RecognizerIntent

/** "Watch the trailer": the add-on's, else TMDB's. A phone opens YouTube; a stick tries YouTube apps, else a QR code. */
object Trailers {
    /** YouTube apps a Fire Stick or Android TV may have (Fire TV's own, Android TV's, SmartTube). */
    private val tvApps = listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "com.google.android.youtube")

    fun row(app: MainActivity, f: Full): Row? {
        if (f.trailerUrl.isBlank() && Settings.tmdbKey(app).isBlank()) return null
        return Row("Trailer", (if (app.isPhone) "opens YouTube" else "YouTube app or QR") + "  ►", action = {
            if (f.trailerUrl.isNotBlank()) open(app, f.name, f.trailerUrl)
            else {
                app.toast("Finding the trailer…")
                val key = Settings.tmdbKey(app)
                Net.async({ Trailer.fromTmdb(key, f.type, f.id) }) { r ->
                    val url = r.getOrNull()
                    if (url == null) app.toast("No trailer found for ${f.name}") else open(app, f.name, url)
                }
            }
        })
    }

    private fun open(app: MainActivity, name: String, url: String) {
        if (app.isPhone) { LinkOut.open(app, url); return }
        val id = Trailer.youtubeId(url)
        val links = listOfNotNull(id?.let { "https://www.youtube.com/watch?v=$it" }, url).distinct()
        for (pkg in tvApps) for (link in links) {
            try { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)); return } catch (e: ActivityNotFoundException) { } catch (e: SecurityException) { }
        }
        app.push(LinkOut.qr("Trailer: $name", url))
    }
}

/**
 * Send to TV. On the phone: "Send to TV" on a title or an episode. On a TV-side device: a quiet
 * check every few seconds while the app is open (not while playing), which plays what was sent.
 */
object TvRemote {
    private const val EVERY_MS = 8_000L
    private var generation = 0

    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")

    fun sendRow(app: MainActivity, f: Full, v: Video? = null): Row? {
        if (!app.isPhone || Account.authKey(app) == null) return null
        return Row(if (v == null) "Send to TV" else "Send this episode to TV", "►", action = {
            val name = if (v == null) f.name else "${f.name} ${Details.epLabel(v)}"
            val r = SendToTv.Request(f.id, f.type, name, f.year, v?.id.orEmpty(), System.currentTimeMillis(), Build.MODEL ?: "phone")
            History.putRemote(app, SendToTv.record(History.rawRemote(app, SendToTv.ID), r))
            app.toast("Sent to your TV: $name. It plays within a few seconds if Stremonino is open there.")
        })
    }

    /** Starts the check on a TV-side device (called when the app comes to the front). */
    fun listen(app: MainActivity) {
        if (app.isPhone) return
        val mine = ++generation
        fun tick() {
            if (mine != generation) return
            val key = Account.authKey(app)
            if (key == null || app.playerOpen) { Net.later(EVERY_MS, Runnable { tick() }); return }
            Net.async({ Sync.pullIds(key, listOf(SendToTv.ID)) }) { r ->
                val raw = r.getOrNull()?.optJSONObject(0)?.toString()
                SendToTv.parse(raw)?.let { req -> handle(app, req) }
                Net.later(EVERY_MS, Runnable { tick() })
            }
        }
        tick()
    }

    fun stop() { generation++ }

    private fun handle(app: MainActivity, req: SendToTv.Request) {
        val last = prefs(app).getLong("sentHandled", 0L)
        if (req.at <= last) return
        prefs(app).edit().putLong("sentHandled", req.at).apply()
        if (System.currentTimeMillis() - req.at > SendToTv.FRESH_MS || app.playerOpen) return
        app.toast("From ${req.from}: ${req.name}")
        if (req.video.isEmpty()) {
            Details.screenFor(app, req.id, req.type, req.name, req.year, autoPlay = true)?.let { app.push(it) }
            return
        }
        val addons = Home.allAddons()
        Net.async({ MetaParse.fetch(addons, "series", req.id) }) { r ->
            val f = r.getOrNull(); val v = f?.videos?.firstOrNull { it.id == req.video }
            if (f == null || v == null) app.toast("Couldn't load ${req.name}") else app.push(Details.playEpisode(app, f, v))
        }
    }
}

/** Speaking a search (where the device has a speech service; older Fire Sticks often don't). */
object Voice {
    const val REQUEST = 9

    fun available(c: Context): Boolean =
        runCatching { c.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }.getOrDefault(false)

    fun listen(app: MainActivity) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a film or show")
        try { @Suppress("DEPRECATION") app.startActivityForResult(i, REQUEST) }
        catch (e: Exception) { app.toast("This device can't take voice search") }
    }

    /** The words heard, or null. */
    fun heard(data: Intent?): String? = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.length >= 2 }
}

/** Calendar entries in the phone's own calendar app (one at a time; no permission needed). */
object CalendarExport {
    fun row(app: MainActivity, title: String, day: String, note: String): Row? {
        if (!app.isPhone) return null
        return Row("Add to my phone's calendar", action = {
            val start = ComingSoon.day(day).takeIf { it > 0 } ?: return@Row
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.Events.DESCRIPTION, note)
            try { app.startActivity(i) } catch (e: ActivityNotFoundException) { app.toast("No calendar app found") }
        })
    }
}

/** "For you" on Home: suggestions from your history ([ForYou]), rebuilt once a day in the background. */
object ForYouUi {
    private var result: ForYou.Result? = null
    private var readFor = ""
    private var building = false
    private var tried = 0L

    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")

    private fun current(c: Context): ForYou.Result? {
        val who = Profiles.current(c)?.id.orEmpty()
        if (who != readFor) { readFor = who; result = ForYou.parse(prefs(c).getString("forYou", null)) }
        return result
    }

    /** Rebuilds if it's a day old (or [force]); then [done]. */
    fun refresh(app: MainActivity, force: Boolean = false, done: () -> Unit = {}) {
        val key = Settings.tmdbKey(app)
        val have = current(app)
        // Nothing to do: no callback (Home redraws from the callback, which would ask again).
        if (key.isBlank() || building || (!force && have != null && System.currentTimeMillis() - have.at < ForYou.KEEP_MS)) return
        if (!force && System.currentTimeMillis() - tried < 10 * 60_000L) return
        tried = System.currentTimeMillis()
        building = true
        val infos = History.infos(app).filter { it.type == "movie" || it.type == "series" }
        val recent = infos.filter { it.lastWatched > 0 }.sortedByDescending { it.lastWatched }.map { ForYou.Seed(it.id, it.type, it.name) }
        val favs = infos.filter { it.inLibrary }.sortedByDescending { it.updated }.map { ForYou.Seed(it.id, it.type, it.name) }
        val exclude = (infos.filter { it.filmWatched || it.continuing || Following.started(it) }.map { it.id } + NextList.ids(app)).toSet()
        Net.async({ ForYou.build(key, recent, favs, exclude) }) { r ->
            building = false
            r.getOrNull()?.let { res -> result = res; prefs(app).edit().putString("forYou", ForYou.json(res)).apply() }
            done()
        }
    }

    fun homeRows(app: MainActivity): List<Row> {
        if (Settings.tmdbKey(app).isBlank() || KidsGuard.locked(app)) return emptyList()
        val r = current(app)
        if (r == null || System.currentTimeMillis() - r.at > ForYou.KEEP_MS) refresh(app) { Home.redraw() }
        val rows = mutableListOf(Row("──── For you ────"))
        if (r == null) { rows += Row("Finding suggestions from what you watch…"); return rows }
        if (r.top.isNotEmpty()) rows += Row("Top picks for you", "${r.top.size}  ►", opens = { list(app, "Top picks for you", r.top, refreshRow = true) })
        r.because.forEach { (s, l) -> rows += Row("Because you watched ${s.name}", "${l.size}  ►", opens = { list(app, "Because you watched ${s.name}", l) }) }
        if (r.favourites.isNotEmpty()) rows += Row("More like your favourites", "${r.favourites.size}  ►", opens = { list(app, "More like your favourites", r.favourites) })
        if (rows.size == 1) rows += Row("Watch a few things and suggestions appear here.")
        return rows
    }

    private fun list(app: MainActivity, title: String, picks: List<ForYou.Pick>, refreshRow: Boolean = false): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val marks = History.marks(app)
            val rows = picks.map { p ->
                Row(Marks.title(marks[p.id], p.name) + if (p.year.isNotEmpty()) " (${p.year})" else "", Catalogs.typeLabel(p.type).removeSuffix("s") + "  ►",
                    opens = { Details.screenFor(app, p.id, p.type, p.name, p.year) ?: Screen(p.name, listOf(Row("Your add-ons are still loading."))) },
                    play = { BasketList.toggle(app, p.id, p.type, p.name, p.year); s.rows = build(); app.refresh(s) })
            }.toMutableList()
            if (refreshRow) {
                rows += Row("────────────────────")
                rows += Row("Refresh suggestions", action = { app.toast("Finding new suggestions…"); refresh(app, force = true) { app.toast("Suggestions updated"); Home.redraw() } })
            }
            return rows
        }
        s = Screen(title, build())
        return s
    }
}

/** Alerts copied to an ntfy.sh topic, so a laptop or someone else's phone gets them too. */
object Ntfy {
    fun send(c: Context, text: String, title: String = "Stremonino") {
        val topic = Settings.ntfyTopic(c)
        if (topic.isEmpty()) return
        Thread {
            runCatching { Http.sendJson("https://ntfy.sh/", "POST", org.json.JSONObject().put("topic", topic).put("title", title).put("message", text).toString(), 10_000) }
        }.start()
    }
}
