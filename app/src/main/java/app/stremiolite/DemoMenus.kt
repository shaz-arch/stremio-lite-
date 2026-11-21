package app.stremiolite

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Step 1 test menus. The section names mirror the AIOMetadata layout, but every
 * list is made-up sample data. Step 3 replaces this with the real rows from the
 * add-ons on the Stremio account.
 */
object DemoMenus {

    fun home(app: MainActivity) = Screen(
        title = "Stremio Lite",
        tag = "demo",
        rows = listOf(
            sample(app, "Top 100 Today"),
            sample(app, "Trending"),
            sample(app, "Latest Movies"),
            sample(app, "Latest Shows"),
            section(app, "Streaming", "Netflix Top 10", "Netflix", "Prime Video Top 10", "Prime Video",
                "Apple TV+ Top 10", "Apple TV+", "Disney+ Top 10", "Disney+"),
            section(app, "More Streaming", "Top 10 on HBO Max, Paramount+, Hulu & Peacock",
                "HBO Max, Paramount+, Hulu & Peacock", "Discovery & National Geographic", "UK TV"),
            section(app, "Indian", "Latest Bollywood Releases", "Popular Bollywood"),
            section(app, "Kids & Family", "Popular Kids", "Disney Kids", "90s Disney", "90s Kids Shows",
                "Animated Favourites"),
            sample(app, "Sitcoms"),
            section(app, "Genres", "Action", "Adventure", "Comedy", "Crime", "Horror", "Mind-Bending",
                "Mystery", "Sci-Fi"),
            section(app, "Studios & Directors", "Marvel Studios", "Great Directors"),
            section(app, "Collections", "Popular Film Series", "Film Series with New Releases"),
            section(app, "IMDb Top 250", "IMDb Top 250 Movies", "IMDb Top 250 Shows"),
            section(app, "Docs", "Popular Sports Docs", "Popular Documentaries", "Popular Nature"),
            Row("────────────────────"),
            Row("Device check", opens = { deviceCheck(app) }),
            Row("About", opens = { about() }),
        ),
    )

    private fun section(app: MainActivity, name: String, vararg rows: String) =
        Row(name, opens = { Screen(name, rows.map { sample(app, it) }) })

    /** A long list (300 lines) to test scrolling and page jumps. */
    private fun sample(app: MainActivity, name: String) = Row(name, opens = {
        Screen(name, (1..300).map { i ->
            val series = i % 3 == 0
            val year = 1990 + (i * 7) % 36
            val rating = 5.0 + (i * 13) % 45 / 10.0
            val kind = if (series) "Series" else "Movie"
            Row(
                label = "Sample ${kind.lowercase()} ${i.toString().padStart(3, '0')}",
                detail = "$year  ★ ${"%.1f".format(rating)}  ${kind.padEnd(6)}",
                opens = { details(app, "Sample ${kind.lowercase()} $i", year, rating, series) },
            )
        })
    })

    private fun details(app: MainActivity, name: String, year: Int, rating: Double, series: Boolean): Screen {
        val info = listOf(
            Row("Year", year.toString()),
            Row("Rating", "★ ${"%.1f".format(rating)}"),
            Row("Runtime", if (series) "45 min episodes" else "1h 52m"),
            Row("A made-up description, split over lines the way the real"),
            Row("details screen will show it, so long text stays readable"),
            Row("from the sofa."),
            Row("────────────────────"),
        )
        val next = if (series) {
            (1..3).map { s ->
                Row("Season $s", opens = {
                    Screen("$name › Season $s", (1..10).map { e ->
                        Row("Episode $e", "S${s}E${e.toString().padStart(2, '0')}",
                            opens = { streams(app, "$name › S${s}E$e") })
                    })
                })
            }
        } else {
            listOf(Row("Streams", opens = { streams(app, name) }))
        }
        return Screen(name, info + next)
    }

    private fun streams(app: MainActivity, name: String) = Screen(
        "$name › Streams",
        listOf(
            Row("2160p  HDR  DV    24.1 GB  [RD+]", "Sample add-on A"),
            Row("1080p  WEB-DL      4.2 GB  [RD+]", "Sample add-on A"),
            Row("1080p  HEVC        1.9 GB  [RD+]", "Sample add-on B"),
            Row("720p   WEB-DL      1.1 GB  [RD+]", "Sample add-on B"),
        ).map { r -> Row(r.label, r.detail, action = { app.toast("Real playback arrives in step 5. Try Device check › Test VLC.") }) },
    )

    fun deviceCheck(app: MainActivity): Screen {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        fun yesNo(b: Boolean) = if (b) "installed" else "not installed"

        // Values sit right after their labels so they're easy to read and photograph.
        fun line(label: String, value: String) = Row(info(label, value))
        val vlc = Player.isInstalled(app, Player.VLC)
        val just = Player.isInstalled(app, Player.JUST_PLAYER)

        return Screen("Device check", listOf(
            line("Device", "${Build.MANUFACTURER} ${Build.MODEL}"),
            line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            line("RAM total", "${mem.totalMem / mb} MB"),
            line("RAM free now", "${mem.availMem / mb} MB"),
            line("Low on memory?", if (mem.lowMemory) "YES" else "no"),
            line("Memory allowed per app", "${am.memoryClass} MB"),
            line("This app is using", "${(rt.totalMemory() - rt.freeMemory()) / mb} MB"),
            line("VLC", yesNo(vlc)),
            line("Just Player", yesNo(just)),
            line("Rescued by bundled certs", "${Trust.rescued} connection(s)"),
            Row("────────────────────"),
            letsEncryptTest(app),
            Row("Test VLC: play the 20-second test clip", action = {
                if (vlc) Player.play(app, Player.TEST_CLIP, "Stremio Lite test clip")
                else app.toast("VLC isn't installed. Get it from the Appstore.")
            }),
            Row("Test Just Player: play the test clip", action = {
                if (just) Player.play(app, Player.TEST_CLIP, "Stremio Lite test clip", Player.JUST_PLAYER)
                else app.toast("Just Player isn't installed. That's fine: VLC is the default.")
            }),
        ))
    }

    fun about() = Screen("About", listOf(
        Row(info("Stremio Lite", BuildConfigLite.VERSION)),
        Row("Step 4 of 6: full details, seasons and episodes"),
        Row("Next: streams, played in VLC."),
    ))

    /** Fetches a Let's Encrypt test page: the kind of site old Android can't verify on its own. */
    private fun letsEncryptTest(app: MainActivity): Row {
        lateinit var row: Row
        row = Row("Test a Let's Encrypt site (like many add-ons)", action = {
            row.detail = "testing…"
            app.refreshTop()
            val before = Trust.rescued
            Net.async({ Net.get("https://valid-isrgrootx1.letsencrypt.org/", timeoutMs = 10_000) }) { r ->
                row.detail = r.fold(
                    { if (Trust.rescued > before) "OK (bundled certs)" else "OK (stick's own)" },
                    { e -> "FAILED: ${Net.describe(e)}" },
                )
                app.refreshTop()
            }
        })
        return row
    }
}

/** Kept by hand so the build needs no BuildConfig generation. */
object BuildConfigLite {
    const val VERSION = "0.4.0"
}
