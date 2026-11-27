package app.stremiolite.linux

import app.stremiolite.LocalFiles
import java.io.File

/** My files: films and videos in your own folders (an external drive, Google Drive mounted with rclone). */
object MyFiles {
    private fun folders() = Prefs.myFolders.map { File(it) }

    fun homeRow(): Row? = if (Prefs.myFolders.isEmpty()) null else Row("My files", "${Prefs.myFolders.size} folder" + (if (Prefs.myFolders.size == 1) "" else "s") + "  ►", opens = { root() })

    fun root(): Screen {
        val rows = folders().map { d ->
            if (d.isDirectory) Row(d.name.ifEmpty { d.path }, "►", opens = { folder(d) })
            else Row(d.name.ifEmpty { d.path }, "not plugged in or not mounted")
        } + listOf(Row("────────────────────"), Row("Folders and sharing", "►", opens = { settings() }))
        return Screen("My files", rows)
    }

    private fun mmss(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    private fun folder(d: File): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val (dirs, vids) = LocalFiles.list(d)
            if (dirs.isEmpty() && vids.isEmpty()) return listOf(Row("No videos in here."))
            return dirs.map { sub -> Row("▸ " + sub.name, "►", opens = { folder(sub) }) } + vids.map { f ->
                val at = Prefs.fileResume(f.absolutePath)
                val mark = if (Prefs.fileWatched(f.absolutePath)) app.stremiolite.Marks.WATCHED else if (at > 0) app.stremiolite.Marks.CONTINUE else null
                Row(app.stremiolite.Marks.title(mark, f.nameWithoutExtension), listOfNotNull(if (at > 0) "from ${mmss(at)}" else null, LocalFiles.size(f.length())).joinToString(" · ") + "  ►",
                    action = { play(f) { s.rows = build(); App.refresh() } }, play = { play(f) { s.rows = build(); App.refresh() } })
            }
        }
        s = Screen(d.name, build())
        return s
    }

    private fun play(f: File, after: () -> Unit) {
        val start = Prefs.fileResume(f.absolutePath)
        Player.play(f.absolutePath, f.nameWithoutExtension, null, start) { r ->
            val watched = r.dur > 0 && r.pos >= r.dur * 0.92
            if (!r.failed) Prefs.setFileProgress(f.absolutePath, r.pos, watched)
            after()
        }
    }

    // ---- settings: the folders, and sharing with the TV ---------------------------------------

    fun settingsRow() = Row("My files folders", (if (Prefs.myFolders.isEmpty()) "none" else "${Prefs.myFolders.size}") + "  ►", opens = { settings() })

    private fun settings(): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val rows = mutableListOf(Row("──── Folders ────"))
            if (Prefs.myFolders.isEmpty()) rows += Row("None yet.")
            Prefs.myFolders.forEach { p ->
                rows += Row(p, "remove", action = { Prefs.myFolders = Prefs.myFolders - p; s.rows = build(); App.refresh(); Home.redraw() })
            }
            LocalFiles.mountedDrives().filter { it.absolutePath !in Prefs.myFolders }.forEach { d ->
                rows += Row("Add plugged-in drive: ${d.name}", "►", action = { Prefs.myFolders = Prefs.myFolders + d.absolutePath; s.rows = build(); App.refresh(); Home.redraw() })
            }
            rows += Row("Add a folder by its path", "►", action = {
                App.ask("Folder (e.g. ~/Videos or ~/GoogleDrive/Home movies)") { v ->
                    val path = v?.trim()?.replaceFirst(Regex("^~"), System.getProperty("user.home"))?.trimEnd('/') ?: return@ask
                    if (path.isEmpty()) return@ask
                    if (!File(path).isDirectory) App.toast("That folder isn't there (is the drive plugged in?)")
                    else { Prefs.myFolders = (Prefs.myFolders + path).distinct(); s.rows = build(); App.refresh(); Home.redraw() }
                }
            })
            rows += Row("──── Sharing ────")
            rows += Row("Share My files with the TV", "◄ ${if (Prefs.shareFiles) "On" else "Off"} ►", action = {
                Prefs.shareFiles = !Prefs.shareFiles
                if (Prefs.shareFiles) Share.start(announce = true) else Share.stop()
                s.rows = build(); App.refresh()
            })
            rows += Row(if (Prefs.shareFiles) "The TV and phone see these under 'Files on ${Share.pcName}' while Stremonino is open here, on the same Wi-Fi."
                else "On: the TV and phone can play these over your home Wi-Fi while Stremonino is open here.")
            if (Prefs.shareFiles) rows += Row("Make a new share key", action = { Prefs.shareKey = ""; Share.stop(); Share.start(announce = true); App.toast("Old links no longer work") })
            return rows
        }
        s = Screen("My files: folders and sharing", build())
        return s
    }

    /** Sharing over the home network; where to find it goes on the account so the TV knows. */
    object Share {
        val pcName: String by lazy { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("this computer") }
        private var server: LocalFiles.Server? = null

        fun start(announce: Boolean = false) {
            val ip = LocalFiles.lanAddress() ?: run { if (announce) App.toast("No home network found to share on"); return }
            val srv = server ?: LocalFiles.Server({ Prefs.myFolders.map { File(it) } }, Prefs.shareKey).also { server = it }
            val port = runCatching { srv.start() }.getOrElse { if (announce) App.toast("Couldn't share: ${it.message}"); return }
            val share = LocalFiles.Record.Share("http://$ip:$port/${Prefs.shareKey}", pcName, System.currentTimeMillis())
            Lib.put(LocalFiles.Record.record(Lib.raw(LocalFiles.Record.ID)?.toString(), share))
            if (announce) App.toast("Sharing on $ip: the TV shows 'Files on $pcName'")
        }

        fun stop() {
            server?.stop(); server = null
            Lib.put(LocalFiles.Record.record(Lib.raw(LocalFiles.Record.ID)?.toString(), null))
        }

        /** At start-up, and every few hours, so the TV knows it's still here. */
        fun keepUp() {
            Thread {
                Thread.sleep(5_000)
                while (true) {
                    if (Prefs.shareFiles && Account.authKey != null) App.post { start() }
                    Thread.sleep(6 * 3_600_000L)
                }
            }.apply { isDaemon = true }.start()
        }
    }
}

/** Playing on a smart TV on the home network (DLNA) instead of mpv, when switched on in Settings. */
object Cast {
    private var last: app.stremiolite.Dlna.Renderer? = null

    fun screen(url: String, title: String): Screen {
        val D = app.stremiolite.Dlna
        val status = Row("Looking for TVs on your Wi-Fi…")
        val s = Screen("Play on a TV", listOf(status))
        App.async({ D.discover() }) { r ->
            val list = (listOfNotNull(last) + r.getOrDefault(emptyList())).distinctBy { it.control }
            status.label = if (list.isEmpty()) "No TVs answered. Is the TV on, on the same Wi-Fi, with DLNA switched on?" else "Pick a TV:"
            s.rows = listOf(status) + list.map { tv ->
                Row(tv.name, "►", action = {
                    App.async({ D.play(tv, url, title) }) { res ->
                        res.onSuccess { last = tv; App.toast("Playing on ${tv.name}") }.onFailure { App.toast("${tv.name}: ${it.message}") }
                    }
                })
            } + if (last != null) listOf(
                Row("────────────────────"),
                Row("Pause", action = { last?.let { tv -> App.async({ D.pause(tv) }) { } } }),
                Row("Resume", action = { last?.let { tv -> App.async({ D.resume(tv) }) { } } }),
                Row("Stop", action = { last?.let { tv -> App.async({ D.stop(tv) }) { } } }),
            ) else emptyList()
            App.refresh()
        }
        return s
    }
}

/** "Files on <phone>": a phone sharing its videos (Share this phone's videos), played in mpv. */
object PhoneFiles {
    private fun share() = LocalFiles.Record.parse(Lib.raw(LocalFiles.Record.PHONE_ID)?.toString())?.takeIf { System.currentTimeMillis() - it.at < 3 * 86_400_000L }

    fun source(): Drives.Source? { val s = share() ?: return null; return Drives.Source("phone", s.name) { screen(s) } }

    private fun screen(sh: LocalFiles.Record.Share): Screen {
        val s = Screen("Files on ${sh.name}", listOf(Row("Asking ${sh.name}…")))
        App.async({ LocalFiles.fetch(sh) }) { r ->
            r.onSuccess { list ->
                fun row(f: LocalFiles.Shared): Row {
                    val go = { Player.play(LocalFiles.playUrl(sh, f), f.name.substringBeforeLast('.'), null, 0) { } }
                    val date = if (f.modified > 0) java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(f.modified)) + " · " else ""
                    return Row(f.name.substringBeforeLast('.'), date + LocalFiles.size(f.size) + "  ►", action = { go() }, play = { go() })
                }
                val rows = mutableListOf<Row>()
                if (list.isEmpty()) rows += Row("No videos found on ${sh.name}.")
                else {
                    rows += Row("──── Recently added ────")
                    LocalFiles.newest(list).take(8).forEach { rows += row(it) }
                    rows += Row("──── Folders ────")
                    list.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (folder, files) ->
                        rows += Row("▸ " + folder.ifEmpty { "(top)" }, "${files.size}  ►", opens = { Screen(folder.substringAfterLast('/').ifEmpty { sh.name }, LocalFiles.newest(files).map { row(it) }) })
                    }
                }
                s.rows = rows
            }.onFailure { s.rows = listOf(Row("Couldn't reach ${sh.name}."), Row("Is Stremonino open on it, with the screen on, on the same Wi-Fi?")) }
            App.refresh()
        }
        return s
    }
}

/** Google Drive, My files and a phone sharing on your Wi-Fi: one "Drives" line on Home, each one shown there or not. */
object Drives {
    class Source(val key: String, val name: String, val opens: () -> Screen)

    private fun onHome(key: String) = key !in Prefs.drivesOffHome

    fun sources(): List<Source> = listOfNotNull(
        if (DriveUi.connected()) Source("gdrive", "Google Drive") { DriveUi.screen() } else null,
        if (Prefs.myFolders.isNotEmpty()) Source("myfiles", "My files") { MyFiles.root() } else null,
        PhoneFiles.source(),
    )

    fun homeRow(): Row? {
        val shown = sources().filter { onHome(it.key) }
        if (shown.isEmpty()) return null
        return Row("Drives", "${shown.size}  ►", opens = { Screen("Drives", shown.map { Row(it.name, "►", opens = it.opens) }) })
    }

    fun settingsRow() = Row("Drives", sources().size.let { if (it == 0) "none" else "$it" } + "  ►", opens = { settings() })

    private fun settings(): Screen {
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf<Row>()
            fun toggle(key: String) {
                if (sources().none { it.key == key }) return
                rows += Row("   On Home", "◄ ${if (onHome(key)) "Shown" else "Hidden"} ►", action = {
                    Prefs.drivesOffHome = if (onHome(key)) Prefs.drivesOffHome + key else Prefs.drivesOffHome - key
                    Home.redraw(); build()
                })
            }
            rows += Row("──── Google Drive ────")
            rows += DriveUi.settingsRow(); toggle("gdrive")
            rows += Row("──── This computer ────")
            rows += MyFiles.settingsRow(); toggle("myfiles")
            rows += Row("──── Shared on your Wi-Fi ────")
            val phone = PhoneFiles.source()
            if (phone == null) rows += Row("None.") else { rows += Row(phone.name, "►", opens = phone.opens); toggle("phone") }
            s.rows = rows; App.refresh()
        }
        s = Screen("Drives", emptyList())
        s.onShow = { build() }
        return s
    }
}

/**
 * Play a link: any video page from the sites yt-dlp supports (YouTube, Vimeo, Twitch, news sites…)
 * plays in mpv, with where you got to remembered. Recent links are kept on this computer.
 */
object PlayLink {
    private fun recent(): List<Pair<String, String>> = Prefs.recentLinks
    fun homeRow() = Row("Play a link", "►", opens = { screen() })

    fun screen(): Screen {
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf(Row("Paste a link", "►", action = { App.ask("Video page link") { v -> v?.trim()?.takeIf { it.startsWith("http") }?.let { play(it, null) { build() } } } }))
            val list = recent()
            if (list.isNotEmpty()) {
                rows += Row("──── Recent ────")
                list.forEach { (url, title) ->
                    val at = Prefs.fileResume(url)
                    val mark = if (Prefs.fileWatched(url)) "✓" else if (at > 0) "from ${clockOf(at)}" else ""
                    rows += Row(title.ifEmpty { url }, listOf(mark, runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault("")).filter { it.isNotEmpty() }.joinToString(" · ") + "  ►",
                        action = { play(url, title) { build() } }, play = { play(url, title) { build() } })
                }
                rows += Row("Clear the list", action = { Prefs.recentLinks = emptyList(); build() })
            }
            s.rows = rows; App.refresh()
        }
        s = Screen("Play a link", emptyList())
        s.onShow = { build() }
        return s
    }

    private fun clockOf(ms: Long): String { val t = ms / 1000; return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }

    private fun play(url: String, known: String?, after: () -> Unit) {
        if (!java.io.File(Prefs.player).name.startsWith("mpv")) { App.toast("Play a link needs mpv as your player (Settings › Player)"); return }
        val title = known ?: runCatching { java.net.URL(url).host.removePrefix("www.") }.getOrDefault("Video")
        remember(url, title)
        // The page's real title, from yt-dlp, for the list.
        if (known.isNullOrEmpty()) App.async({
            val p = ProcessBuilder("yt-dlp", "--no-warnings", "--skip-download", "--print", "title", url).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim().lines().lastOrNull().orEmpty()
            if (p.waitFor() == 0) out else null
        }) { r -> r.getOrNull()?.takeIf { it.isNotBlank() }?.let { remember(url, it); App.refresh() } }
        Player.play(url, title, null, Prefs.fileResume(url)) { r ->
            if (r.failed) App.toast("That link didn't play (${r.why})")
            else Prefs.setFileProgress(url, r.pos, r.dur > 0 && r.pos >= r.dur * 0.92)
            after()
        }
    }

    private fun remember(url: String, title: String) {
        Prefs.recentLinks = (listOf(url to title) + recent().filter { it.first != url }).take(20)
    }
}
