package app.stremiolite.linux

import app.stremiolite.Sleep
import app.stremiolite.Best
import app.stremiolite.Http
import app.stremiolite.LibraryItems
import app.stremiolite.Languages
import app.stremiolite.Stream
import app.stremiolite.StreamFetch
import app.stremiolite.StreamMemory
import app.stremiolite.StreamParse
import app.stremiolite.Sub
import app.stremiolite.SubtitleFetch
import app.stremiolite.YouTube
import org.json.JSONObject
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/** Asking add-ons for streams and subtitles, choosing one (the shared Play best rules), and playing it in mpv. */
object Play {
    /**
     * One round of asking every stream add-on ([StreamFetch]) and every subtitle add-on
     * ([SubtitleFetch]) for a title, both shared with the Android app.
     */
    private class Fetch(type: String, id: String, val onChange: () -> Unit) {
        // mpv plays YouTube links itself (with yt-dlp); other players are left without them.
        private val streams = StreamFetch(type, id, Home.all(), youTube = { yt -> if ("mpv" in Prefs.player) YouTube.watch(yt) else null })
            .listen { onChange() }
        val streamAddons: List<app.stremiolite.Addon> get() = streams.addons
        val found: List<Stream> get() = streams.found
        /** Why the fail-safes are being shown, add-ons that failed, and what was hidden. */
        val notes: List<String> get() = listOfNotNull(streams.failsafeLine()) + streams.notes + listOfNotNull(streams.hiddenLine())
        val links: List<Pair<String, String>> get() = streams.links
        val answered: Int get() = streams.answered
        val started: Long get() = streams.started
        val done: Boolean get() = streams.done

        // Shared with fetching early and the stream list ([SubtitleFetch]).
        private val subtitles = SubtitleFetch.get(type, id, Home.all()).listen { onChange() }
        val subs: List<Sub> get() = subtitles.found
        val subsDone: Boolean get() = subtitles.done

        /** The subtitle to use: the first in your chosen language (null if off or none). */
        fun chosenSub(): Sub? {
            val lang = Prefs.language()
            if (lang.codes.isEmpty()) return null
            return subs.firstOrNull { Languages.matches(it, lang) }
        }
    }

    private fun minutes(w: Watch?, type: String) = w?.runtime?.let { Best.minutes(it) } ?: if (type == "movie") 110.0 else 45.0

    /** Play best: wait for the answers (and a subtitle in your language), pick the winner, play it. */
    fun best(type: String, id: String, title: String, w: Watch?, startAt: Long): Screen {
        val owner = w?.id ?: id
        if (type == "series" && ':' in id && Prefs.skipIntros) App.async({ app.stremiolite.Skips.find(id) }) { }
        if (!KidsGuard.allows(owner)) return Screen(title, listOf(Row("Not allowed on this profile (" + (app.stremiolite.AgeRatings.of(owner.substringBefore(':'))?.ifBlank { null } ?: "not rated yet") + ").")))
        val status = Row("")
        val pickRow = Row("")
        lateinit var s: Screen
        lateinit var fetch: Fetch
        var picked: Stream? = null
        var playing = false
        var started = false
        val tried = mutableSetOf<Stream>()
        val showId = w?.id?.takeIf { type == "series" }
        val prefer = showId?.let { StreamMemory.lastFor(it) }
        val minutes = minutes(w, type)

        lateinit var go: (Stream) -> Unit
        fun rows(): List<Row> {
            val out = mutableListOf(status)
            if (picked != null) out += pickRow
            out += Row("────────────────────")
            if (picked != null) out += Row("Play it again", action = { picked?.let { go(it) } })
            out += Row("Choose a different stream", opens = { list(type, id, title, w, startAt) })
            return out
        }

        lateinit var launch: (Stream) -> Unit
        go = { st ->
            playing = true
            tried += st
            status.label = "Checking the stream…"
            s.rows = rows(); App.refresh()
            App.async({ app.stremiolite.StreamCheck.broken(st.url) }) { r ->
                if (!r.getOrDefault(false)) { launch(st); return@async }
                val next = if (tried.size >= 6) null else Best.pick(fetch.found.filter { it !in tried }, minutes, prefer)
                playing = false
                if (next == null) {
                    status.label = "That release has no playable video, and there's no other good one. Choose another below."
                    if (type == "movie" && NextList.has(id)) {
                        Waiting.unplayable(id, title)
                        status.label += " It's back in Waiting until one plays."
                    }
                    s.rows = rows(); App.refresh()
                } else {
                    picked = next
                    pickRow.label = next.summary; pickRow.detail = next.addon
                    status.label = "Skipped a broken release (no playable video). Trying the next one…"
                    s.rows = rows(); App.refresh()
                    App.later(300) { if (App.isTop(s)) go(next) }
                }
            }
        }
        launch = { st ->
            playing = true
            showId?.let { StreamMemory.remember(it, st) }
            status.label = "Playing in ${Prefs.player}… (this screen waits until it closes)"
            s.rows = rows()
            App.refresh()
            Player.play(st.url, title, fetch.chosenSub(), startAt, video = id) { result ->
                playing = false
                if (result.failed && tried.size < 4) {
                    val next = Best.pick(fetch.found.filter { it !in tried }, minutes, prefer)
                    if (next != null) {
                        picked = next
                        pickRow.label = next.summary; pickRow.detail = next.addon
                        status.label = "That stream didn't play (${result.why}). Trying the next one…"
                        s.rows = rows(); App.refresh()
                        App.later(2_000) { if (App.isTop(s)) go(next) }
                        return@play
                    }
                }
                status.label = if (result.failed) "That stream didn't play (${result.why}). Choose another below."
                else "Played to ${clock(result.pos)}. Press Enter below to play again or choose another."
                s.rows = rows(); App.refresh()
                if (w != null && !result.failed) After.played(w, result)
            }
        }

        fun update() {
            if (picked != null) return
            val lang = Prefs.language()
            val waitingSubs = lang.codes.isNotEmpty() && !fetch.subsDone && fetch.chosenSub() == null
            if (!fetch.done || waitingSubs) {
                status.label = "Finding the best stream… ${fetch.answered} of ${fetch.streamAddons.size} add-ons, ${fetch.found.size} streams" +
                    if (fetch.done && waitingSubs) " · getting subtitles" else ""
                s.rows = rows(); App.refresh()
                return
            }
            if (fetch.streamAddons.isEmpty()) { status.label = "None of your add-ons gives streams for this."; s.rows = rows(); App.refresh(); return }
            val b = Best.pick(fetch.found, minutes, prefer)
            if (b == null) {
                status.label = if (fetch.found.isEmpty()) "No playable streams found." else "No stream suits Play best. Choose one below."
                s.rows = rows(); s.moveTo = s.rows.lastIndex; App.refresh()
                return
            }
            picked = b
            val sub = fetch.chosenSub()?.let { " · subtitles " + Languages.name(it.lang) } ?: ""
            val took = (System.currentTimeMillis() - fetch.started) / 100 / 10.0
            status.label = "Found in $took s (${fetch.found.size} streams).${if (startAt > 0) " From ${clock(startAt)}." else ""}$sub"
            pickRow.label = b.summary
            pickRow.detail = b.addon
            s.rows = rows(); s.moveTo = 0; App.refresh()
            App.later(1_000) { if (App.isTop(s) && !playing) go(b) }
        }

        s = Screen("Play: $title", listOf(status))
        s.onShow = {
            if (!started) {
                started = true
                fetch = Fetch(type, id) { update() }
                update()
            }
        }
        return s
    }

    /** Every stream, best first; Enter plays the highlighted one. */
    fun list(type: String, id: String, title: String, w: Watch?, startAt: Long): Screen {
        lateinit var s: Screen
        lateinit var fetch: Fetch
        val minutes = minutes(w, type)
        var started = false
        var checking = false
        val broken = mutableSetOf<Stream>()
        fun build() {
            val scored = fetch.found.map { it to Best.score(it, minutes) }
                .sortedWith(compareByDescending<Pair<Stream, Double?>> { it.second ?: Double.NEGATIVE_INFINITY }.thenBy { it.first.order })
            val rows = mutableListOf<Row>()
            rows += Row(if (!fetch.done) "Asking ${fetch.streamAddons.size} add-ons… ${fetch.answered} answered" else "${fetch.found.size} streams from ${fetch.streamAddons.size} add-ons")
            val sub = fetch.chosenSub()
            rows += Row(if (sub != null) "Subtitles: ${Languages.name(sub.lang)} · ${sub.addon}" else if (Prefs.language().codes.isEmpty()) "Subtitles: off" else if (fetch.subsDone) "Subtitles: none found in ${Prefs.language().name}" else "Subtitles: looking…")
            fetch.notes.forEach { rows += Row("  $it") }
            fetch.links.forEach { (label, url) -> rows += Row("↗ $label", "opens browser  ►", action = { WebLink.open(url) }) }
            rows += Row("────────────────────")
            if (fetch.done && !checking) {
                checking = true
                scored.filter { it.second != null }.take(5).forEach { (st, _) ->
                    App.async({ app.stremiolite.StreamCheck.broken(st.url) }) { r -> if (r.getOrDefault(false)) { broken += st; build() } }
                }
            }
            val star = scored.firstOrNull { it.second != null && it.first !in broken }?.first
            scored.forEach { (st, score) ->
                rows += Row((if (st === star) "★ " else "") + st.summary + if (score == null) "  (${Best.whyOut(st)})" else "", (if (st in broken) "✗ no video · " else "") + (if (st.langs.isNotEmpty()) st.langs + " · " else "") + st.addon, action = {
                    App.toast("Checking the stream…")
                    App.async({ app.stremiolite.StreamCheck.broken(st.url) }) { ok ->
                        if (ok.getOrDefault(false)) App.toast("That release has no playable video. Pick another.")
                        else Player.play(st.url, title, fetch.chosenSub(), startAt, video = id) { r ->
                            if (r.failed) App.toast("That stream didn't play (${r.why})")
                            else if (w != null) After.played(w, r)
                        }
                    }
                })
            }
            s.rows = rows
            App.refresh()
        }
        s = Screen(title, listOf(Row("Asking your add-ons…")), tag = "Streams")
        s.onShow = { if (!started) { started = true; fetch = Fetch(type, id) { build() }; build() } }
        return s
    }
}

/** What to do after something has played: save progress, and offer the next episode. */
object After {
    fun played(w: Watch, r: Player.Result) {
        if (r.pos <= 0 && r.dur <= 0) return
        val slept = r.launched > 0 && Sleep.stoppedSince(r.launched)
        val done = !slept && LibraryItems.finished(r.pos, r.dur)
        Lib.record(w, r.pos, r.dur, positionKnown = true, done = done)
        if (done) Offline.watched(w.videoId)
        Home.redraw()
        if (slept) { App.toast("Sleep timer: stopped at ${clock(r.pos)}"); return }
        val next = w.nextVideoId
        if (done && next != null && Prefs.autoNext && !Sleep.stopsNext()) App.push(upNext(w, next))
    }

    private fun label(id: String): String {
        val p = id.split(':')
        val s = p.getOrNull(p.size - 2)?.toIntOrNull()
        val e = p.lastOrNull()?.toIntOrNull()
        return if (s != null && e != null) "S%02dE%02d".format(s, e) else ""
    }

    private fun upNext(w: Watch, nextId: String): Screen {
        val ids = w.videoIds
        val after = ids.getOrNull(ids.indexOf(nextId) + 1)
        val nw = Watch(w.id, w.type, w.name, nextId, w.nextLabel ?: label(nextId), w.runtime, ids, after, after?.let { label(it) })
        val title = "${w.name} ${nw.label}"
        var left = 10
        var cancelled = false
        lateinit var s: Screen
        val status = Row("")
        val shownAt = System.currentTimeMillis()
        fun tick() {
            if (cancelled || !App.isTop(s)) return
            if (Sleep.timeUp() || Sleep.stoppedSince(shownAt)) { cancelled = true; App.back(); App.toast("Sleep timer: stopped before the next episode"); return }
            if (left <= 0) { App.replaceTop(Play.best(w.type, nextId, title, nw, 0)); return }
            status.label = "Next: ${nw.label} starts in $left s"
            left--
            App.refresh()
            App.later(1_000) { tick() }
        }
        s = Screen("Up next", listOf(
            status,
            Row("Play it now", action = { cancelled = true; App.replaceTop(Play.best(w.type, nextId, title, nw, 0)) }),
            Row("Not now", action = { cancelled = true; App.back() }),
        ))
        s.onShow = { tick() }
        s.onHide = { cancelled = true }
        s.pos = 1
        return s
    }
}

/**
 * Plays through mpv (or another player set in Settings). With mpv, the app follows the position
 * through mpv's IPC socket, so resume, watched marks and next episode all work.
 */
object Player {
    class Result(val pos: Long, val dur: Long, val failed: Boolean, val why: String, val launched: Long = 0)

    @Volatile private var current: Process? = null
    @Volatile private var control: SocketChannel? = null
    @Volatile var title: String = ""
        private set

    /** Sends mpv a command (e.g. "cycle", "pause"); false if mpv isn't playing. */
    fun command(vararg args: Any): Boolean {
        val c = control ?: return false
        val json = org.json.JSONObject().put("command", org.json.JSONArray(args.toList())).toString() + "\n"
        return runCatching { synchronized(c) { c.write(ByteBuffer.wrap(json.toByteArray())) }; true }.getOrDefault(false)
    }

    /** A player is open (background look-ups wait). */
    val playing get() = current?.isAlive == true

    /** Closes the player if one is open (the sleep timer); true if there was one. */
    fun stop(note: String = "Sleep timer: stopping"): Boolean {
        val p = current?.takeIf { it.isAlive } ?: return false
        // mpv: say why on screen, then quit cleanly; other players: stop the process.
        if (command("show-text", note, 3000)) Thread { Thread.sleep(1500); command("quit"); Thread.sleep(3000); if (p.isAlive) p.destroy() }.start()
        else p.destroy()   // mpv quits cleanly on SIGTERM; the position it reported so far is kept
        return true
    }

    /** What mpv's log says went wrong, in plain words where we know it, or its own last error line. */
    private fun mpvSaid(log: File): String? {
        val lines = runCatching { log.readLines() }.getOrNull() ?: return null
        val errors = lines.filter { "][e][" in it || "][f][" in it }.map { it.substringAfter("][e][").substringAfter("][f][").substringAfter("] ").trim() }
        if (errors.isEmpty()) return null
        val all = errors.joinToString(" ").lowercase()
        // yt-dlp's own complaint, when it ran but YouTube said no.
        val ytErr = errors.lastOrNull { "ERROR:" in it }?.substringAfter("ERROR:")?.substringAfter("] ")?.trim()
        val ytdl = "youtube-dl" in all || "yt-dlp" in all || "ytdl" in all
        return when {
            ytdl && ("not found" in all || "no such file" in all || "not enough permissions" in all) && ytErr == null -> "mpv can't find yt-dlp: sudo pacman -S yt-dlp"
            ytErr != null && "unsupported url" in ytErr.lowercase() -> "it's a web page, not a video"
            ytErr != null && ("sign in" in ytErr.lowercase() || "bot" in ytErr.lowercase()) -> "YouTube wants a sign-in check (see Settings › Player options)"
            ytErr != null -> "YouTube: " + ytErr.take(90)
            "403" in all || "forbidden" in all -> "the server refused it (403): the link may have expired, try another copy"
            "404" in all || "not found" in all -> "the file wasn't there (404): try another copy"
            ytdl -> "yt-dlp couldn't get it: " + errors.last().take(80)
            "unrecognized file format" in all || "failed to recognize" in all -> "it isn't a video mpv can open"
            "certificate" in all || "tls" in all || "ssl" in all -> "a secure-connection error"
            "timed out" in all || "timeout" in all -> "the connection timed out"
            "vo" in all && "init" in all -> "mpv couldn't open a video window"
            else -> "mpv said: " + errors.last().take(90)
        }
    }

    fun play(url: String, title: String, sub: Sub?, startAt: Long, video: String = "", done: (Result) -> Unit) {
        // A locked profile at bedtime, or with today's time used up: only with the parent PIN.
        if (KidsGuard.blockedNow() != null) {
            KidsGuard.checkTime { play(url, title, sub, startAt, video, done) }
            return
        }
        // Play on a TV instead (Settings): the TV plays the link itself.
        if (Prefs.castDlna && url.startsWith("http") && !url.contains("127.0.0.1")) { App.push(Cast.screen(url, title)); done(Result(0, 0, false, "cast")); return }
        val cmd = Prefs.player
        val isMpv = File(cmd).name.startsWith("mpv")
        val sock = File(System.getProperty("java.io.tmpdir"), "stremonino-mpv-${ProcessHandle.current().pid()}.sock")
        sock.delete()
        // mpv's own log of this play, so a failure can say what actually went wrong.
        val log = File(System.getProperty("java.io.tmpdir"), "stremonino-mpv-${ProcessHandle.current().pid()}.log")
        log.delete()
        val args = mutableListOf(cmd)
        if (isMpv) {
            args += listOf("--no-terminal", "--force-window=immediate", "--input-ipc-server=${sock.path}", "--force-media-title=$title", "--save-position-on-quit=no", "--log-file=${log.path}")
            if (startAt > 0) args += "--start=${startAt / 1000}"
            if (sub != null) args += "--sub-file=${sub.url}"
        }
        args += Prefs.playerArgs.split(' ').filter { it.isNotBlank() }
        // mpv takes special headers itself, for every format (DASH too): give it the real link.
        val direct = if (isMpv) app.stremiolite.HeaderBridge.lookup(url) else null
        if (direct != null) {
            direct.second.forEach { (k, v) ->
                when {
                    k.equals("User-Agent", true) -> args += "--user-agent=$v"
                    k.equals("Referer", true) -> args += "--referrer=$v"
                    else -> args += "--http-header-fields-append=$k: $v"
                }
            }
            args += direct.first
        } else args += url
        val launched = System.currentTimeMillis()
        val proc = try {
            ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            App.toast(if (isMpv) "Couldn't start mpv. Install it: sudo pacman -S mpv" else "Couldn't start $cmd: ${e.message}")
            done(Result(0, 0, true, "player missing"))
            return
        }
        current = proc
        this.title = title
        Sleep.started(startAt, launched)
        // Bedtime or the end of today's time: the player is closed then.
        KidsGuard.leftNow()?.let { left ->
            App.later(left + 1_000) { if (current === proc && proc.isAlive) { App.toast(KidsGuard.blockedNow() ?: "Time's up for today"); proc.destroy() } }
        }
        val posMs = java.util.concurrent.atomic.AtomicLong(0)
        val durMs = java.util.concurrent.atomic.AtomicLong(0)
        if (isMpv) {
            Thread {
                // Follow time-pos and duration until mpv closes.
                var ch: SocketChannel? = null
                for (i in 0 until 50) {
                    if (!proc.isAlive) break
                    ch = runCatching {
                        SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(UnixDomainSocketAddress.of(sock.toPath())) }
                    }.getOrNull()
                    if (ch != null) break
                    Thread.sleep(200)
                }
                val c = ch ?: return@Thread
                control = c
                runCatching {
                    fun send(s: String) { synchronized(c) { c.write(ByteBuffer.wrap((s + "\n").toByteArray())) } }
                    // Skip intros: the episode's recap and intro times (TheIntroDB / IntroDB), skipped as they play.
                    val segs = if (video.isNotEmpty() && Prefs.skipIntros) runCatching { app.stremiolite.Skips.find(video) }.getOrDefault(emptyList()) else emptyList()
                    val skipped = HashSet<Long>()
                    var creditsTold = false
                    val credits = app.stremiolite.Skips.creditsAt(segs)
                    fun at(pos: Long) {
                        app.stremiolite.Skips.skipAt(segs, pos)?.takeIf { it.start !in skipped }?.let { seg ->
                            skipped += seg.start
                            send("""{"command":["seek",${seg.end / 1000.0},"absolute"]}""")
                            send("""{"command":["show-text","Skipped the ${seg.kind}",2000]}""")
                        }
                        if (credits != null && !creditsTold && pos >= credits) {
                            creditsTold = true
                            send("""{"command":["show-text","Credits: close mpv (q) for the next episode",4000]}""")
                        }
                    }
                    send("""{"command":["observe_property",1,"time-pos"]}""")
                    send("""{"command":["observe_property",2,"duration"]}""")
                    Channels.newReader(c, "UTF-8").buffered().useLines { lines ->
                        lines.forEach { line ->
                            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                            if (o.optString("event") == "property-change") {
                                when (o.optString("name")) {
                                    "time-pos" -> o.optDouble("data").takeIf { !it.isNaN() }?.let { posMs.set((it * 1000).toLong()); if (segs.isNotEmpty()) at(posMs.get()) }
                                    "duration" -> o.optDouble("data").takeIf { !it.isNaN() }?.let { durMs.set((it * 1000).toLong()) }
                                }
                            }
                        }
                    }
                }
                if (control === c) control = null
                runCatching { c.close() }
            }.apply { isDaemon = true }.start()
        }
        Thread {
            val code = proc.waitFor()
            Thread.sleep(300)
            sock.delete()
            val ran = System.currentTimeMillis() - launched
            val p = posMs.get()
            val d = durMs.get()
            val failed = isMpv && ((d in 1..119_999) || (ran < 15_000 && p < 5_000))
            val said = if (isMpv) mpvSaid(log) else null
            val why = when {
                d in 1..119_999 -> "it was a short error clip"
                said != null -> said
                code != 0 && ran < 15_000 -> "the player stopped with an error"
                else -> "it closed straight away"
            }
            if (current === proc) current = null
            KidsGuard.addTime(ran)
            val slept = Sleep.stoppedSince(launched)
            // Players other than mpv don't report a position: use the time it ran.
            val pos = if (slept && p <= 0) Sleep.reached() else p
            App.post { done(Result(pos, d, failed && !slept, why, launched)) }
        }.apply { isDaemon = true }.start()
    }
}

/** Updates from the same GitHub releases as the Android app (the Linux file is stremonino-linux.jar). */
object Updates {
    private const val LATEST = "https://api.github.com/repos/shaz-arch/stremio-lite-/releases/latest"
    private const val JAR = "https://github.com/shaz-arch/stremio-lite-/releases/latest/download/stremonino-linux.jar"

    val current: Int by lazy {
        runCatching { Updates::class.java.getResource("/stremonino-version.txt")!!.readText().trim().toInt() }.getOrDefault(0)
    }
    var available: Int? = null
        private set
    /** A newer build already downloaded over this one: restarting starts it. */
    var installed: Int? = null
        private set
    private var lastCheck = 0L
    private var scheduled = false
    private val listeners = mutableListOf<() -> Unit>()

    private fun latest(): Int? = JSONObject(Http.get(LATEST)).optString("tag_name").removePrefix("build-").toIntOrNull()

    /** Checks now and then every 30 minutes, whichever screen is open. [done] runs when an update turns up. */
    fun check(done: () -> Unit) {
        if (done !in listeners) listeners += done
        if (!scheduled) {
            scheduled = true
            fun tick() { checkQuietly(); App.later(30 * 60_000L) { tick() } }
            tick()
        }
    }

    private fun checkQuietly() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 25 * 60_000) return
        lastCheck = now
        App.async({ latest() }) { r ->
            val n = r.getOrNull() ?: return@async
            if (n > current && current > 0 && n != available && n != installed) {
                available = n
                App.badge = "⬆ build $n"
                App.toast("Update available: build $n. It's at the top of Home: Enter installs it", 12_000)
                listeners.forEach { it() }
            }
        }
    }

    fun checkNow() {
        App.toast("Checking for updates…")
        App.async({ latest() }) { r ->
            val n = r.getOrNull()
            when {
                n == null -> App.toast("Couldn't check: ${r.exceptionOrNull()?.let { Net.describe(it) } ?: "no answer"}")
                n == installed -> restartRow(n)
                n <= current -> App.toast("You're on the latest build ($current).")
                else -> install(n)
            }
        }
    }

    /** Home's top line while an update is waiting or downloaded. */
    fun homeRow(): Row? {
        installed?.let { n -> return Row("Build $n is installed. Enter restarts into it", "►", action = { restart() }) }
        available?.let { n -> return Row("Update available: build $n. Enter installs it", "►", action = { install(n) }) }
        return null
    }

    private fun restartRow(n: Int) = App.toast("Build $n is installed: choose the top line on Home to restart into it", 8_000)

    /** Closes the app and starts the new build in this terminal. */
    fun restart() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val me = runCatching { File(Updates::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return
        App.afterExit = {
            val code = runCatching { ProcessBuilder(java, "-jar", me.path).inheritIO().start().waitFor() }.getOrDefault(0)
            kotlin.system.exitProcess(code)
        }
        App.quit()
    }

    /** Downloads the new jar over the running one; it takes effect next time the app starts. */
    private fun install(n: Int) {
        val me = runCatching { File(Updates::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        if (me == null || !me.isFile || !me.name.endsWith(".jar")) {
            App.toast("Build $n is out: download stremonino-linux.jar from the releases page")
            return
        }
        App.toast("Downloading build $n…")
        App.async({
            val tmp = File(me.parentFile, me.name + ".new")
            tmp.writeBytes(Http.getBytes(JAR, timeoutMs = 60_000))
            if (tmp.length() < 100_000) throw IllegalStateException("download looks incomplete")
            if (!tmp.renameTo(me)) throw IllegalStateException("couldn't replace ${me.name}")
        }) { r ->
            r.onSuccess {
                installed = n; available = null
                App.badge = "⬆ restart for build $n"
                App.toast("Build $n downloaded. Enter on Home's top line restarts into it", 10_000)
                listeners.forEach { it() }
            }
                .onFailure { App.toast("Update failed: ${Net.describe(it)}") }
        }
    }
}
