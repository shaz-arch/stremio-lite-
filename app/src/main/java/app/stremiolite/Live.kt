package app.stremiolite

/** Live TV: recent channels, favourites, and playlists (the broadcasters' own free channels are the first). */
object Live {
    fun homeDetail(app: MainActivity): String {
        val n = Settings.playlists(app).size
        return if (n == 0) "►" else "$n playlist${if (n == 1) "" else "s"}  ►"
    }

    /** Live TV: your recent channels, favourites and playlists (the built-in free channels are one of them). */
    fun screen(app: MainActivity): Screen {
        lateinit var screen: Screen

        fun build() {
            val rows = mutableListOf<Row>()
            val favs = Settings.favourites(app)
            val recent = Settings.recent(app)
            if (recent.isNotEmpty()) {
                rows += Row("──── Recent ────")
                recent.forEach { c -> rows += channelRow(app, c, c.group, favs) }
            }
            if (favs.isNotEmpty()) {
                rows += Row("──── Favourites ────")
                favs.forEach { c -> rows += channelRow(app, c, c.group, favs) }
                if (favs.size > 1) rows += Row("Arrange favourites", "►", opens = { arrange(app) })
            }
            if (recent.isEmpty() && favs.isEmpty()) {
                rows += Row("Empty.")
            }
            rows += Row("──── Playlists ────")
            rows += Row("Free channels", "news, Welsh, shopping  ►", opens = { freeChannels(app) })
            Settings.playlists(app).forEach { p -> rows += Row(p.name, "►", opens = { playlist(app, p) }) }
            rows += Row("Add a playlist (M3U link)", "►", action = { addPlaylist(app) { build() } })
            rows += Row("──── TV guide ────")
            val yours = Settings.liveGuide(app)
            rows += Row("Your TV guide link (XMLTV)", if (yours.isEmpty()) "none  ►" else guideState(yours) + "  ►", action = { setGuide(app) { build(); loadGuides(app, mine(app)) { if (app.inStack(screen)) build() } } })
            screen.rows = rows
            app.refresh(screen)
        }

        screen = Screen("Live TV", listOf(Row("Loading…")))
        screen.key = "live"
        screen.onShow = {
            build()
            // Only your own channels are checked here: a few, so it's quick on the stick.
            for (c in mine(app)) {
                if (LiveTv.known(c) != null) continue
                Net.async({ LiveTv.check(c) }) { if (app.inStack(screen)) build() }
            }
            loadGuides(app, mine(app)) { if (app.inStack(screen)) build() }
        }
        return screen
    }

    private fun mine(app: MainActivity) = Settings.recent(app) + Settings.favourites(app)

    /** The built-in list (the broadcasters' own free streams), shown like a playlist. */
    private fun freeChannels(app: MainActivity): Screen {
        lateinit var screen: Screen
        var groups: List<LiveTv.Group>? = null
        var error: String? = null
        fun shown() = groups?.let { LiveTv.visible(LiveTv.Playlist("", "", 0, Settings.builtinHidden(app)), it) }

        fun build() {
            val rows = mutableListOf<Row>()
            val list = shown()
            when {
                list == null && error != null -> rows += Row("Couldn't load the channel list: $error")
                list == null -> rows += Row("Loading channels…")
                list.isEmpty() -> rows += Row("Everything here is hidden: see Choose what to show.")
            }
            val favs = Settings.favourites(app)
            val off = mutableListOf<LiveTv.Channel>()
            list?.forEach { g ->
                val on = g.channels.filter { LiveTv.known(it) != "" }
                off += g.channels.filter { LiveTv.known(it) == "" }
                if (on.isEmpty()) return@forEach
                rows += Row("──── ${g.name} ────")
                on.forEach { c -> rows += channelRow(app, c, "Free channels · " + g.name, favs) }
            }
            if (off.isNotEmpty()) rows += Row("Off air right now", "${off.size}  ►", opens = { offAir(app, off) })
            rows += Row("────────────────────")
            rows += Row("Choose what to show", "►", opens = {
                chooser(app, groups ?: emptyList(), { Settings.builtinHidden(app) }) { Settings.setBuiltinHidden(app, it) }
            })
            rows += Row("About these channels", "►", opens = { about() })
            screen.rows = rows
            app.refresh(screen)
        }

        fun checkAll() {
            for (c in shown()?.flatMap { it.channels } ?: emptyList()) {
                if (LiveTv.known(c) != null) continue
                Net.async({ LiveTv.check(c) }) { if (app.inStack(screen)) build() }
            }
            loadGuides(app, shown()?.flatMap { it.channels } ?: emptyList()) { if (app.inStack(screen)) build() }
        }

        screen = Screen("Free channels", listOf(Row("Loading channels…")))
        screen.key = "live-free"
        screen.onShow = {
            if (groups == null) {
                Net.async({ LiveTv.load() }) { r ->
                    r.onSuccess { groups = it; build(); checkAll() }.onFailure { error = Net.describe(it); build() }
                }
            } else { build(); checkAll() }
        }
        return screen
    }

    /** Plays the channel's working link (checking it first if it hasn't been). */
    fun play(app: MainActivity, c: LiveTv.Channel, from: String = c.group) {
        Settings.setRecent(app, LiveTv.played(Settings.recent(app), c.from(from)))
        val known = LiveTv.known(c)
        if (!known.isNullOrEmpty()) { Player.play(app, known, c.name); return }
        app.toast("Tuning in to ${c.name}…")
        Net.async({ LiveTv.check(c) }) { r ->
            val url = r.getOrNull()
            if (url != null) Player.play(app, url, c.name) else app.toast("${c.name} isn't answering right now")
        }
    }

    private fun offAir(app: MainActivity, off: List<LiveTv.Channel>) = Screen(
        "Off air right now",
        listOf(Row("These didn't answer just now. Press OK to try one anyway."), Row("")) +
            off.map { c -> Row(c.name, c.group, opens = { channelMenu(app, c, "Free channels · " + c.group) }, play = { Player.play(app, c.urls.first(), c.name) }) },
    )

    private fun about() = Screen(
        "About Free channels",
        listOf(
            Row("These are channels the broadcasters"),
            Row("stream free themselves: news, S4C"),
            Row("and shopping. They play in your player."),
            Row(""),
            Row("BBC One, ITV, Channel 4 and 5 aren't"),
            Row("here: watch those in their own apps."),
            Row(""),
            Row("Each channel is checked when the list"),
            Row("opens. Any that don't answer go to"),
            Row("\"Off air right now\"."),
            Row(""),
            Row("Don't want some? Choose what to show"),
            Row("hides them on all your devices."),
        ),
    )

    // ---- your playlists -------------------------------------------------------------------

    private fun addPlaylist(app: MainActivity, done: () -> Unit) {
        val input = android.widget.EditText(app).apply {
            hint = "https://…/playlist.m3u"
            setSingleLine()
        }
        PhoneTyping.show(app, android.app.AlertDialog.Builder(app)
            .setTitle("Playlist link")
            .setMessage("Paste an M3U playlist link you're entitled to use. It follows your account to your other devices.")
            .setPositiveButton("Add") { _, _ ->
                val link = LiveTv.cleanLink(input.text.toString())
                if (link == null) { app.toast("That isn't a link"); return@setPositiveButton }
                app.toast("Checking the playlist…")
                val p = LiveTv.Playlist(LiveTv.nameFor(link), link, System.currentTimeMillis())
                Net.async({ LiveTv.loadPlaylist(p) }) { r ->
                    r.onSuccess { groups ->
                        Settings.setPlaylists(app, Settings.playlists(app).filter { it.url != link } + p)
                        app.toast("Added ${p.name}: ${groups.sumOf { it.channels.size }} channels")
                        done()
                    }.onFailure { app.toast("Couldn't add it: ${Net.describe(it)}") }
                }
            }
            .setNegativeButton("Cancel", null), input, "Playlist link")
    }

    private fun playlist(app: MainActivity, p: LiveTv.Playlist): Screen {
        var groups: List<LiveTv.Group>? = null
        var error: String? = null
        lateinit var screen: Screen
        lateinit var tail: List<Row>
        fun current() = Settings.playlists(app).firstOrNull { it.url == p.url } ?: p
        // What's shown: the playlist without the groups and channels you've hidden.
        fun shown() = groups?.let { LiveTv.visible(current(), it) }
        // One group: its channels are listed (and checked) right here.
        val channels: () -> List<LiveTv.Channel>? = { shown()?.singleOrNull()?.channels }
        fun reload(done: () -> Unit) {
            Net.async({ LiveTv.loadPlaylist(p) }) { r ->
                r.onSuccess { g ->
                    groups = g; error = null
                    LiveTv.refreshFavourites(Settings.favourites(app), g)?.let { Settings.setFavourites(app, it) }
                }.onFailure { error = Net.describe(it) }
                done()
            }
        }
        tail = listOf(
            Row("────────────────────"),
            Row("Reload this playlist", action = { LiveTv.forget(); reload { screen.onShow?.invoke() } }),
            Row("Choose what to show", "►", opens = { chooser(app, p.url, groups ?: emptyList()) }),
            Row("About this playlist", "►", opens = { aboutPlaylist(app, current()) }),
            Row("Rename this playlist", "►", action = { rename(app, current()) }),
            Row("Remove this playlist", "►", opens = {
                confirmScreen(app, "Remove ${p.name}?", "Takes it off Live TV on all your devices.", "Yes, remove it") {
                    Settings.setPlaylists(app, Settings.playlists(app).filter { it.url != p.url })
                    app.toast("Removed ${p.name}")
                    app.back(); app.back()
                }
            }),
        )
        screen = channelScreen(app, p.name, channels, tail, from = p.name) {
            val g = shown()
            when {
                g == null && error != null -> listOf(Row("Couldn't load it: $error"))
                g == null -> listOf(Row("Loading the playlist…"))
                groups!!.isEmpty() -> listOf(Row("No channels in this playlist."))
                g.isEmpty() -> listOf(Row("Everything here is hidden: see Choose what to show."))
                g.size == 1 -> null
                else -> g.map { grp ->
                    Row(grp.name, "${grp.channels.size}  ►", opens = {
                        channelScreen(app, grp.name, { shown()?.firstOrNull { it.name == grp.name }?.channels ?: emptyList() }, from = p.name + " · " + grp.name) { null }
                    })
                }
            }?.plus(tail)
        }
        screen.key = "playlist:" + p.url
        val show = screen.onShow
        screen.onShow = { if (groups == null && error == null) reload { if (app.inStack(screen)) show?.invoke() } else show?.invoke() }
        return screen
    }

    /**
     * A list of channels with their status, checked two at a time while the list is open (so the
     * stick stays responsive). Off-air ones say so while checking, then move to the end.
     * [other] gives the screen's rows instead when it isn't a channel list (still loading, groups).
     */
    private fun channelScreen(
        app: MainActivity,
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
            if (list == null || rows != null) { screen.rows = rows ?: emptyList(); app.refresh(screen); return }
            val progress = LiveTv.progress(list)
            val favs = Settings.favourites(app)
            val out = mutableListOf<Row>()
            if (progress.isNotEmpty()) out += Row(progress)
            val off = if (progress.isEmpty()) list.filter { LiveTv.known(it) == "" } else emptyList()
            (list - off.toSet()).forEach { out += channelRow(app, it, from, favs) }
            if (off.isNotEmpty()) {
                out += Row("──── Off air right now ────")
                off.forEach { out += channelRow(app, it, from, favs) }
            }
            if (list.isEmpty()) out += Row("No channels.")
            screen.rows = out + extra
            app.refresh(screen)
        }

        fun soon() { if (!pending) { pending = true; Net.later(if (app.isPhone) 800L else 1500L) { build() } } }

        fun next() {
            if (!app.inStack(screen)) return
            val c = queue.removeFirstOrNull() ?: return
            if (LiveTv.known(c) != null) { next(); return }
            active++; busy += c
            Net.async({ LiveTv.check(c, 5_000) }) { active--; busy -= c; soon(); next() }
        }

        screen = Screen(title, listOf(Row("Loading…")))
        screen.onShow = {
            build()
            channels()?.let { list -> loadGuides(app, list) { if (app.inStack(screen)) soon() } }
            channels()?.let { list ->
                queue.clear()
                queue.addAll(list.filter { LiveTv.known(it) == null && it !in busy })
                while (active < 2 && queue.isNotEmpty()) next()
            }
        }
        return screen
    }

    private fun status(c: LiveTv.Channel) = when (LiveTv.known(c)) { null -> "checking…"; "" -> "off air"; else -> "live" }

    /** OK opens the channel (watch, favourite); Play/Pause plays it straight away. */
    private fun channelRow(app: MainActivity, c: LiveTv.Channel, from: String, favs: List<LiveTv.Channel> = Settings.favourites(app)): Row {
        val fav = if (LiveTv.isFavourite(favs, c)) "★ " else ""
        val on = Guide.nowTitle(c, guidesFor(app, c)).let { if (it.length > 26) it.take(25).trimEnd() + "…" else it }
        return Row(c.name, fav + listOf(on, status(c)).filter { it.isNotEmpty() }.joinToString(" · "), opens = { channelMenu(app, c, from) }, play = { play(app, c, from) })
    }

    // ---- TV guide ------------------------------------------------------------------------------

    private fun guidesFor(app: MainActivity, c: LiveTv.Channel) = Guide.guidesFor(c, Settings.liveGuide(app))

    /** Reads the guides [channels] need (just for those channels), then calls [done] once per guide read. */
    private fun loadGuides(app: MainActivity, channels: List<LiveTv.Channel>, done: () -> Unit) {
        Guide.needs(channels, Settings.liveGuide(app)).forEach { (u, wanted) -> Net.async({ Guide.load(u, wanted) }) { done() } }
    }

    private fun guideState(url: String): String = when {
        Guide.isLoading(url) -> "loading…"
        Guide.get(url) != null -> "${Guide.get(url)!!.progs.size} channels"
        Guide.error(url) != null -> "couldn't read it"
        else -> LiveTv.nameFor(url)
    }

    /** Why a channel shows no programmes. */
    private fun noGuide(app: MainActivity, c: LiveTv.Channel): String {
        val urls = guidesFor(app, c)
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

    /** Now, next and later today for a channel, as rows. */
    private fun guideRows(app: MainActivity, c: LiveTv.Channel): List<Row> {
        val progs = Guide.lookup(c, guidesFor(app, c))
        if (progs.isEmpty()) return listOf(Row(noGuide(app, c))) + guidesFor(app, c).map { Row(info("Guide link", it.removePrefix("https://").removePrefix("http://").take(if (narrow) 30 else 48))) }
        val now = System.currentTimeMillis()
        val width = if (narrow) 34 else 50
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

    /** One channel: watch it, or add it to (or take it off) your favourites. */
    fun channelMenu(app: MainActivity, c: LiveTv.Channel, from: String): Screen {
        lateinit var s: Screen
        var placed = false
        var redraw: () -> Unit = {}
        fun rows(): List<Row> {
            val fav = LiveTv.isFavourite(Settings.favourites(app), c)
            return listOfNotNull(
                Row("Watch now", status(c).let { if (it == "checking…") "►" else "$it  ►" }, action = { play(app, c, from) }),
                Row(if (fav) "Remove from favourites" else "Add to favourites", if (fav) "★" else "☆", action = {
                    Settings.setFavourites(app, LiveTv.toggle(Settings.favourites(app), c, from))
                    app.toast(if (fav) "Removed from favourites" else "Added to favourites")
                    redraw()
                }),
                Row(""),
            ) + guideRows(app, c) + listOfNotNull(
                Row(""),
                Row(info("From", from)),
                c.note.takeIf { it.isNotEmpty() }?.let { Row(info("Note", it)) },
            )
        }
        fun build() {
            s.rows = rows()
            // Always open on "Watch now", so OK, OK plays.
            if (!placed) { placed = true; s.moveTo = 0; s.position = 0 }
            app.refresh(s)
        }
        s = Screen(c.name, emptyList())
        s.rows = rows()
        redraw = { build() }
        s.onShow = { build(); loadGuides(app, listOf(c)) { if (app.inStack(s)) build() } }
        return s
    }

    /** Your favourites in your own order: pick one up and drop it where it goes. */
    private fun arrange(app: MainActivity): Screen = Reorder(
        app,
        head = { emptyList() },
        items = Settings.favourites(app).toMutableList(),
        label = { it.name },
        detail = { it.group },
        open = { null },
        commit = { Settings.setFavourites(app, it) },
    ).build("Arrange favourites")

    /** Channels matching a search, for the Search screen: favourites, the broadcasters' list and your playlists. */
    fun search(app: MainActivity, query: String, done: (List<LiveTv.Channel>) -> Unit) {
        if (!Settings.liveTv(app)) return
        val favs = Settings.favourites(app)
        val lists = Settings.playlists(app)
        val hide = Settings.builtinHidden(app)
        Net.async({ LiveTv.matching(LiveTv.everything(favs, lists, hide), query) }) { r -> r.getOrNull()?.let(done) }
    }

    /** A search result: OK opens it, Play/Pause plays it. */
    fun searchRow(app: MainActivity, c: LiveTv.Channel): Row =
        Row(c.name, c.group, opens = { channelMenu(app, c, c.group) }, play = { play(app, c) })

    /** A new name for a playlist; Live TV shows it straight away (and so do your other devices). */
    private fun rename(app: MainActivity, p: LiveTv.Playlist) {
        val input = android.widget.EditText(app).apply {
            setText(p.name)
            setSelection(p.name.length)
            setSingleLine()
        }
        android.app.AlertDialog.Builder(app)
            .setTitle("Rename playlist")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == p.name) return@setPositiveButton
                Settings.setPlaylists(app, Settings.playlists(app).map { if (it.url == p.url) it.renamed(name) else it })
                app.toast("Renamed to $name")
                app.back()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Details of a playlist: channels, groups, how many are on air, its link and so on. */
    private fun aboutPlaylist(app: MainActivity, p: LiveTv.Playlist): Screen {
        val day = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.UK)
        val link = listOf(Row(""), Row("Link:")) + p.url.chunked(if (narrow) 34 else 52).map { Row(it) } +
            if (app.isPhone) Row("Copy the link", action = { LinkOut.copy(app, p.url, "Playlist link", " Keep it private.") })
            else Row("Show the link as a QR code", "QR  ►", opens = { LinkOut.qr(p.name, p.url) })
        val s = Screen("About ${p.name}", listOf(Row("Reading the playlist…")) + link)
        s.onShow = {
            Net.async({ LiveTv.read(p, fresh = false) }) { r ->
                s.rows = r.fold(
                    { l -> LiveTv.about(p, l, Settings.favourites(app)) { day.format(java.util.Date(it)) }.map { (k, v) -> Row(info(k, v)) } },
                    { e -> listOf(Row("Couldn't read it: ${Net.describe(e)}")) },
                ) + link
                app.refresh(s)
            }
        }
        return s
    }

    /** Your TV guide link: used for the broadcasters' channels and any playlist without its own guide. */
    private fun setGuide(app: MainActivity, done: () -> Unit) {
        val input = android.widget.EditText(app).apply {
            hint = "https://…/guide.xml.gz"
            setText(Settings.liveGuide(app))
            setSingleLine()
        }
        PhoneTyping.show(app, android.app.AlertDialog.Builder(app)
            .setTitle("TV guide link (XMLTV)")
            .setMessage("Paste an XMLTV guide link (.xml or .xml.gz) you're entitled to use. Playlists with their own guide use theirs first. Leave it empty to remove it.")
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val link = if (text.isEmpty()) "" else LiveTv.cleanLink(text)
                if (link == null) { app.toast("That isn't a link"); return@setPositiveButton }
                Guide.forget(Settings.liveGuide(app))
                Settings.setLiveGuide(app, link)
                app.toast(if (link.isEmpty()) "TV guide link removed" else "Reading the guide…")
                done()
            }
            .setNegativeButton("Cancel", null), input, "TV guide link")
    }

    // ---- choosing what a playlist shows -------------------------------------------------------

    /** Hide groups or channels you don't want (radio, ones always off air). They stay in the playlist. */
    private fun chooser(app: MainActivity, url: String, groups: List<LiveTv.Group>): Screen = chooser(
        app, groups,
        { Settings.playlists(app).firstOrNull { it.url == url }?.hidden ?: emptySet() },
    ) { h -> Settings.setPlaylists(app, Settings.playlists(app).map { if (it.url == url) it.withHidden(h) else it }) }

    private fun chooser(app: MainActivity, groups: List<LiveTv.Group>, hidden: () -> Set<String>, setHidden: (Set<String>) -> Unit): Screen {
        fun toggle(key: String) = hidden().let { h -> setHidden(if (key in h) h - key else h + key) }
        fun mark(isHidden: Boolean) = if (isHidden) "✗ hidden" else "✓ shown"
        fun channelRows(list: List<LiveTv.Channel>, redraw: () -> Unit): List<Row> {
            val h = hidden()
            return list.map { c -> LiveTv.channelKey(c).let { k -> Row(c.name, mark(k in h), action = { toggle(k); redraw() }) } }
        }

        fun groupScreen(g: LiveTv.Group): Screen {
            lateinit var s: Screen
            fun build() {
                val k = LiveTv.groupKey(g)
                s.rows = listOf(
                    Row("Show this group", "◄ ${if (k in hidden()) "No" else "Yes"} ►", action = { toggle(k); build() }),
                    Row("──── Channels ────"),
                ) + channelRows(g.channels) { build() }
                app.refresh(s)
            }
            s = Screen(g.name, emptyList())
            s.onShow = { build() }
            return s
        }

        lateinit var s: Screen
        fun build() {
            val h = hidden()
            val rows = mutableListOf<Row>()
            val radio = LiveTv.radio(groups)
            if (radio.isNotEmpty()) {
                val keys = radio.map { LiveTv.channelKey(it) }.toSet()
                val all = keys.all { it in h }
                rows += Row("Radio stations (${radio.size})", "◄ ${if (all) "Hidden" else "Shown"} ►", action = { setHidden(if (all) h - keys else h + keys); build() })
            }
            if (groups.size == 1) {
                rows += Row("──── Channels ────")
                rows += channelRows(groups[0].channels) { build() }
            } else {
                rows += Row("──── Groups ────")
                groups.forEach { g ->
                    val off = g.channels.count { LiveTv.channelKey(it) in h }
                    val detail = if (LiveTv.groupKey(g) in h) mark(true) else mark(false) + if (off > 0) " · $off hidden" else ""
                    rows += Row(g.name, "$detail  ►", opens = { groupScreen(g) })
                }
            }
            if (h.isNotEmpty()) rows += Row("Show everything again", action = { setHidden(emptySet()); build() })
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Choose what to show", emptyList())
        s.onShow = { build() }
        return s
    }
}
