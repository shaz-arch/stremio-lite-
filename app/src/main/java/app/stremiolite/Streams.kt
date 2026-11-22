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

    class Stream(
        val url: String,
        val addon: String,
        val quality: String,
        val tags: String,
        val size: String,
        val sizeMb: Double,
        val provider: String,
        val cached: Boolean?,
        val rank: Int,
        val order: Int,
        /** Everything the add-on said about it, upper-cased, for scoring. */
        val text: String,
    ) {
        val summary: String
            get() = listOf(quality.padEnd(5), tags, size, provider).filter { it.isNotBlank() }.joinToString("  ")
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
    }

    /** A recent early fetch for this title, or a new one. */
    private fun fetchFor(type: String, id: String): Fetch {
        val key = "$type/$id"
        early[key]?.let { if (System.currentTimeMillis() - it.started < FRESH_MS) return it }
        return Fetch(type, id).also { early[key] = it }
    }

    /** One round of asking the stream add-ons; every listener runs on the main thread after each answer. */
    class Fetch(type: String, id: String) {
        val started = System.currentTimeMillis()
        val listeners = mutableListOf<() -> Unit>()
        private fun onChange() = listeners.toList().forEach { it() }

        val addons = Home.allAddons().filter { it.supports("stream", type, id) }
        val found = mutableListOf<Stream>()
        val notes = mutableListOf<String>()
        var answered = 0
        var hiddenTorrent = 0
        var hiddenHeaders = 0
        val done: Boolean get() = answered >= addons.size

        init {
            addons.forEach { a ->
                val url = a.base + "/stream/" + enc(type) + "/" + enc(id) + ".json"
                Net.async({ JSONObject(Net.get(url, timeoutMs = TIMEOUT_MS)) }) { r ->
                    answered++
                    r.onSuccess { json ->
                        val arr = json.optJSONArray("streams")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val hints = o.optJSONObject("behaviorHints")
                            val link = o.optString("url")
                            when {
                                link.isBlank() || !link.startsWith("http") ->
                                    if (o.has("infoHash")) hiddenTorrent++
                                hints?.optJSONObject("proxyHeaders") != null -> hiddenHeaders++
                                else -> found.add(parse(o, link, shortName(a.name), found.size))
                            }
                        }
                    }.onFailure { e ->
                        notes += "${shortName(a.name)}: ${Net.describe(e)}"
                    }
                    onChange()
                }
            }
            if (addons.isEmpty()) Net.later(0, Runnable { onChange() })
        }

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
        var subs: List<Subtitles.Sub> = emptyList()
        var done = lang.codes.isEmpty()
        var chosen: Subtitles.Sub? = null
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

        rebuild = {
            summary.label = when {
                fetch.addons.isEmpty() -> "None of your add-ons offers streams for this title."
                !fetch.done -> "Asking ${fetch.addons.size} add-ons… ${fetch.answered} answered, ${fetch.found.size} streams so far"
                fetch.found.isEmpty() -> "No playable streams found."
                else -> "${fetch.found.size} streams from ${fetch.addons.size} add-ons"
            }
            subRow.detail = subs.line()
            val sorted = fetch.found.sortedWith(
                compareBy<Stream>({ if (it.cached == false) 1 else 0 }, { -it.rank }, { it.order })
            )
            val rows = mutableListOf(summary, subRow)
            fetch.hiddenLine()?.let { rows += Row(it) }
            fetch.notes.forEach { rows += Row(it) }
            rows += Row("────────────────────")
            sorted.forEach { s ->
                val detail = (if (s.cached == false) "not cached · " else "") + s.addon
                rows += Row(s.summary, detail, action = { Player.play(app, s.url, title, listOfNotNull(subs.chosen), watch = watch, startAt = freshStart(app, watch, startAt)) })
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
        var subsGraceStarted = false
        var subsGraceOver = false
        val minutes = Best.minutes(runtime) ?: if (type == "movie") 110.0 else 45.0

        fun rows(): List<Row> {
            val list = mutableListOf(status)
            if (picked != null) list += pickRow
            list += Row("────────────────────")
            if (picked != null) list += Row("Play it again", action = {
                picked?.let { Player.play(app, it.url, title, listOfNotNull(subs.chosen), watch = watch, startAt = freshStart(app, watch, startAt)) }
            })
            list += Row("Choose a different stream", opens = { screen(app, type, id, title, watch, startAt) })
            return list
        }

        lateinit var retry: () -> Boolean
        val start = Runnable {
            val s = picked
            if (s != null && active && !played) {
                played = true
                tried += s
                app.onPlayerFailed = { retry() }
                Player.play(app, s.url, title, listOfNotNull(subs.chosen), watch = watch, startAt = freshStart(app, watch, startAt))
            }
        }

        // The stream died straight away: count down to the next-best one (up to 3 more tries).
        retry = {
            val next = if (tried.size >= 4) null else Best.pick(fetch.found.filter { it !in tried }, minutes)
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
            // Once the streams are in, give subtitles one more second at most.
            if (fetch.done && !subs.done && !subsGraceStarted) {
                subsGraceStarted = true
                Net.later(1_000, Runnable { subsGraceOver = true; update() })
            }
            val waitingSubs = !subs.done && !subsGraceOver
            if (!fetch.done || waitingSubs) {
                status.label = "Finding the best stream… ${fetch.answered} of ${fetch.addons.size} add-ons, ${fetch.found.size} streams" +
                    if (fetch.done && waitingSubs) " · getting subtitles" else ""
                screen.rows = rows()
                app.refresh(screen)
                return
            }
            val best = Best.pick(fetch.found, minutes)
            if (best == null) {
                status.label = if (fetch.found.isEmpty()) "No playable streams found." else "No stream suits this device. Choose one below."
                val r = rows()
                screen.rows = r
                screen.moveTo = r.lastIndex
                app.refresh(screen)
                return
            }
            picked = best
            val sub = subs.chosen?.let { " · subtitles " + Subtitles.languageName(it.lang) } ?: ""
            val from = if (startAt > 0) " from ${History.clock(startAt)}" else ""
            val took = (System.currentTimeMillis() - (if (askedAt > 0) askedAt else fetch.started)) / 100 / 10.0
            status.label = "Found in $took s. Playing$from…$sub"
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

    private fun shortName(name: String) = name.substringBefore('|').trim().ifEmpty { name }.take(16)

    private val sizeRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB|GiB|MiB)""", RegexOption.IGNORE_CASE)

    private fun parse(o: JSONObject, url: String, addon: String, order: Int): Stream {
        val name = o.optString("name")
        val hints = o.optJSONObject("behaviorHints")
        val text = name + " " + o.optString("title") + " " + o.optString("description") + " " + (hints?.optString("filename") ?: "")
        val up = text.uppercase()

        val (quality, rank) = when {
            "2160" in up || "4K" in up || "UHD" in up -> "4K" to 4
            "1080" in up -> "1080p" to 3
            "720" in up -> "720p" to 2
            "480" in up || "SD" in up.split(Regex("[^A-Z0-9]")) -> "480p" to 1
            else -> "?" to 0
        }
        val tags = buildList {
            if (Regex("""\bDV\b|DOLBY ?VISION|\bDOVI\b""").containsMatchIn(up)) add("DV")
            if ("HDR" in up) add("HDR")
            if ("HEVC" in up || "X265" in up || "H265" in up || "H.265" in up) add("HEVC")
            when {
                "REMUX" in up -> add("REMUX")
                "BLURAY" in up || "BLU-RAY" in up || "BDRIP" in up || "BRRIP" in up -> add("BluRay")
                "WEB-DL" in up || "WEBDL" in up -> add("WEB-DL")
                "WEBRIP" in up -> add("WEBRip")
                "HDTV" in up -> add("HDTV")
                Best.isCam(up) -> add("CAM")
            }
        }.joinToString(" ")

        val bytes = hints?.optLong("videoSize", 0L) ?: 0L
        val sizeMb = when {
            bytes > 0 -> bytes / 1_048_576.0
            else -> sizeRegex.find(text)?.let { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                if (m.groupValues[2].uppercase().startsWith("G")) v * 1024 else v
            } ?: 0.0
        }
        val size = when {
            sizeMb >= 1024 -> "%.1f GB".format(sizeMb / 1024)
            sizeMb > 0 -> "%.0f MB".format(sizeMb)
            else -> ""
        }

        // Debrid tag such as [RD+] / [TB+] (cached) or [TB download] (not cached yet).
        val bracket = Regex("""\[([A-Za-z]{2,4})(\+| download)?[^\]]*]""").find(name)
        val provider = bracket?.let { "[" + it.groupValues[1].uppercase() + (if (it.groupValues[2] == "+") "+" else "") + "]" } ?: ""
        val lowerName = name.lowercase()
        val cached = when {
            bracket?.groupValues?.get(2) == "+" || "⚡" in name -> true
            bracket?.groupValues?.get(2) == " download" || "⏳" in name || "uncached" in lowerName -> false
            else -> null
        }
        return Stream(url, addon, quality, tags, size, sizeMb, provider, cached, rank, order, up)
    }
}

/**
 * Scores streams for a 1080p stick playing through VLC over Wi-Fi, after Stremio Origin's approach:
 * read each stream's own text, leave out what won't play, prefer the best picture the device shows,
 * a good source, English (or multi-language) audio, and a bitrate Wi-Fi carries comfortably.
 */
object Best {
    private const val MAX_MBPS = 30.0   // above this an old stick on Wi-Fi tends to buffer

    private val camWords = Regex("""\b(CAM|CAMRIP|HDCAM|TS|HDTS|TELESYNC|TC|TELECINE|SCR|SCREENER|DVDSCR)\b""")
    fun isCam(up: String) = camWords.containsMatchIn(up)

    private val englishTags = Regex("""\b(ENG|ENGLISH)\b|🇬🇧|🇺🇸""")
    private val multiTags = Regex("""\b(MULTI|DUAL|DUAL[ -]AUDIO)\b""")
    private val otherTags = Regex(
        """\b(ITA|ITALIAN|FRE|FRENCH|VFF|VFQ|VF|TRUEFRENCH|SPA|SPANISH|ESP|CASTELLANO|LATINO|GER|GERMAN|DEU|RUS|RUSSIAN|""" +
            """POR|PORTUGUESE|PT-BR|POL|POLISH|TUR|TURKISH|HIN|HINDI|TAM|TAMIL|TEL|TELUGU|KOR|KOREAN|JPN|JAPANESE|""" +
            """CHI|CHINESE|ARA|ARABIC|DUT|DUTCH|NLD)\b|🇮🇹|🇫🇷|🇪🇸|🇲🇽|🇩🇪|🇷🇺|🇵🇹|🇧🇷|🇵🇱|🇹🇷|🇮🇳|🇰🇷|🇯🇵|🇨🇳|🇳🇱"""
    )

    /** "2h37min", "1h 52m", "45min", "110" → minutes. */
    fun minutes(runtime: String): Double? {
        val t = runtime.lowercase()
        val h = Regex("""(\d+)\s*h""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val m = Regex("""(\d+)\s*m""").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: if (h == 0.0) t.trim().toDoubleOrNull() else null
        val total = h * 60 + (m ?: 0.0)
        return if (total in 5.0..600.0) total else null
    }

    /** A score, or null when the stream shouldn't be auto-played at all. */
    fun score(s: Streams.Stream, minutes: Double): Double? {
        val up = s.text
        if (isCam(up)) return null
        if (s.cached == false) return null                            // debrid file not ready yet
        if (s.rank >= 4) return null                                  // 4K: above a 1080p stick
        if (Regex("""\bHI10P?\b""").containsMatchIn(up)) return null  // 10-bit H.264: no hardware decoder

        var sc = when (s.rank) { 3 -> 300.0; 2 -> 200.0; 1 -> 80.0; else -> 160.0 }
        sc += when {
            "REMUX" in s.tags -> 30.0
            "BluRay" in s.tags -> 24.0
            "WEB-DL" in s.tags || "WEBRip" in s.tags -> 20.0
            "HDTV" in s.tags -> 4.0
            else -> 0.0
        }
        if ("DV" in s.tags) sc -= if ("HDR" in s.tags) 20.0 else 60.0   // Dolby Vision-only files show wrong colours
        else if ("HDR" in s.tags) sc -= 10.0                             // tone-mapped on an SDR screen
        if (Regex("""\bAV1\b""").containsMatchIn(up)) sc -= 200.0
        sc += when {
            "TRUEHD" in up || Regex("""\bDTS""").containsMatchIn(up) -> -20.0
            "EAC3" in up || "DDP" in up || "DD+" in up -> 14.0
            "AC3" in up || Regex("""\bDD\b""").containsMatchIn(up) -> 12.0
            "AAC" in up -> 8.0
            else -> 0.0
        }

        val eng = englishTags.containsMatchIn(up)
        val multi = multiTags.containsMatchIn(up)
        val other = otherTags.containsMatchIn(up)
        sc += when {
            eng -> 30.0
            multi -> -10.0
            other -> -160.0       // only another language named: last resort
            else -> 0.0           // nothing named: usually the original
        }

        if (s.cached == true) sc += 10.0
        if (s.sizeMb > 0) {
            val mbps = s.sizeMb * 8 / (minutes * 60)
            if (mbps > MAX_MBPS) sc -= 80.0 + (mbps - MAX_MBPS) * 2
            else sc += minOf(18.0, mbps / 6.0 * 8)          // more bitrate for the same picture: better
        }
        return sc
    }

    fun pick(streams: List<Streams.Stream>, minutes: Double): Streams.Stream? =
        streams.mapNotNull { s -> score(s, minutes)?.let { s to it } }
            .maxWithOrNull(compareBy<Pair<Streams.Stream, Double>>({ it.second }, { -it.first.order }))
            ?.first
}
