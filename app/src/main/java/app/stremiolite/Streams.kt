package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Streams for a title: asked of every stream add-on that handles it at once (about 10 seconds each).
 *
 * Two ways in:
 *  - [screen]: the full list, best quality first; OK plays the highlighted one.
 *  - [playBest]: waits for the answers, scores every stream ([Best]) and plays the winner,
 *    showing what it picked so you can go back and choose another.
 *
 * Shown: streams with a web link (what debrid services return).
 * Hidden: plain torrents (no streaming server here) and links that need special
 * headers (an external player can't send them).
 */
object Streams {
    private const val TIMEOUT_MS = 10_000

    private fun bingePrefs(c: android.content.Context) = Profiles.prefsFor(c, "binge")

    fun lastFor(c: android.content.Context, showId: String): Pref? {
        val v = bingePrefs(c).getString(showId, null) ?: return null
        val p = v.split('|')
        return if (p.size >= 3) Pref(p[0], p[1], p[2]) else null
    }

    private fun remember(c: android.content.Context, showId: String, s: Stream) {
        val prefs = bingePrefs(c)
        val e = prefs.edit().putString(showId, "${s.group}|${s.quality}|${s.source}")
        // Keep it small: the 40 most recent shows.
        val all = prefs.all.keys.filter { it != showId }
        if (all.size > 40) all.take(all.size - 40).forEach { e.remove(it) }
        e.apply()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- fetching early --------------------------------------------------------------------

    private const val FRESH_MS = 5 * 60_000L
    private val early = LinkedHashMap<String, Fetch>()

    /** Starts asking for a title's streams (and subtitles) before you press play. */
    fun prefetch(app: MainActivity, type: String, id: String) {
        val key = "$type/$id"
        val have = early[key]
        if (have != null && System.currentTimeMillis() - have.started < FRESH_MS) return
        early.remove(key)
        early[key] = Fetch(type, id)
        while (early.size > 3) early.remove(early.keys.first())
        Subtitles.prefetch(app, type, id)
        if (type == "series" && ':' in id && Settings.skipIntros(app)) Net.async({ Skips.find(id) }) { }
    }

    /**
     * A recent early fetch for this title if it can be trusted, or a fresh one: reused only if it's
     * under 90 seconds old and still running, or finished with every source answering and results.
     */
    private fun fetchFor(type: String, id: String): Fetch {
        val key = "$type/$id"
        early[key]?.let { f ->
            val young = System.currentTimeMillis() - f.started < REUSE_MS
            val healthy = !f.done || (f.notes.isEmpty() && f.found.isNotEmpty())
            if (young && healthy) return f
        }
        return Fetch(type, id).also { early[key] = it }
    }

    private const val REUSE_MS = 90_000L

    /** One round of asking the stream add-ons; every listener runs on the main thread after each answer. */
    class Fetch(type: String, id: String) {
        val started = System.currentTimeMillis()
        val listeners = mutableListOf<() -> Unit>()
        private fun onChange() = listeners.toList().forEach { it() }

        private val tiers = StreamParse.tiers(Home.allAddons().filter { it.supports("stream", type, id) })
        /** The add-ons asked so far: AIOStreams, plus the fail-safes once they're needed. */
        val addons = tiers.first.toMutableList()
        val found = mutableListOf<Stream>()
        val notes = mutableListOf<String>()
        var answered = 0
        var hiddenTorrent = 0
        var hiddenHeaders = 0
        /** AIOStreams gave nothing playable, so the fail-safes were asked. */
        var usingFailsafe = false
        /** Web pages rather than videos ("open in browser", ratings pages): shown as links, never played. */
        val links = mutableListOf<Pair<String, String>>()
        val done: Boolean get() = answered >= addons.size
        private var timed = false
        private val type = type
        private val id = id

        private fun ask(list: List<Addon>) {
            list.forEach { a ->
                val url = a.base + "/stream/" + enc(type) + "/" + enc(id) + ".json"
                Net.async({ JSONObject(Net.get(url, timeoutMs = TIMEOUT_MS)) }) { r ->
                    answered++
                    r.onSuccess { json ->
                        val arr = json.optJSONArray("streams")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val hints = o.optJSONObject("behaviorHints")
                            val link = o.optString("url")
                            val web = StreamParse.webLink(o, a)
                            when {
                                web != null -> if (links.none { it.second == web.second }) links += web
                                link.isBlank() || !link.startsWith("http") ->
                                    if (o.has("infoHash")) hiddenTorrent++
                                else -> StreamParse.addUnique(found, StreamParse.parse(o, link, shortName(a.name), found.size))
                            }
                        }
                    }.onFailure { e ->
                        notes += "${shortName(a.name)}: ${Net.describe(e)}"
                    }
                    // AIOStreams has answered with nothing playable: ask the fail-safes now.
                    if (done && !usingFailsafe && tiers.second.isNotEmpty() && !StreamParse.hasPlayable(found)) {
                        usingFailsafe = true
                        addons += tiers.second
                        ask(tiers.second)
                    }
                    if (done && !timed) { timed = true; Timings.record("Finding streams", System.currentTimeMillis() - started) }
                    onChange()
                }
            }
        }

        init {
            ask(addons)
            if (addons.isEmpty()) Net.later(0, Runnable { onChange() })
        }

        /** "AIOStreams gave nothing: showing your fail-safes (Torrentio)". */
        fun failsafeLine(): String? = if (!usingFailsafe) null
            else tiers.first.joinToString(", ") { shortName(it.name) } + " gave nothing: showing your fail-safes (" + tiers.second.joinToString(", ") { shortName(it.name) } + ")"

        fun listen(l: () -> Unit): Fetch {
            listeners += l
            return this
        }

        fun hiddenLine(): String? {
            val hidden = mutableListOf<String>()
            if (hiddenTorrent > 0) hidden += "$hiddenTorrent torrent-only"
            if (hiddenHeaders > 0) hidden += "$hiddenHeaders need special headers"
            return if (hidden.isEmpty()) null else "Hidden: " + hidden.joinToString(", ")
        }
    }

    /** Subtitles for the title, picked automatically in the chosen language unless the user picks. */
    private class SubPick(app: MainActivity, type: String, id: String, onChange: () -> Unit) {
        val lang = Settings.subtitleLanguage(app)
        var subs: List<Sub> = emptyList()
        var done = lang.codes.isEmpty()
        var chosen: Sub? = null
        var userPicked = false

        init {
            if (!done) {
                val f = Subtitles.get(app, type, id)
                val apply = {
                    subs = f.found.toList()
                    done = f.done
                    if (!userPicked) chosen = subs.firstOrNull { Subtitles.matches(it, lang) }
                }
                apply()
                f.listen { apply(); onChange() }
            }
        }

        fun line(): String {
            val c = chosen
            return when {
                lang.codes.isEmpty() -> "off (change in Settings)"
                !done && subs.isEmpty() -> "looking…"
                c != null -> Subtitles.languageName(c.lang) + " · " + c.addon + "  (${subs.size} found)  ►"
                subs.isEmpty() -> "none found"
                else -> "off  (${subs.size} found)  ►"
            }
        }

        fun picker(app: MainActivity, after: () -> Unit): Screen {
            val pick = mutableListOf(Row("Off", action = { chosen = null; userPicked = true; app.back(); after() }))
            subs.sortedBy { if (Subtitles.matches(it, lang)) 0 else 1 }.forEach { sub ->
                pick += Row(Subtitles.languageName(sub.lang), sub.addon + if (sub === chosen) "  ✓" else "", action = {
                    chosen = sub; userPicked = true; app.back(); after()
                })
            }
            return Screen("Subtitles", pick)
        }
    }

    // ---- the full list ------------------------------------------------------------------------

    fun screen(app: MainActivity, type: String, id: String, title: String, watch: History.Watch? = null, startAt: Long = 0): Screen {
        val summary = Row("")
        lateinit var screen: Screen
        lateinit var fetch: Fetch
        lateinit var subs: SubPick
        lateinit var rebuild: () -> Unit
        val subRow = Row("Subtitles", opens = { subs.picker(app) { rebuild() } })
        var started = false
        var checking = false
        val broken = mutableSetOf<Stream>()

        rebuild = {
            summary.label = when {
                fetch.addons.isEmpty() -> "None of your add-ons offers streams for this title."
                !fetch.done -> "Asking ${fetch.addons.size} add-ons… ${fetch.answered} answered, ${fetch.found.size} streams so far"
                fetch.found.isEmpty() -> "No playable streams found."
                else -> "${fetch.found.size} streams from ${fetch.addons.size} add-ons"
            }
            subRow.detail = subs.line()
            // In Play best's order: its score for this device first (★ = what it would pick), then the rest.
            val mins = watch?.runtime?.let { Best.minutes(it) } ?: if (type == "movie") 110.0 else 45.0
            val scores = fetch.found.associateWith { Best.score(it, mins) }
            val sorted = fetch.found.sortedWith(
                compareBy<Stream>({ if (scores[it] != null) 0 else 1 }, { -(scores[it] ?: 0.0) }, { if (it.cached == false) 1 else 0 }, { -it.rank }, { it.order })
            )
            val star = sorted.firstOrNull { scores[it] != null && it !in broken }
            val rows = mutableListOf(summary, subRow)
            fetch.failsafeLine()?.let { rows += Row(it) }
            fetch.links.forEach { (label, url) -> rows += LinkOut.row(app, "↗ $label", label, url) }
            fetch.hiddenLine()?.let { rows += Row(it) }
            fetch.notes.forEach { rows += Row(it) }
            rows += Row("────────────────────")
            // Once all answers are in, quietly check what the top few links really serve.
            if (fetch.done && !checking) {
                checking = true
                fetch.found.map { it to Best.score(it, 45.0) }.filter { it.second != null }.sortedByDescending { it.second }.take(5).forEach { (st, _) ->
                    Net.async({ StreamCheck.broken(st.url) }) { r -> if (r.getOrDefault(false)) { broken += st; if (app.isTop(screen)) rebuild() } }
                }
            }
            sorted.forEach { s ->
                val detail = (if (s in broken) "✗ no video · " else "") + (if (s.cached == false) "not cached · " else "") + (if (s.langs.isNotEmpty()) s.langs + " · " else "") + s.addon
                rows += Row((if (s === star) "★ " else "") + s.summary, detail, action = {
                    app.toast("Checking the stream…")
                    Net.async({ StreamCheck.broken(s.url) }) { r ->
                        if (r.getOrDefault(false)) app.toast("That release has no playable video. Pick another.")
                        else Player.play(app, s.url, title, listOfNotNull(subs.chosen), watch = watch, startAt = freshStart(app, watch, startAt))
                    }
                })
            }
            val firstStream = rows.size - sorted.size
            val wasOnHeader = app.cursor() < firstStream
            screen.rows = rows
            // Keep the cursor on the first stream while results are still arriving.
            if (wasOnHeader && sorted.isNotEmpty()) screen.moveTo = firstStream
            app.refresh(screen)
        }

        screen = Screen(title, listOf(summary))
        screen.onShow = {
            if (!started) {
                started = true
                subs = SubPick(app, type, id) { rebuild() }
                fetch = fetchFor(type, id).listen { rebuild() }
                rebuild()
            }
        }
        return screen
    }

    // ---- play the best one ----------------------------------------------------------------------

    /**
     * Waits for the add-ons (and subtitles), picks the best stream for an old 1080p stick and plays it.
     * [runtime] is the title's running time text ("1h 52m", "45min"), used to work out bitrates.
     */
    fun playBest(app: MainActivity, type: String, id: String, title: String, runtime: String, watch: History.Watch? = null, startAt: Long = 0): Screen {
        if (type == "series" && ':' in id && Settings.skipIntros(app) && Skips.known(id) == null) Net.async({ Skips.find(id) }) { }
        val status = Row("")
        val pickRow = Row("")
        lateinit var screen: Screen
        lateinit var fetch: Fetch
        lateinit var subs: SubPick
        var started = false
        var active = false
        var played = false
        var picked: Stream? = null
        val tried = mutableSetOf<Stream>()
        var askedAt = 0L
        val minutes = Best.minutes(runtime) ?: if (type == "movie") 110.0 else 45.0
        // Episodes: prefer the same release as the last episode played (steady quality, subtitles in sync).
        val showId = watch?.id?.takeIf { type == "series" }
        val prefer = showId?.let { lastFor(app, it) }

        fun rows(): List<Row> {
            val list = mutableListOf(status)
            if (picked != null) list += pickRow
            if (fetch.done) list += Row("Why this stream?", "►", opens = { why(fetch, minutes, picked, tried) })
            list += Row("────────────────────")
            if (picked != null) list += Row("Play it again", action = {
                picked?.let { Player.play(app, it.url, title, listOfNotNull(subs.chosen), watch = watch, startAt = freshStart(app, watch, startAt)) }
            })
            list += Row("Choose a different stream", opens = { screen(app, type, id, title, watch, startAt) })
            return list
        }

        lateinit var retry: () -> Boolean
        lateinit var start: Runnable
        start = Runnable {
            val s = picked
            if (s != null && active && !played) {
                played = true
                tried += s
                // Check what the link really serves first: a broken release gives a tiny error clip.
                Net.async({ StreamCheck.broken(s.url) }) { r ->
                    if (!active || picked !== s) return@async
                    if (r.getOrDefault(false)) {
                        val next = if (tried.size >= 6) null else Best.pick(fetch.found.filter { it !in tried }, minutes, prefer)
                        if (next == null) {
                            status.label = "That release has no playable video, and there's no other good one. Choose below."
                            if (type == "movie" && NextList.has(app, id)) {
                                Waiting.unplayable(app, id, title)
                                status.label += " It's back in Waiting until one plays."
                            }
                            screen.rows = rows(); app.refresh(screen)
                        } else {
                            picked = next
                            played = false
                            status.label = "Skipped a broken release (no playable video). Trying the next one…"
                            pickRow.label = next.summary
                            pickRow.detail = next.addon
                            screen.rows = rows(); screen.moveTo = 0; app.refresh(screen)
                            Net.later(300, start)
                        }
                        return@async
                    }
                    showId?.let { remember(app, it, s) }
                    app.onPlayerFailed = { retry() }
                    Player.play(app, s.url, title, listOfNotNull(subs.chosen), watch = watch, startAt = freshStart(app, watch, startAt))
                }
            }
        }

        // The stream died straight away: count down to the next-best one (up to 3 more tries).
        retry = {
            val next = if (tried.size >= 4) null else Best.pick(fetch.found.filter { it !in tried }, minutes, prefer)
            if (next == null) {
                status.label = "That stream didn't start, and there's no other good one. Choose below."
                screen.rows = rows()
                app.refresh(screen)
                false
            } else {
                picked = next
                played = false
                status.label = "That stream didn't start. Trying the next one in 3 seconds… (Back to stop)"
                pickRow.label = next.summary
                pickRow.detail = next.addon
                screen.rows = rows()
                screen.moveTo = 0
                app.refresh(screen)
                Net.later(3_000, start)
                true
            }
        }

        fun update() {
            if (picked != null) return
            // Subtitles: go as soon as one in your language has arrived (the same one that would be
            // chosen anyway); if none has yet, wait for every subtitle add-on to answer, as before.
            val waitingSubs = !subs.done && subs.chosen == null
            if (!fetch.done || waitingSubs) {
                status.label = "Finding the best stream… ${fetch.answered} of ${fetch.addons.size} add-ons, ${fetch.found.size} streams" +
                    if (fetch.done && waitingSubs) " · getting subtitles" else ""
                screen.rows = rows()
                app.refresh(screen)
                return
            }
            val best = Best.pick(fetch.found, minutes, prefer)
            if (best == null) {
                status.label = if (fetch.found.isEmpty()) "No playable streams found." else "No stream suits this device. Choose one below."
                val r = rows().toMutableList()
                if (type == "movie" && !Waiting.has(app, id)) r.add(1, Row("Tell me when a stream is available", action = {
                    Waiting.add(app, Waiting.Entry(id, type, title, "", 0))
                    app.toast("Added to Waiting")
                    app.back()
                }))
                screen.rows = r
                screen.moveTo = r.lastIndex
                app.refresh(screen)
                return
            }
            picked = best
            val sub = subs.chosen?.let { " · subtitles " + Subtitles.languageName(it.lang) } ?: ""
            val from = if (startAt > 0) " from ${History.clock(startAt)}" else ""
            val took = (System.currentTimeMillis() - (if (askedAt > 0) askedAt else fetch.started)) / 100 / 10.0
            status.label = "Found in $took s (${fetch.found.size} streams). Playing$from…$sub"
            pickRow.label = best.summary
            pickRow.detail = best.addon
            screen.rows = rows()
            screen.moveTo = 0
            app.refresh(screen)
            // A short pause so you can read the pick, and press Back if it's wrong.
            Net.later(1_000, start)
        }

        screen = Screen("Play: $title", listOf(status))
        screen.onShow = {
            active = true
            if (!started) {
                started = true
                askedAt = System.currentTimeMillis()
                subs = SubPick(app, type, id) { update() }
                fetch = fetchFor(type, id).listen { update() }
                // An early fetch started before Play best was pressed: count from when it began.
                askedAt = minOf(askedAt, fetch.started)
                update()
            } else if (played) {
                status.label = "Played. Press OK below to play again or choose another."
                app.refresh(screen)
            }
        }
        screen.onHide = {
            active = false
            Net.cancel(start)
            // Left before it started (Back during the pause): don't play it later.
            if (picked != null && !played) played = true
        }
        return screen
    }

    /** When resuming, pick up from the latest saved position (it moves on each time you watch a bit more). */
    private fun freshStart(app: MainActivity, watch: History.Watch?, startAt: Long): Long =
        if (startAt > 0 && watch != null) History.resumeAt(app, watch.videoId).takeIf { it > 0 } ?: startAt else startAt

    /** Every stream Play best considered, best score first, and why any were left out. */
    private fun why(fetch: Fetch, minutes: Double, picked: Stream?, tried: Set<Stream>): Screen {
        val scored = fetch.found.map { s -> s to Best.score(s, minutes) }
        val rows = mutableListOf<Row>()
        rows += Row("${fetch.found.size} streams from ${fetch.addons.size} add-ons, in ${(System.currentTimeMillis() - fetch.started) / 1000} s")
        fetch.notes.forEach { rows += Row("Problem: $it") }
        fetch.hiddenLine()?.let { rows += Row(it) }
        rows += Row("──── Score · stream ────")
        scored.filter { it.second != null }.sortedByDescending { it.second }.forEach { (s, sc) ->
            val mark = when {
                s === picked -> "  ◄ picked"
                s in tried -> "  ✗ failed"
                else -> ""
            }
            rows += Row("${sc!!.toInt().toString().padStart(4)}  ${s.summary}", s.addon + mark)
        }
        val out = scored.filter { it.second == null }
        if (out.isNotEmpty()) {
            rows += Row("──── Left out ────")
            out.forEach { (s, _) -> rows += Row("      ${s.summary}", Best.whyOut(s)) }
        }
        return Screen("Why this stream?", rows)
    }

    private fun shortName(name: String) = name.substringBefore('|').trim().ifEmpty { name }.take(16)


}

