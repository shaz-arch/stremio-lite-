package app.stremiolite.linux

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
                    rows += Row(c.name, state, action = { play(c) }, play = { play(c) })
                }
            }
            if (off.isNotEmpty()) rows += Row("Off air right now", "${off.size}  ►", opens = { offAir(off) })
            rows += Row("──── Your playlists ────")
            Prefs.playlists.forEach { p -> rows += Row(p.name, "►", opens = { playlist(p) }) }
            rows += Row("Add a playlist (M3U link)", "►", action = { addPlaylist { build() } })
            rows += Row("──── About ────")
            rows += Row("About these channels", "►", opens = { about() })
            screen.rows = rows
            App.refresh()
        }

        fun checkAll() {
            val list = groups ?: return
            for (c in list.flatMap { it.channels }) {
                if (LiveTv.known(c) != null) continue
                App.async({ LiveTv.check(c) }) { if (App.inStack(screen)) build() }
            }
        }

        screen = Screen("Live TV", listOf(Row("Loading channels…")))
        screen.key = "live"
        screen.onShow = {
            if (groups == null) {
                App.async({ LiveTv.load() }) { r ->
                    r.onSuccess { groups = it; build(); checkAll() }.onFailure { error = Net.describe(it); build() }
                }
            } else { build(); checkAll() }
        }
        return screen
    }

    private fun play(c: LiveTv.Channel) {
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
            off.map { c -> Row(c.name, c.group, action = { Player.play(c.urls.first(), c.name, null, 0) { } }) },
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
            val p = LiveTv.Playlist(LiveTv.nameFor(link), link)
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
        lateinit var screen: Screen
        val tail = listOf(
            Row("────────────────────"),
            Row("Reload this playlist", action = { load(p) { screen.rows = it; App.refresh() } }),
            Row("Remove this playlist", "►", opens = {
                confirmScreen("Remove ${p.name}?", "Takes it off Live TV on all your devices.", "Yes, remove it") {
                    Prefs.playlists = Prefs.playlists.filter { it.url != p.url }
                    Prefs.syncOut()
                    App.toast("Removed ${p.name}")
                    App.back(); App.back()
                }
            }),
        )
        screen = Screen(p.name, listOf(Row("Loading the playlist…")) + tail)
        screen.key = "playlist:" + p.url
        load(p) { rows -> screen.rows = rows + tail; App.refresh() }
        return screen
    }

    private fun load(p: LiveTv.Playlist, done: (List<Row>) -> Unit) {
        App.async({ LiveTv.loadPlaylist(p) }) { r ->
            done(r.fold({ groups ->
                when {
                    groups.isEmpty() -> listOf(Row("No channels in this playlist."))
                    groups.size == 1 -> channelRows(groups[0])
                    else -> groups.map { g -> Row(g.name, "${g.channels.size}  ►", opens = { Screen(g.name, channelRows(g)) }) }
                }
            }, { e -> listOf(Row("Couldn't load it: ${Net.describe(e)}")) }))
        }
    }

    private fun channelRows(g: LiveTv.Group) =
        g.channels.map { c -> Row(c.name, "", action = { Player.play(c.urls.first(), c.name, null, 0) { } }, play = { Player.play(c.urls.first(), c.name, null, 0) { } }) }
}
