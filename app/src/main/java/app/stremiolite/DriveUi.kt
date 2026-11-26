package app.stremiolite

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * My Drive videos: your Google Drive videos in Stremonino. The phone signs in and picks the videos
 * in Google's own picker; the stick signs in with a code typed on the phone and sees what was picked.
 * The Google sign-in stays on each device (never synced or shared).
 */
object DriveUi {
    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")
    private var access = ""
    private var accessUntil = 0L
    private var accessFor = ""

    fun connected(c: Context) = prefs(c).getString("gdriveRefresh", "").orEmpty().isNotEmpty()
    private fun client(c: Context) = prefs(c).getString("gdriveClient", GDrive.ANDROID_ID).orEmpty()

    private fun save(c: Context, client: String, t: GDrive.Tokens) {
        prefs(c).edit().putString("gdriveRefresh", t.refresh).putString("gdriveClient", client).apply()
        access = t.access; accessUntil = t.expires; accessFor = t.refresh
    }

    /** Blocking: a working access token (refreshed when it runs out). */
    private fun token(c: Context): String {
        val refresh = prefs(c).getString("gdriveRefresh", "").orEmpty()
        if (refresh.isEmpty()) throw GDrive.Failed("Not connected to Google Drive")
        if (access.isNotEmpty() && accessFor == refresh && System.currentTimeMillis() < accessUntil) return access
        val t = GDrive.refresh(client(c), refresh)
        access = t.access; accessUntil = t.expires; accessFor = refresh
        return access
    }

    private var bridge: GDrive.Bridge? = null
    private fun bridge(c: Context): GDrive.Bridge = bridge ?: GDrive.Bridge { token(c.applicationContext) }.also { bridge = it }

    fun homeRow(app: MainActivity): Row? = if (!connected(app)) null else Row("My Drive videos", "►", opens = { screen(app) })

    fun settingsRow(app: MainActivity): Row =
        Row("Google Drive videos", (if (connected(app)) "connected" else "connect") + "  ►", opens = { if (connected(app)) screen(app) else connect(app) })

    // ---- connecting --------------------------------------------------------------------------

    /** Phone: Google's sign-in and picker in the browser, back into the app. Stick: a code. */
    fun connect(app: MainActivity): Screen = if (app.isPhone) pickScreen(app, first = true) else codeScreen(app)

    private fun openPicker(app: MainActivity) {
        val (verifier, challenge) = GDrive.pkce()
        prefs(app).edit().putString("gdriveVerifier", verifier).apply()
        try { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GDrive.pickerUrl(GDrive.ANDROID_ID, GDrive.ANDROID_REDIRECT, challenge)))) }
        catch (e: Exception) { app.toast("No browser on this phone") }
    }

    private fun pickScreen(app: MainActivity, first: Boolean) = Screen(if (first) "Connect Google Drive" else "Pick more videos", listOf(
        Row("Google's own picker opens. Tick the videos you want in Stremonino (home movies, say): you can tick many at once."),
        Row("Pick the videos themselves: Google doesn't let apps see inside a picked folder. You can group them here afterwards."),
        Row("Stremonino only ever sees the files you pick, nothing else in your Drive."),
        Row("────────────────────"),
        Row(if (first) "Sign in and pick videos" else "Open the picker", "►", action = { openPicker(app) }),
    ))

    /** The browser came back with the sign-in code and what was picked. */
    fun back(app: MainActivity, uri: Uri?) {
        if (uri == null || uri.scheme != GDrive.ANDROID_SCHEME) return
        uri.getQueryParameter("error")?.let { app.toast("Google Drive: $it"); return }
        val code = uri.getQueryParameter("code") ?: return
        val verifier = prefs(app).getString("gdriveVerifier", "").orEmpty()
        app.toast("Connecting Google Drive…")
        Net.async({ GDrive.exchange(GDrive.ANDROID_ID, code, verifier, GDrive.ANDROID_REDIRECT) }) { r ->
            r.onSuccess { t -> save(app, GDrive.ANDROID_ID, t); app.toast("Google Drive connected"); app.push(screen(app)); Home.redraw() }
                .onFailure { app.toast("Couldn't connect: ${it.message}") }
        }
    }

    /** The stick: a code to enter on the phone at google.com/device. */
    private fun codeScreen(app: MainActivity): Screen {
        val status = Row("Asking Google for a code…")
        lateinit var s: Screen
        s = Screen("Connect Google Drive", listOf(status))
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                if (GDrive.tvSecret.isEmpty()) status.label = "This build can't sign in on a TV yet (its Google secret is missing)."
                else Net.async({ GDrive.deviceStart() }) { r ->
                    val d = r.getOrElse { status.label = "Couldn't get a code: ${it.message}"; app.refresh(s); return@async }
                    s.rows = listOf(
                        Row("On your phone, go to ${d.url.removePrefix("https://")}"),
                        Row("and enter this code:   ${d.user}"),
                        Row("Then pick your videos on the phone: Stremonino › My Drive videos › Pick more videos."),
                        status.also { it.label = "Waiting for you to say yes on the phone…" },
                    )
                    s.image = Qr.make(d.url)
                    app.refresh(s)
                    fun poll() {
                        if (!app.inStack(s)) return
                        if (System.currentTimeMillis() > d.until) { status.label = "The code ran out: go back and start again"; app.refresh(s); return }
                        Net.async({ GDrive.devicePoll(d) }) { pr ->
                            pr.onSuccess { t ->
                                if (t == null) Net.later(d.interval * 1000L, Runnable { poll() })
                                else { save(app, GDrive.TV_ID, t); app.toast("Google Drive connected"); app.back(); app.push(screen(app)); Home.redraw() }
                            }.onFailure { status.label = it.message ?: "Sign-in failed"; app.refresh(s) }
                        }
                    }
                    Net.later(d.interval * 1000L, Runnable { poll() })
                }
            }
        }
        return s
    }

    // ---- the videos: groups (yours, kept on the account), hidden ones, and changes to Drive ------

    private var files: List<GDrive.File> = emptyList()

    private fun org(c: Context) = GDrive.Org.parse(History.rawRemote(c, GDrive.Org.ID))
    private fun saveOrg(c: Context, s: GDrive.Org.State) = History.putRemote(c, GDrive.Org.record(History.rawRemote(c, GDrive.Org.ID), s))

    private fun ask(app: MainActivity, title: String, initial: String, done: (String) -> Unit) {
        val input = android.widget.EditText(app).apply { setText(initial); setSingleLine(); setSelection(initial.length) }
        android.app.AlertDialog.Builder(app).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun play(app: MainActivity, f: GDrive.File) = Player.play(app, bridge(app).url(f.id, f.name), f.name.substringBeforeLast('.'))

    private fun videoRow(app: MainActivity, f: GDrive.File) =
        Row(f.name.substringBeforeLast('.'), GDrive.nice(f) + "  ►", opens = { videoScreen(app, f) }, play = { play(app, f) })

    /** [group]: one group's videos; [hidden]: the hidden ones; neither: everything. */
    fun screen(app: MainActivity, group: String? = null, hidden: Boolean = false): Screen {
        val status = Row("Getting your videos…")
        lateinit var s: Screen
        fun tools(): List<Row> = if (group != null || hidden) emptyList() else listOf(
            Row("────────────────────"),
            if (app.isPhone) Row("Pick more videos", "►", opens = { pickScreen(app, first = false) })
            else Row("Pick more on your phone: Stremonino › My Drive videos › Pick more videos."),
            Row("Disconnect Google Drive", action = {
                prefs(app).edit().remove("gdriveRefresh").remove("gdriveClient").apply(); access = ""
                app.toast("Disconnected from Google Drive"); app.back(); Home.redraw()
            }),
        )
        fun build(): List<Row> {
            val o = org(app)
            val shown = files.filter { (it.id in o.hidden) == hidden }
            val rows = mutableListOf<Row>()
            when {
                hidden -> shown.forEach { rows += videoRow(app, it) }
                group != null -> shown.filter { o.groupOf(it.id) == group }.forEach { rows += videoRow(app, it) }
                else -> {
                    o.groups.keys.sortedBy { it.lowercase() }.forEach { g ->
                        val n = shown.count { o.groupOf(it.id) == g }
                        if (n > 0) rows += Row("▸ $g", "$n  ►", opens = { screen(app, group = g) })
                    }
                    shown.filter { o.groupOf(it.id) == null }.forEach { rows += videoRow(app, it) }
                    if (files.isNotEmpty() && shown.isEmpty()) rows += Row("Every video is hidden.")
                    if (files.isEmpty()) rows += Row("No videos picked yet." + if (app.isPhone) " Use 'Pick more videos' below." else " Pick some on your phone.")
                    val h = files.count { it.id in o.hidden }
                    if (h > 0) rows += Row("Hidden videos", "$h  ►", opens = { screen(app, hidden = true) })
                }
            }
            if (rows.isEmpty()) rows += Row("Nothing here now.")
            return rows + tools()
        }
        s = Screen(when { hidden -> "Hidden Drive videos"; group != null -> group; else -> "My Drive videos" }, listOf(status) + tools())
        s.onShow = {
            if (group != null || hidden) { s.rows = build(); app.refresh(s) }
            else Net.async({ GDrive.videos(token(app)) }) { r ->
                r.onSuccess { list -> files = list; s.rows = build() }
                    .onFailure { status.label = "Couldn't get your videos: ${it.message}"; s.rows = listOf(status) + tools() }
                if (app.isTop(s)) app.refresh(s)
            }
        }
        return s
    }

    /** One video: play it, rename it, put it in a group, hide it, or bin it. */
    private fun videoScreen(app: MainActivity, f: GDrive.File): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val o = org(app)
            val g = o.groupOf(f.id)
            return listOf(
                Row(f.name), Row(listOfNotNull(GDrive.nice(f).ifEmpty { null }, g?.let { "in: $it" }).joinToString(" · ")),
                Row("────────────────────"),
                Row("Play", "►", action = { play(app, f) }),
                Row(if (g == null) "Put in a group" else "Move to another group", "►", opens = { groupPick(app, f) }),
                Row("Rename (in your Drive)", action = {
                    ask(app, "New name", f.name) { name ->
                        Net.async({ GDrive.rename(token(app), f.id, name) }) { r ->
                            r.onSuccess { files = files.map { x -> if (x.id == f.id) GDrive.File(x.id, name, x.size, x.durationMs, x.folder) else x }; app.toast("Renamed"); app.back() }
                                .onFailure { app.toast("Couldn't rename: ${it.message}") }
                        }
                    }
                }),
                Row(if (f.id in o.hidden) "Show it again" else "Hide from Stremonino (stays in your Drive)", action = {
                    saveOrg(app, org(app).hide(f.id, f.id !in org(app).hidden)); app.toast(if (f.id in org(app).hidden) "Hidden" else "Shown again"); app.back()
                }),
                Row("Move to your Drive's bin", "►", opens = {
                    confirmScreen(app, "Move to the bin?", "\"${f.name}\" goes to your Google Drive bin. Google keeps it there for 30 days, so you can restore it from drive.google.com.", "Yes, move it to the bin") {
                        Net.async({ GDrive.trash(token(app), f.id) }) { r ->
                            r.onSuccess { files = files.filter { it.id != f.id }; saveOrg(app, org(app).put(f.id, null).hide(f.id, false)); app.toast("Moved to the bin"); app.back(); app.back() }
                                .onFailure { app.toast("Couldn't: ${it.message}") }
                        }
                    }
                }),
            )
        }
        s = Screen(f.name.substringBeforeLast('.'), build())
        s.onShow = { s.rows = build(); app.refresh(s) }
        return s
    }

    private fun groupPick(app: MainActivity, f: GDrive.File): Screen {
        val o = org(app)
        val rows = mutableListOf<Row>()
        o.groups.keys.sortedBy { it.lowercase() }.forEach { g ->
            rows += Row(g, if (o.groupOf(f.id) == g) "✓" else "", action = { saveOrg(app, org(app).put(f.id, g)); app.toast("In $g"); app.back() })
        }
        rows += Row("New group…", action = { ask(app, "Group name (e.g. Holiday 2025)", "") { g -> saveOrg(app, org(app).put(f.id, g)); app.toast("In $g"); app.back() } })
        if (o.groupOf(f.id) != null) rows += Row("Not in a group", action = { saveOrg(app, org(app).put(f.id, null)); app.toast("Out of the group"); app.back() })
        return Screen("Put in a group", rows)
    }
}

/** "Files on <computer>": the films a Linux Stremonino shares on your home Wi-Fi (My files). */
object SharedFiles {
    private fun share(c: Context) = LocalFiles.Record.parse(History.rawRemote(c, LocalFiles.Record.ID))
        ?.takeIf { System.currentTimeMillis() - it.at < 3 * 86_400_000L }

    fun homeRow(app: MainActivity): Row? {
        val s = share(app) ?: return null
        return Row("Files on ${s.name}", "►", opens = { screen(app, s) })
    }

    private fun screen(app: MainActivity, share: LocalFiles.Record.Share): Screen {
        val status = Row("Asking ${share.name}…")
        lateinit var s: Screen
        s = Screen("Files on ${share.name}", listOf(status))
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                Net.async({ LocalFiles.fetch(share) }) { r ->
                    r.onSuccess { list ->
                        val rows = mutableListOf<Row>()
                        if (list.isEmpty()) rows += Row("No videos shared yet. On the computer: My files › Folders and sharing.")
                        list.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (folder, files) ->
                            if (folder.isNotEmpty()) rows += Row("──── $folder ────")
                            files.forEach { f ->
                                val go = { Player.play(app, LocalFiles.playUrl(share, f), f.name.substringBeforeLast('.')) }
                                rows += Row(f.name.substringBeforeLast('.'), LocalFiles.size(f.size) + "  ►", action = { go() }, play = { go() })
                            }
                        }
                        s.rows = rows
                    }.onFailure {
                        s.rows = listOf(Row("Couldn't reach ${share.name}."), Row("Is it switched on, with Stremonino open, on the same Wi-Fi as this device?"))
                    }
                    if (app.isTop(s)) app.refresh(s)
                }
            }
        }
        return s
    }
}

/** Playing on a smart TV on the home network (DLNA), chosen as the player in Settings. */
object Cast {
    const val PKG = "dlna"
    private var last: Dlna.Renderer? = null
    private var found: List<Dlna.Renderer> = emptyList()

    /** Finds the TVs, then plays [url] on the one you pick (the last one used goes first). */
    fun screen(app: MainActivity, url: String, title: String): Screen {
        val status = Row("Looking for TVs on your Wi-Fi…")
        lateinit var s: Screen
        fun rows(): List<Row> {
            val list = (listOfNotNull(last) + found).distinctBy { it.control }
            val out = mutableListOf(status)
            list.forEach { r ->
                out += Row(r.name, if (r.control == last?.control) "last used  ►" else "►", action = {
                    status.label = "Sending to ${r.name}…"; app.refresh(s)
                    Net.async({ Dlna.play(r, url, title) }) { res ->
                        res.onSuccess { last = r; status.label = "Playing on ${r.name}"; app.toast("Playing on ${r.name}") }
                            .onFailure { status.label = "${r.name}: ${it.message}. Some TVs can't play this kind of file or link." }
                        s.rows = rows() + controls(app, r); app.refresh(s)
                    }
                })
            }
            out += Row("Look again", action = { search(app, s, status) { s.rows = rows(); app.refresh(s) } })
            return out
        }
        s = Screen("Play on a TV", rows())
        var started = false
        s.onShow = { if (!started) { started = true; search(app, s, status) { s.rows = rows(); app.refresh(s) } } }
        return s
    }

    private fun controls(app: MainActivity, r: Dlna.Renderer) = listOf(
        Row("────────────────────"),
        Row("Pause", action = { Net.async({ Dlna.pause(r) }) { } }),
        Row("Resume", action = { Net.async({ Dlna.resume(r) }) { } }),
        Row("Stop", action = { Net.async({ Dlna.stop(r) }) { app.toast("Stopped") } }),
    )

    private fun search(app: MainActivity, s: Screen, status: Row, done: () -> Unit) {
        status.label = "Looking for TVs on your Wi-Fi…"; app.refresh(s)
        Net.async({ Dlna.discover() }) { r ->
            found = r.getOrDefault(emptyList())
            status.label = if (found.isEmpty() && last == null) "No TVs answered. Is the TV on, on the same Wi-Fi, with DLNA or screen sharing switched on?" else "Pick a TV:"
            done()
        }
    }
}
