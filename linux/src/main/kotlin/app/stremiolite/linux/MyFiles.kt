package app.stremiolite.linux

import app.stremiolite.LocalFiles
import java.io.File

/** My files: films and videos in your own folders (an external drive, Google Drive mounted with rclone). */
object MyFiles {
    private fun folders() = Prefs.myFolders.map { File(it) }

    fun homeRow(): Row? = if (Prefs.myFolders.isEmpty()) null else Row("My files", "${Prefs.myFolders.size} folder" + (if (Prefs.myFolders.size == 1) "" else "s") + "  ►", opens = { root() })

    private fun root(): Screen {
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
