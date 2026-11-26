package app.stremiolite.linux

import app.stremiolite.GDrive
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/** My Drive videos on Linux: sign in and pick in your browser, play in mpv through the local bridge. */
object DriveUi {
    private var access = ""
    private var accessUntil = 0L
    private var accessFor = ""

    fun connected() = Prefs.gdriveRefresh.isNotEmpty()

    private fun token(): String {
        val refresh = Prefs.gdriveRefresh
        if (refresh.isEmpty()) throw GDrive.Failed("Not connected to Google Drive")
        if (access.isNotEmpty() && accessFor == refresh && System.currentTimeMillis() < accessUntil) return access
        val t = GDrive.refresh(GDrive.DESKTOP_ID, refresh)
        access = t.access; accessUntil = t.expires; accessFor = refresh
        return access
    }

    private val bridge by lazy { GDrive.Bridge { token() } }

    fun homeRow(): Row? = if (!connected()) null else Row("My Drive videos", "►", opens = { screen() })
    fun settingsRow(): Row = Row("Google Drive videos", (if (connected()) "connected" else "connect") + "  ►", action = {
        if (connected()) App.push(screen()) else signIn()
    })

    /** Opens Google's sign-in and picker in the browser; it comes back to a one-time local address. */
    private fun signIn() {
        if (GDrive.desktopSecret.isEmpty()) { App.toast("This build can't sign in to Google Drive yet (its Google secret is missing)"); return }
        val server = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }.getOrElse { App.toast("Couldn't start the sign-in: ${it.message}"); return }
        val redirect = "http://127.0.0.1:${server.localPort}"
        val (verifier, challenge) = GDrive.pkce()
        WebLink.open(GDrive.pickerUrl(GDrive.DESKTOP_ID, redirect, challenge))
        App.toast("Sign in and pick your videos in the browser…", 8_000)
        Thread {
            runCatching {
                server.soTimeout = 10 * 60_000
                server.accept().use { c ->
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                    val q = line.split(' ').getOrNull(1).orEmpty().substringAfter('?', "")
                    val params = q.split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { (k, v) -> k to URLDecoder.decode(v, "UTF-8") }
                    val ok = params["code"] != null
                    val page = "<html><body style='font-family:sans-serif'><h2>" + (if (ok) "Done. You can close this tab and go back to Stremonino." else "Sign-in didn't finish: " + (params["error"] ?: "no code")) + "</h2></body></html>"
                    c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page").toByteArray())
                    val code = params["code"] ?: throw GDrive.Failed(params["error"] ?: "no code")
                    val t = GDrive.exchange(GDrive.DESKTOP_ID, code, verifier, redirect)
                    App.post { Prefs.gdriveRefresh = t.refresh; access = t.access; accessUntil = t.expires; accessFor = t.refresh; App.toast("Google Drive connected"); App.push(screen()); Home.redraw() }
                }
            }.onFailure { e -> App.post { App.toast("Google Drive: ${e.message}") } }
            runCatching { server.close() }
        }.apply { isDaemon = true }.start()
    }

    private var files: List<GDrive.File> = emptyList()
    private fun org() = GDrive.Org.parse(Lib.raw(GDrive.Org.ID)?.toString())
    private fun saveOrg(st: GDrive.Org.State) = Lib.put(GDrive.Org.record(Lib.raw(GDrive.Org.ID)?.toString(), st))
    private fun play(f: GDrive.File) = Player.play(bridge.url(f.id, f.name), f.name.substringBeforeLast('.'), null, 0) { }
    private fun row(f: GDrive.File) = Row(f.name.substringBeforeLast('.'), GDrive.nice(f) + "  ►", opens = { video(f) }, play = { play(f) })

    /** [group]: one group; [hidden]: the hidden videos; neither: everything (fetched fresh). */
    fun screen(group: String? = null, hidden: Boolean = false): Screen {
        val status = Row("Getting your videos…")
        fun tools() = if (group != null || hidden) emptyList() else listOf(
            Row("────────────────────"),
            Row("Pick more videos", "browser  ►", action = { signIn() }),
            Row("Disconnect Google Drive", action = { Prefs.gdriveRefresh = ""; access = ""; App.toast("Disconnected from Google Drive"); App.back(); Home.redraw() }),
        )
        fun build(): List<Row> {
            val o = org()
            val shown = files.filter { (it.id in o.hidden) == hidden }
            val rows = mutableListOf<Row>()
            when {
                hidden -> shown.forEach { rows += row(it) }
                group != null -> shown.filter { o.groupOf(it.id) == group }.forEach { rows += row(it) }
                else -> {
                    o.groups.keys.sortedBy { it.lowercase() }.forEach { g ->
                        val n = shown.count { o.groupOf(it.id) == g }
                        if (n > 0) rows += Row("▸ $g", "$n  ►", opens = { screen(group = g) })
                    }
                    shown.filter { o.groupOf(it.id) == null }.forEach { rows += row(it) }
                    if (files.isEmpty()) rows += Row("No videos picked yet. Use 'Pick more videos' below.")
                    val h = files.count { it.id in o.hidden }
                    if (h > 0) rows += Row("Hidden videos", "$h  ►", opens = { screen(hidden = true) })
                }
            }
            if (rows.isEmpty()) rows += Row("Nothing here now.")
            return rows + tools()
        }
        val s = Screen(when { hidden -> "Hidden Drive videos"; group != null -> group; else -> "My Drive videos" }, listOf(status) + tools())
        s.onShow = {
            if (group != null || hidden) { s.rows = build(); App.refresh() }
            else App.async({ GDrive.videos(token()) }) { r ->
                r.onSuccess { list -> files = list; s.rows = build() }
                    .onFailure { status.label = "Couldn't get your videos: ${it.message}"; s.rows = listOf(status) + tools() }
                App.refresh()
            }
        }
        return s
    }

    private fun video(f: GDrive.File): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val o = org(); val g = o.groupOf(f.id)
            return listOf(
                Row(f.name), Row(listOfNotNull(GDrive.nice(f).ifEmpty { null }, g?.let { "in: $it" }).joinToString(" · ")),
                Row("────────────────────"),
                Row("Play", "►", action = { play(f) }),
                Row(if (g == null) "Put in a group" else "Move to another group", "►", opens = { groupPick(f) }),
                Row("Rename (in your Drive)", action = {
                    App.ask("New name", f.name) { name ->
                        val n = name?.trim().orEmpty()
                        if (n.isNotEmpty()) App.async({ GDrive.rename(token(), f.id, n) }) { r ->
                            r.onSuccess { files = files.map { x -> if (x.id == f.id) GDrive.File(x.id, n, x.size, x.durationMs, x.folder) else x }; App.toast("Renamed"); App.back() }
                                .onFailure { App.toast("Couldn't rename: ${it.message}") }
                        }
                    }
                }),
                Row(if (f.id in o.hidden) "Show it again" else "Hide from Stremonino (stays in your Drive)", action = {
                    val on = f.id !in org().hidden; saveOrg(org().hide(f.id, on)); App.toast(if (on) "Hidden" else "Shown again"); App.back()
                }),
                Row("Move to your Drive's bin", "►", opens = {
                    confirmScreen("Move to the bin?", "\"${f.name}\" goes to your Google Drive bin, kept 30 days (restore it at drive.google.com).", "Yes, move it to the bin") {
                        App.async({ GDrive.trash(token(), f.id) }) { r ->
                            r.onSuccess { files = files.filter { it.id != f.id }; saveOrg(org().put(f.id, null).hide(f.id, false)); App.toast("Moved to the bin"); App.back(); App.back() }
                                .onFailure { App.toast("Couldn't: ${it.message}") }
                        }
                    }
                }),
            )
        }
        s = Screen(f.name.substringBeforeLast('.'), build())
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    private fun groupPick(f: GDrive.File): Screen {
        val o = org()
        val rows = o.groups.keys.sortedBy { it.lowercase() }.map { g -> Row(g, if (o.groupOf(f.id) == g) "✓" else "", action = { saveOrg(org().put(f.id, g)); App.toast("In $g"); App.back() }) }.toMutableList()
        rows += Row("New group…", action = { App.ask("Group name (e.g. Holiday 2025)") { v -> v?.trim()?.takeIf { it.isNotEmpty() }?.let { g -> saveOrg(org().put(f.id, g)); App.toast("In $g"); App.back() } } })
        if (o.groupOf(f.id) != null) rows += Row("Not in a group", action = { saveOrg(org().put(f.id, null)); App.toast("Out of the group"); App.back() })
        return Screen("Put in a group", rows)
    }
}
