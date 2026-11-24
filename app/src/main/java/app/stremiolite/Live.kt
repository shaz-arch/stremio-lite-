package app.stremiolite

/** Live TV: the broadcasters' own free channels, checked when the list opens, and your playlists. */
object Live {
    fun homeDetail(app: MainActivity): String {
        val n = Settings.playlists(app).size
        return if (n == 0) "►" else "$n playlist${if (n == 1) "" else "s"}  ►"
    }

    fun screen(app: MainActivity): Screen {
        lateinit var screen: Screen
        var groups: List<LiveTv.Group>? = null
        var error: String? = null

        fun build() {
            val rows = mutableListOf<Row>()
            val list = groups
            when {
                list == null && error != null -> rows += Row("Couldn't load the channel list: $error")
                list == null -> rows += Row("Loading channels…")
            }
            val off = mutableListOf<LiveTv.Channel>()
            list?.forEach { g ->
                val shown = g.channels.filter { LiveTv.known(it) != "" }
                off += g.channels.filter { LiveTv.known(it) == "" }
                if (shown.isEmpty()) return@forEach
                rows += Row("──── ${g.name} ────")
                shown.forEach { c ->
                    val state = when (LiveTv.known(c)) { null -> "checking…"; else -> listOf(c.note, "live").filter { it.isNotEmpty() }.joinToString(" · ") }
                    rows += Row(c.name, state, action = { play(app, c) }, play = { play(app, c) })
                }
            }
            if (off.isNotEmpty()) {
                rows += Row("Off air right now", "${off.size}  ►", opens = { offAir(app, off) })
            }
            rows += Row("──── Your playlists ────")
            Settings.playlists(app).forEach { p -> rows += Row(p.name, "►", opens = { playlist(app, p) }) }
            rows += Row("Add a playlist (M3U link)", "►", action = { addPlaylist(app) { build() } })
            rows += Row("──── About ────")
            rows += Row("About these channels", "►", opens = { about() })
            screen.rows = rows
            app.refresh(screen)
        }

        fun checkAll() {
            val list = groups ?: return
            for (c in list.flatMap { it.channels }) {
                if (LiveTv.known(c) != null) continue
                Net.async({ LiveTv.check(c) }) { if (app.inStack(screen)) build() }
            }
        }

        screen = Screen("Live TV", listOf(Row("Loading channels…")))
        screen.key = "live"
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
    private fun play(app: MainActivity, c: LiveTv.Channel) {
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
            off.map { c -> Row(c.name, c.group, action = { Player.play(app, c.urls.first(), c.name) }) },
    )

    private fun about() = Screen(
        "About Live TV",
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
            Row("Your playlists: add an M3U link you're"),
            Row("entitled to use. That's your responsibility."),
            Row("They follow your account to your other devices."),
        ),
    )

    // ---- your playlists -------------------------------------------------------------------

    private fun addPlaylist(app: MainActivity, done: () -> Unit) {
        val input = android.widget.EditText(app).apply {
            hint = "https://…/playlist.m3u"
            setSingleLine()
        }
        android.app.AlertDialog.Builder(app)
            .setTitle("Playlist link")
            .setMessage("Paste an M3U playlist link you're entitled to use. It follows your account to your other devices.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val link = LiveTv.cleanLink(input.text.toString())
                if (link == null) { app.toast("That isn't a link"); return@setPositiveButton }
                app.toast("Checking the playlist…")
                val p = LiveTv.Playlist(LiveTv.nameFor(link), link)
                Net.async({ LiveTv.loadPlaylist(p) }) { r ->
                    r.onSuccess { groups ->
                        Settings.setPlaylists(app, Settings.playlists(app).filter { it.url != link } + p)
                        app.toast("Added ${p.name}: ${groups.sumOf { it.channels.size }} channels")
                        done()
                    }.onFailure { app.toast("Couldn't add it: ${Net.describe(it)}") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playlist(app: MainActivity, p: LiveTv.Playlist): Screen {
        lateinit var screen: Screen
        val tail = listOf(
            Row("────────────────────"),
            Row("Reload this playlist", action = { load(app, p) { screen.rows = it; app.refresh(screen) } }),
            Row("Remove this playlist", "►", opens = {
                confirmScreen(app, "Remove ${p.name}?", "Takes it off Live TV on all your devices.", "Yes, remove it") {
                    Settings.setPlaylists(app, Settings.playlists(app).filter { it.url != p.url })
                    app.toast("Removed ${p.name}")
                    app.back(); app.back()
                }
            }),
        )
        screen = Screen(p.name, listOf(Row("Loading the playlist…")) + tail)
        screen.key = "playlist:" + p.url
        load(app, p) { rows -> screen.rows = rows + tail; app.refresh(screen) }
        return screen
    }

    private fun load(app: MainActivity, p: LiveTv.Playlist, done: (List<Row>) -> Unit) {
        Net.async({ LiveTv.loadPlaylist(p) }) { r ->
            done(r.fold({ groups ->
                when {
                    groups.isEmpty() -> listOf(Row("No channels in this playlist."))
                    // One group: straight to the channels.
                    groups.size == 1 -> channelRows(app, groups[0])
                    else -> groups.map { g -> Row(g.name, "${g.channels.size}  ►", opens = { Screen(g.name, channelRows(app, g)) }) }
                }
            }, { e -> listOf(Row("Couldn't load it: ${Net.describe(e)}")) }))
        }
    }

    private fun channelRows(app: MainActivity, g: LiveTv.Group) =
        g.channels.map { c -> Row(c.name, "", action = { Player.play(app, c.urls.first(), c.name) }, play = { Player.play(app, c.urls.first(), c.name) }) }
}
