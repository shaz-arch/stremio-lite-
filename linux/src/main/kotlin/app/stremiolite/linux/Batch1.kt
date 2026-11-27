package app.stremiolite.linux

import app.stremiolite.Full
import app.stremiolite.MetaParse
import app.stremiolite.SendToTv
import app.stremiolite.Sync
import app.stremiolite.Trailer
import app.stremiolite.Video
import java.io.File

/** "Watch the trailer": in mpv when yt-dlp is there to fetch YouTube, else in the browser. */
object Trailers {
    private val ytdlp: Boolean by lazy {
        System.getenv("PATH").orEmpty().split(':').any { File(it, "yt-dlp").canExecute() || File(it, "youtube-dl").canExecute() }
    }

    fun row(f: Full): Row? {
        if (f.trailerUrl.isBlank() && Prefs.tmdbKey.isBlank()) return null
        return Row("Trailer", if (ytdlp && File(Prefs.player).name.startsWith("mpv")) "in mpv  ►" else "browser  ►", action = {
            if (f.trailerUrl.isNotBlank()) open(f.trailerUrl)
            else {
                App.toast("Finding the trailer…")
                val key = Prefs.tmdbKey
                App.async({ Trailer.fromTmdb(key, f.type, f.id) }) { r -> r.getOrNull()?.let { open(it) } ?: App.toast("No trailer found for ${f.name}") }
            }
        })
    }

    private fun open(url: String) {
        if (ytdlp && File(Prefs.player).name.startsWith("mpv")) {
            val ok = runCatching { ProcessBuilder(Prefs.player, "--no-terminal", "--force-window=immediate", url).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.isSuccess
            if (ok) { App.toast("Trailer opening in mpv…"); return }
        }
        WebLink.open(url)
    }
}

/**
 * Send to TV on Linux: send a title or episode from here, and (if you switch it on in Settings)
 * play what's sent from the phone. Off by default, so one send doesn't start a computer as well as the TV.
 */
object TvRemote {
    private val me: String by lazy { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("Linux") }

    fun sendRow(f: Full, v: Video? = null): Row? {
        if (Account.authKey == null) return null
        return Row(if (v == null) "Send to TV" else "Send this episode to TV", "►", action = {
            val name = if (v == null) f.name else "${f.name} ${MetaParse.epLabel(v)}"
            val r = SendToTv.Request(f.id, f.type, name, f.year, v?.id.orEmpty(), System.currentTimeMillis(), me)
            Lib.put(SendToTv.record(Lib.raw(SendToTv.ID)?.toString(), r))
            App.toast("Sent to your TV: $name")
        })
    }

    fun start() {
        Thread {
            while (true) {
                Thread.sleep(8_000)
                val key = Account.authKey ?: continue
                if (!Prefs.receiveSends || Player.playing) continue
                val raw = runCatching { Sync.pullIds(key, listOf(SendToTv.ID)).optJSONObject(0)?.toString() }.getOrNull()
                val req = SendToTv.parse(raw) ?: continue
                if (req.at <= Prefs.sentHandled || req.from == me) continue
                Prefs.sentHandled = req.at
                if (System.currentTimeMillis() - req.at > SendToTv.FRESH_MS) continue
                App.post { play(req) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun play(req: SendToTv.Request) {
        App.toast("From ${req.from}: ${req.name}")
        if (req.video.isEmpty()) { App.push(Details.screenFor(req.id, req.type, req.name, autoPlay = true)); return }
        App.async({ MetaParse.fetch(Home.all(), "series", req.id) }) { r ->
            val f = r.getOrNull(); val v = f?.videos?.firstOrNull { it.id == req.video }
            if (f == null || v == null) App.toast("Couldn't load ${req.name}")
            else App.push(Play.best("series", v.id, "${f.name} ${MetaParse.epLabel(v)}", Details.watchFor(f, v), Lib.resumeAt(f.id, v.id)))
        }
    }
}

/** Saving the Calendar as an .ics file any calendar app can import. */
object CalendarSave {
    fun save(items: List<app.stremiolite.CalendarFile.Item>) {
        if (items.isEmpty()) { App.toast("Nothing dated to save yet"); return }
        val home = File(System.getProperty("user.home"))
        val dir = File(home, "Downloads").takeIf { it.isDirectory } ?: home
        val f = File(dir, "stremonino-calendar.ics")
        runCatching { f.writeText(app.stremiolite.CalendarFile.ics(items)) }
            .onSuccess { App.toast("Saved ${items.size} dates to ${f.path}: import it into your calendar", 8_000) }
            .onFailure { App.toast("Couldn't save: ${it.message}") }
    }
}

/** While mpv plays: pause, skip and stop from Stremonino (through mpv's control socket). */
object NowPlaying {
    fun row(): Row? = if (!Player.playing) null else Row("Now playing", Player.title + "  ►", opens = { screen() })

    fun screen(): Screen = Screen("Now playing", listOf(
        Row(Player.title.ifBlank { "Playing in your player" }),
        Row("────────────────────"),
        Row("Pause / resume", action = { if (!Player.command("cycle", "pause")) App.toast("Not playing in mpv") }),
        Row("Back 10 seconds", action = { Player.command("seek", -10, "relative") }),
        Row("Forward 30 seconds", action = { Player.command("seek", 30, "relative") }),
        Row("Forward 90 seconds (skip an intro)", action = { Player.command("seek", 90, "relative") }),
        Row("Show the time on screen", action = { Player.command("show-progress") }),
        Row("Stop", action = { Player.stop("Stopped from Stremonino"); App.back() }),
    ))
}

/** "For you" on Home: suggestions from your history ([app.stremiolite.ForYou]), rebuilt once a day. */
object ForYouUi {
    private val F = app.stremiolite.ForYou
    private var result: app.stremiolite.ForYou.Result? = null
    private var readFor = ""
    private var building = false
    private var tried = 0L

    private fun current(): app.stremiolite.ForYou.Result? {
        val who = Profiles.current()?.name.orEmpty()
        if (who != readFor) { readFor = who; result = F.parse(Prefs.forYou.ifEmpty { null }) }
        return result
    }

    fun refresh(force: Boolean = false, done: () -> Unit = {}) {
        val key = Prefs.tmdbKey
        val have = current()
        if (key.isBlank() || building || (!force && have != null && System.currentTimeMillis() - have.at < F.KEEP_MS)) return
        if (!force && System.currentTimeMillis() - tried < 10 * 60_000L) return
        tried = System.currentTimeMillis(); building = true
        val infos = Lib.infos().filter { it.type == "movie" || it.type == "series" }
        val recent = infos.filter { it.lastWatched > 0 }.sortedByDescending { it.lastWatched }.map { app.stremiolite.ForYou.Seed(it.id, it.type, it.name) }
        val favs = infos.filter { it.inLibrary }.sortedByDescending { it.updated }.map { app.stremiolite.ForYou.Seed(it.id, it.type, it.name) }
        val exclude = (infos.filter { it.filmWatched || it.continuing || app.stremiolite.Following.started(it) }.map { it.id } + NextList.ids()).toSet()
        val avoid = if (force) have?.top?.map { it.id }?.toSet().orEmpty() else emptySet()
        App.async({ F.build(key, recent, favs, exclude, avoid = avoid) }) { r ->
            building = false
            r.getOrNull()?.let { res -> result = res; Prefs.forYou = F.json(res) }
            done()
        }
    }

    fun homeRows(): List<Row> {
        if (Prefs.tmdbKey.isBlank() || KidsGuard.locked()) return emptyList()
        val top = Prefs.onHome("foryou.top"); val because = Prefs.onHome("foryou.because"); val favs = Prefs.onHome("foryou.favs")
        if (!top && !because && !favs) return emptyList()
        val r = current()
        if (r == null || System.currentTimeMillis() - r.at > F.KEEP_MS) refresh { Home.redraw() }
        val rows = mutableListOf(Row("──── For you ────"))
        if (r == null) { rows += Row("Finding suggestions from what you watch…"); return rows }
        if (top && r.top.isNotEmpty()) rows += Row("Top picks for you", "${r.top.size}  ►", opens = { list("Top picks for you", r.top, true) })
        if (because) r.because.forEach { (s, l) -> rows += Row("Because you watched ${s.name}", "${l.size}  ►", opens = { list("Because you watched ${s.name}", l) }) }
        if (favs && r.favourites.isNotEmpty()) rows += Row("More like your favourites", "${r.favourites.size}  ►", opens = { list("More like your favourites", r.favourites) })
        if (rows.size == 1) rows += Row("Nothing yet.")
        return rows
    }

    private fun list(title: String, first: List<app.stremiolite.ForYou.Pick>, refreshRow: Boolean = false): Screen {
        lateinit var s: Screen
        fun rowsFor(picks: List<app.stremiolite.ForYou.Pick>): List<Row> {
            val rows = picks.map { p ->
                Row(app.stremiolite.Marks.title(Catalogs.mark(p.id, p.type), p.name) + if (p.year.isNotEmpty()) " (${p.year})" else "", MetaParse.typeLabel(p.type) + "  ►",
                    opens = { Details.screenFor(p.id, p.type, p.name) }, play = { BasketList.toggle(p.id, p.type, p.name, p.year) })
            }.toMutableList()
            if (refreshRow) {
                rows += Row("────────────────────")
                rows += Row("Refresh suggestions", action = {
                    App.toast("Finding new suggestions…")
                    refresh(true) {
                        current()?.top?.let { s.rows = rowsFor(it); s.moveTo = 0; App.refresh() }
                        App.toast("Suggestions updated"); Home.redraw()
                    }
                })
            }
            return rows
        }
        s = Screen(title, rowsFor(first))
        return s
    }
}
