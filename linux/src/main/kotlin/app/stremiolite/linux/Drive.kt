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

    fun screen(): Screen {
        val status = Row("Getting your videos…")
        fun tools() = listOf(
            Row("────────────────────"),
            Row("Pick more videos", "browser  ►", action = { signIn() }),
            Row("Disconnect Google Drive", action = { Prefs.gdriveRefresh = ""; access = ""; App.toast("Disconnected from Google Drive"); App.back(); Home.redraw() }),
        )
        val s = Screen("My Drive videos", listOf(status) + tools())
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                App.async({ GDrive.videos(token()) }) { r ->
                    r.onSuccess { list ->
                        s.rows = (if (list.isEmpty()) listOf(Row("No videos picked yet. Use 'Pick more videos' below."))
                        else list.map { f ->
                            val go = { Player.play(bridge.url(f.id, f.name), f.name.substringBeforeLast('.'), null, 0) { } }
                            Row(f.name, GDrive.nice(f) + "  ►", action = { go() }, play = { go() })
                        }) + tools()
                    }.onFailure { status.label = "Couldn't get your videos: ${it.message}"; s.rows = listOf(status) + tools() }
                    App.refresh()
                }
            }
        }
        return s
    }
}
