package app.stremiolite.linux

import app.stremiolite.Guide
import app.stremiolite.LiveTv

/** Live TV: the broadcasters' own free channels, checked when the list opens, and your playlists. */
object Live {
    fun homeDetail(): String {
        val n = Prefs.playlists.size
        return if (n == 0) "►" else "$n playlist${if (n == 1) "" else "s"}  ►"
    }

    fun screen(): Screen {
        lateinit var screen: Screen
        var groups: List<LiveTv.Group>? = null
        var error: String? = null
        var guides: () -> Unit = {}

        fun build() {
            val rows = mutableListOf<Row>()
            val list = groups
            when {
                list == null && error != null -> rows += Row("Couldn't load the channel list: $error")
                list == null -> rows += Row("Loading channels…")
            }
            val favs = Prefs.favourites
            if (favs.isNotEmpty()) {
                rows += Row("──── Favourites ────")
                favs.forEach { c -> rows += channelRow(c, c.group, favs) }
                if (favs.size > 1) rows += Row("Arrange favourites", "►", opens = { arrange() })
            }
            val off = mutableListOf<LiveTv.Channel>()
            list?.forEach { g ->
                val shown = g.channels.filter { LiveTv.known(it) != "" }
                off += g.channels.filter { LiveTv.known(it) == "" }
                if (shown.isEmpty()) return@forEach
                rows += Row("──── ${g.name} ────")
                shown.forEach { c -> rows += channelRow(c, "Live TV · " + g.name, favs) }
            }
            if (off.isNotEmpty()) rows += Row("Off air right now", "${off.size}  ►", opens = { offAir(off) })
            rows += Row("──── Your playlists ────")
            Prefs.playlists.forEach { p -> rows += Row(p.name, "►", opens = { playlist(p) }) }
            rows += Row("Add a playlist (M3U link)", "►", action = { addPlaylist { build() } })
            rows += Row("──── TV guide ────")
            val yours = Prefs.liveGuide
            rows += Row("Your TV guide link (XMLTV)", if (yours.isEmpty()) "none  ►" else guideState(yours) + "  ►", action = { setGuide { build(); guides() } })
            rows += Row("──── About ────")
            rows += Row("About these channels", "►", opens = { about() })
            screen.rows = rows
            App.refresh()
        }

        fun checkAll() {
            val list = groups ?: return
            for (c in Prefs.favourites + list.flatMap { it.channels }) {
                if (LiveTv.known(c) != null) continue
                App.async({ LiveTv.check(c) }) { if (App.inStack(screen)) build() }
            }
        }

        guides = {
            val urls = listOf(Prefs.liveGuide) + Prefs.favourites.flatMap { Guide.guidesFor(it, "") }
            loadGuides(urls) { if (App.inStack(screen)) build() }
        }

        screen = Screen("Live TV", listOf(Row("Loading channels…")))
        screen.key = "live"
        screen.onShow = {
            guides()
            if (groups == null) {
                App.async({ LiveTv.load() }) { r ->
                    r.onSuccess { groups = it; build(); checkAll() }.onFailure { error = Net.describe(it); build() }
                }
            } else { build(); checkAll() }
        }
        return screen
    }

    fun play(c: LiveTv.Channel) {
        val known = LiveTv.known(c)
        if (!known.isNullOrEmpty()) { Player.play(known, c.name, null, 0) { }; return }
        App.toast("Tuning in to ${c.name}…")
        App.async({ LiveTv.check(c) }) { r ->
            val url = r.getOrNull()
            if (url != null) Player.play(url, c.name, null, 0) { } else App.toast("${c.name} isn't answering right now")
        }
    }

    private fun offAir(off: List<LiveTv.Channel>) = Screen(
        "Off air right now",
        listOf(Row("These didn't answer just now. Press Enter to try one anyway."), Row("")) +
            off.map { c -> Row(c.name, c.group, opens = { channelMenu(c, "Live TV · " + c.group) }, play = { Player.play(c.urls.first(), c.name, null, 0) { } }) },
    )

    private fun about() = Screen(
        "About Live TV",
        listOf(
            Row("These are channels the broadcasters stream free themselves:"),
            Row("news, S4C and shopping. They play in your player (mpv)."),
            Row(""),
            Row("BBC One, ITV, Channel 4 and 5 aren't here: watch those on"),
            Row("their own sites or apps."),
            Row(""),
            Row("Each channel is checked when the list opens. Any that don't"),
            Row("answer go to \"Off air right now\"."),
            Row(""),
            Row("Your playlists: add an M3U link you're entitled to use."),
            Row("That's your responsibility. They follow your account to"),
            Row("your other devices."),
        ),
    )

    // ---- your playlists -------------------------------------------------------------------

    private fun addPlaylist(done: () -> Unit) {
        App.ask("Playlist link (M3U)") { v ->
            if (v.isNullOrBlank()) return@ask
            val link = LiveTv.cleanLink(v)
            if (link == null) { App.toast("That isn't a link"); return@ask }
            App.toast("Checking the playlist…")
            val p = LiveTv.Playlist(LiveTv.nameFor(link), link, System.currentTimeMillis())
            App.async({ LiveTv.loadPlaylist(p) }) { r ->
                r.onSuccess { groups ->
                    Prefs.playlists = Prefs.playlists.filter { it.url != link } + p
                    Prefs.syncOut()
                    App.toast("Added ${p.name}: ${groups.sumOf { it.channels.size }} channels")
                    done()
                }.onFailure { App.toast("Couldn't add it: ${Net.describe(it)}") }
            }
        }
    }

    private fun playlist(p: LiveTv.Playlist): Screen {
        var groups: List<LiveTv.Group>? = null
        var error: String? = null
        lateinit var screen: Screen
        // One group: its channels are listed (and checked) right here.
        val channels: () -> List<LiveTv.Channel>? = { groups?.singleOrNull()?.channels }
        fun reload(done: () -> Unit) {
            App.async({ LiveTv.loadPlaylist(p) }) { r ->
                r.onSuccess { groups = it; error = null }.onFailure { error = Net.describe(it) }
                done()
            }
        }
        val tail = listOf(
            Row("────────────────────"),
            Row("Reload this playlist", action = { LiveTv.forget(); reload { screen.onShow?.invoke() } }),
            Row("About this playlist", "►", opens = { aboutPlaylist(p) }),
            Row("Rename this playlist", "►", action = {
                App.ask("New name", p.name) { v ->
                    val name = v?.trim().orEmpty()
                    if (name.isEmpty() || name == p.name) return@ask
                    Prefs.playlists = Prefs.playlists.map { if (it.url == p.url) it.renamed(name) else it }
                    Prefs.syncOut()
                    App.toast("Renamed to $name")
                    App.back()
                }
            }),
            Row("Remove this playlist", "►", opens = {
                confirmScreen("Remove ${p.name}?", "Takes it off Live TV on all your devices.", "Yes, remove it") {
                    Prefs.playlists = Prefs.playlists.filter { it.url != p.url }
                    Prefs.syncOut()
                    App.toast("Removed ${p.name}")
                    App.back(); App.back()
                }
            }),
        )
        screen = channelScreen(p.name, channels, tail, from = p.name) {
            val g = groups
            when {
                g == null && error != null -> listOf(Row("Couldn't load it: $error"))
                g == null -> listOf(Row("Loading the playlist…"))
                g.isEmpty() -> listOf(Row("No channels in this playlist."))
                g.size == 1 -> null
                else -> g.map { grp -> Row(grp.name, "${grp.channels.size}  ►", opens = { channelScreen(grp.name, { grp.channels }, from = p.name + " · " + grp.name) { null } }) }
            }?.plus(tail)
        }
        screen.key = "playlist:" + p.url
        val show = screen.onShow
        screen.onShow = { if (groups == null && error == null) reload { if (App.inStack(screen)) show?.invoke() } else show?.invoke() }
        return screen
    }

    /**
     * A list of channels with their status, checked a few at a time while the list is open.
     * Off-air ones say so while checking, then move to the end. [other] gives the screen's rows
     * instead when it isn't a channel list (still loading, or a list of groups).
     */
    private fun channelScreen(
        title: String,
        channels: () -> List<LiveTv.Channel>?,
        extra: List<Row> = emptyList(),
        from: String = title,
        other: () -> List<Row>?,
    ): Screen {
        lateinit var screen: Screen
        var pending = false
        var active = 0
        val queue = ArrayDeque<LiveTv.Channel>()
        val busy = HashSet<LiveTv.Channel>()

        fun build() {
            pending = false
            val list = channels()
            val rows = other()
            if (list == null || rows != null) { screen.rows = rows ?: emptyList(); App.refresh(); return }
            val progress = LiveTv.progress(list)
            val favs = Prefs.favourites
            val out = mutableListOf<Row>()
            if (progress.isNotEmpty()) out += Row(progress)
            val off = if (progress.isEmpty()) list.filter { LiveTv.known(it) == "" } else emptyList()
            (list - off.toSet()).forEach { out += channelRow(it, from, favs) }
            if (off.isNotEmpty()) {
                out += Row("──── Off air right now ────")
                off.forEach { out += channelRow(it, from, favs) }
            }
            if (list.isEmpty()) out += Row("No channels.")
            screen.rows = out + extra
            App.refresh()
        }

        fun soon() { if (!pending) { pending = true; App.later(500) { build() } } }

        fun next() {
            if (!App.inStack(screen)) return
            val c = queue.removeFirstOrNull() ?: return
            if (LiveTv.known(c) != null) { next(); return }
            active++; busy += c
            App.async({ LiveTv.check(c, 5_000) }) { active--; busy -= c; soon(); next() }
        }

        screen = Screen(title, listOf(Row("Loading…")))
        screen.onShow = {
            build()
            channels()?.let { list -> loadGuides(list.flatMap { Guide.pick(it) }.distinct() + Prefs.liveGuide) { if (App.inStack(screen)) soon() } }
            channels()?.let { list ->
                queue.clear()
                queue.addAll(list.filter { LiveTv.known(it) == null && it !in busy })
                while (active < 4 && queue.isNotEmpty()) next()
            }
        }
        return screen
    }

    private fun status(c: LiveTv.Channel) = when (LiveTv.known(c)) { null -> "checking…"; "" -> "off air"; else -> "live" }

    /** Enter opens the channel (watch, favourite); p plays it straight away. */
    private fun channelRow(c: LiveTv.Channel, from: String, favs: List<LiveTv.Channel> = Prefs.favourites): Row {
        val fav = if (LiveTv.isFavourite(favs, c)) "★ " else ""
        val on = Guide.nowTitle(c, guidesFor(c)).let { if (it.length > 34) it.take(33).trimEnd() + "…" else it }
        return Row(c.name, fav + listOf(on, status(c)).filter { it.isNotEmpty() }.joinToString(" · "), opens = { channelMenu(c, from) }, play = { play(c) })
    }

    // ---- TV guide ------------------------------------------------------------------------------

    private fun guidesFor(c: LiveTv.Channel) = Guide.guidesFor(c, Prefs.liveGuide)

    private fun loadGuides(urls: List<String>, done: () -> Unit) {
        Guide.due(urls).forEach { u -> App.async({ Guide.load(u) }) { done() } }
    }

    private fun guideState(url: String): String = when {
        Guide.isLoading(url) -> "loading…"
        Guide.get(url) != null -> "${Guide.get(url)!!.progs.size} channels"
        Guide.error(url) != null -> "couldn't read it"
        else -> LiveTv.nameFor(url)
    }

    private fun noGuide(c: LiveTv.Channel): String {
        val urls = guidesFor(c)
        return when {
            urls.isEmpty() -> "No guide yet: add a TV guide link in Live TV"
            urls.any { Guide.isLoading(it) } -> "Loading the guide…"
            urls.all { Guide.error(it) != null } -> "Couldn't read the guide: ${Guide.error(urls.first())}"
            else -> "Not in the guide"
        }
    }

    private fun wrap(text: String, width: Int, lines: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (w in text.split(' ')) {
            if (line.isEmpty()) line = w
            else if (line.length + 1 + w.length <= width) line += " $w"
            else { out += line; line = w; if (out.size == lines) break }
        }
        if (out.size < lines && line.isNotEmpty()) out += line
        if (out.size == lines && out.joinToString(" ").length < text.length) out[lines - 1] = out[lines - 1].take(width - 1) + "…"
        return out
    }

    private fun guideRows(c: LiveTv.Channel): List<Row> {
        val progs = Guide.lookup(c, guidesFor(c))
        if (progs.isEmpty()) return listOf(Row(noGuide(c))) + guidesFor(c).map { Row(info("Guide link", it)) }
        val now = System.currentTimeMillis()
        val width = (App.columns() - 10).coerceAtMost(90)
        val rows = mutableListOf<Row>()
        val cur = progs.first().takeIf { it.start <= now }
        val rest = if (cur != null) progs.drop(1) else progs
        if (cur != null) {
            val left = ((cur.stop - now) / 60_000).coerceAtLeast(1)
            rows += Row("Now   " + cur.title, "${Guide.clock(cur.start)}–${Guide.clock(cur.stop)}  ($left min left)")
            wrap(cur.desc, width, 3).forEach { rows += Row("      $it") }
        }
        rest.firstOrNull()?.let { n ->
            rows += Row("Next  " + n.title, Guide.clock(n.start))
            wrap(n.desc, width, 2).forEach { rows += Row("      $it") }
        }
        val later = rest.drop(1).take(10)
        if (later.isNotEmpty()) {
            rows += Row("──── Later ────")
            later.forEach { rows += Row(it.title, Guide.clock(it.start)) }
        }
        return rows
    }

    private fun setGuide(done: () -> Unit) {
        App.ask("TV guide link (XMLTV, .xml or .xml.gz; empty removes it)", Prefs.liveGuide) { v ->
            if (v == null) return@ask
            val text = v.trim()
            val link = if (text.isEmpty()) "" else LiveTv.cleanLink(text)
            if (link == null) { App.toast("That isn't a link"); return@ask }
            Guide.forget(Prefs.liveGuide)
            Prefs.liveGuide = link
            Prefs.syncOut()
            App.toast(if (link.isEmpty()) "TV guide link removed" else "Reading the guide…")
            done()
        }
    }

    /** One channel: watch it, or add it to (or take it off) your favourites. */
    fun channelMenu(c: LiveTv.Channel, from: String): Screen {
        lateinit var s: Screen
        var placed = false
        var redraw: () -> Unit = {}
        fun rows(): List<Row> {
            val fav = LiveTv.isFavourite(Prefs.favourites, c)
            return listOfNotNull(
                Row("Watch now", status(c).let { if (it == "checking…") "►" else "$it  ►" }, action = { play(c) }),
                Row(if (fav) "Remove from favourites" else "Add to favourites", if (fav) "★" else "☆", action = {
                    Prefs.favourites = LiveTv.toggle(Prefs.favourites, c, from)
                    Prefs.syncOut()
                    App.toast(if (fav) "Removed from favourites" else "Added to favourites")
                    redraw()
                }),
                Row(""),
            ) + guideRows(c) + listOfNotNull(
                Row(""),
                Row(info("From", from)),
                c.note.takeIf { it.isNotEmpty() }?.let { Row(info("Note", it)) },
            )
        }
        fun build() {
            s.rows = rows()
            // Always open on "Watch now", so OK, OK plays.
            if (!placed) { placed = true; s.moveTo = 0; s.pos = 0 }
            App.refresh()
        }
        s = Screen(c.name, emptyList())
        s.rows = rows()
        redraw = { build() }
        s.onShow = { build(); loadGuides(guidesFor(c)) { if (App.inStack(s)) build() } }
        return s
    }

    /** Your favourites in your own order: pick one up and drop it where it goes. */
    private fun arrange(): Screen = Reorder(
        head = { emptyList() },
        items = Prefs.favourites.toMutableList(),
        label = { it.name },
        detail = { it.group },
        open = { null },
        commit = { Prefs.favourites = it; Prefs.syncOut() },
    ).build("Arrange favourites")

    /** Channels matching a search: favourites, the broadcasters' list and your playlists. */
    fun search(query: String, done: (List<LiveTv.Channel>) -> Unit) {
        if (!Prefs.liveTv) return
        val favs = Prefs.favourites
        val lists = Prefs.playlists
        App.async({ LiveTv.matching(LiveTv.everything(favs, lists), query) }) { r -> r.getOrNull()?.let(done) }
    }

    /** A search result: Enter opens it, p plays it. */
    fun searchRow(c: LiveTv.Channel): Row = Row(c.name, c.group, opens = { channelMenu(c, c.group) }, play = { play(c) })

    /** Details of a playlist: channels, groups, how many are on air, its link and so on. */
    private fun aboutPlaylist(p: LiveTv.Playlist): Screen {
        val day = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK)
        val link = listOf(Row(""), Row("Link:")) + p.url.chunked(App.columns()).map { Row(it) } +
            Row("Copy the link", action = { Clipboard.copyOrShow(p.url, "Playlist link") })
        val s = Screen("About ${p.name}", listOf(Row("Reading the playlist…")) + link)
        s.onShow = {
            App.async({ LiveTv.read(p, fresh = false) }) { r ->
                s.rows = r.fold(
                    { l -> LiveTv.about(p, l, Prefs.favourites) { day.format(java.util.Date(it)) }.map { (k, v) -> Row(info(k, v)) } },
                    { e -> listOf(Row("Couldn't read it: ${Net.describe(e)}")) },
                ) + link
                App.refresh()
            }
        }
        return s
    }
}
