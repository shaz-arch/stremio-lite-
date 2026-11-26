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
        Row("Google's own picker opens. Tick the videos you want in Stremonino (home movies, say)."),
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

    // ---- the videos ----------------------------------------------------------------------------

    fun screen(app: MainActivity): Screen {
        val status = Row("Getting your videos…")
        lateinit var s: Screen
        fun tools(): List<Row> = listOf(
            Row("────────────────────"),
            if (app.isPhone) Row("Pick more videos", "►", opens = { pickScreen(app, first = false) })
            else Row("Pick more on your phone: Stremonino › My Drive videos › Pick more videos."),
            Row("Disconnect Google Drive", action = {
                prefs(app).edit().remove("gdriveRefresh").remove("gdriveClient").apply(); access = ""
                app.toast("Disconnected from Google Drive"); app.back(); Home.redraw()
            }),
        )
        s = Screen("My Drive videos", listOf(status) + tools())
        s.onShow = {
            Net.async({ GDrive.videos(token(app)) }) { r ->
                r.onSuccess { list ->
                    val b = bridge(app)
                    s.rows = (if (list.isEmpty()) listOf(Row("No videos picked yet." + if (app.isPhone) " Use 'Pick more videos' below." else " Pick some on your phone."))
                    else list.map { f ->
                        Row(f.name, GDrive.nice(f) + "  ►", action = { Player.play(app, b.url(f.id, f.name), f.name.substringBeforeLast('.')) },
                            play = { Player.play(app, b.url(f.id, f.name), f.name.substringBeforeLast('.')) })
                    }) + tools()
                }.onFailure { status.label = "Couldn't get your videos: ${it.message}"; s.rows = listOf(status) + tools() }
                if (app.isTop(s)) app.refresh(s)
            }
        }
        return s
    }
}
