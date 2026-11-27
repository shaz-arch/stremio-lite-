package app.stremiolite

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Films and episodes saved on the phone for watching without internet. Android's own download service
 * does the work (in the background, with a notification); playing goes through a small server on this
 * phone only, so VLC can read the app's own folder.
 */
object Offline {
    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")
    private fun store(c: Context) = File(c.filesDir, Profiles.fileName(c, "downloads"))

    fun dir(c: Context): File = File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: c.filesDir, "Stremonino").also { it.mkdirs() }

    fun cap(c: Context) = prefs(c).getInt("downloadCap", 3)
    fun mobileData(c: Context) = prefs(c).getBoolean("downloadMobile", false)
    fun deleteWatched(c: Context) = prefs(c).getBoolean("downloadDeleteWatched", false)

    @Synchronized fun all(c: Context): MutableList<Downloads.Item> = Downloads.parse(runCatching { store(c).readText() }.getOrNull())
    @Synchronized private fun save(c: Context, list: List<Downloads.Item>) { runCatching { store(c).writeText(Downloads.write(list)) } }
    @Synchronized private fun update(c: Context, key: String, change: (Downloads.Item) -> Unit) {
        val list = all(c); list.firstOrNull { it.key == key }?.let(change); save(c, list)
    }

    fun has(c: Context, key: String) = all(c).any { it.key == key }
    private fun dm(c: Context) = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // ---- starting -------------------------------------------------------------------------------

    /** Downloads [s] for [w] (a stream you picked). */
    fun start(app: MainActivity, w: History.Watch, title: String, s: Stream, quiet: Boolean = false) {
        if (has(app, w.videoId)) { all(app).first { it.key == w.videoId }.let { if (it.status != Downloads.FAILED) { if (!quiet) app.toast("Already in Downloads"); return } }; remove(app, w.videoId) }
        val name = Downloads.fileName(title, w.videoId, Downloads.ext(s))
        val file = File(dir(app), name)
        val req = DownloadManager.Request(Uri.parse(s.url))
            .setTitle(title)
            .setDescription("Stremonino")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_MOVIES, "Stremonino/$name")
            .setAllowedNetworkTypes(if (mobileData(app)) DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE else DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverRoaming(false)
        req.setAllowedOverMetered(mobileData(app))
        req.addRequestHeader("User-Agent", Http.userAgent)
        val id = runCatching { dm(app).enqueue(req) }.getOrElse { app.toast("Couldn't start the download: ${it.message}"); return }
        val item = Downloads.Item(w.videoId, title, w.toJson(), file.absolutePath, s.url, s.quality + (if (s.size.isNotEmpty()) " · " + s.size else ""), Downloads.RUNNING, sysId = id)
        synchronized(this) { val list = all(app); list.removeAll { it.key == item.key }; list += item; save(app, list) }
        if (!quiet) app.toast("Downloading: $title" + if (!mobileData(app)) " (on Wi-Fi)" else "")
        // Subtitles in your language, saved next to it.
        val lang = Settings.subtitleLanguage(app)
        val addons = Home.allAddons()
        val ctx = app.applicationContext
        Net.async({
            val sub = Downloads.subtitle(addons, w.type, w.videoId, lang) ?: return@async null
            val srt = File(file.parentFile, file.nameWithoutExtension + ".srt")
            Downloads.fetchTo(sub.url, srt)
            sub.lang
        }) { r -> r.getOrNull()?.let { l -> update(ctx, item.key) { it.sub = l } } }
    }

    /** Picks and downloads the best-fitting stream for each episode, one after another. */
    fun startMany(app: MainActivity, list: List<Pair<History.Watch, String>>, minutes: Double) {
        if (list.isEmpty()) return
        app.toast("Finding streams for ${list.size} episode" + (if (list.size == 1) "" else "s") + "…")
        val addons = Home.allAddons()
        val cap = cap(app)
        var missed = 0
        fun next(i: Int) {
            if (i >= list.size) {
                app.toast(if (missed == 0) "All ${list.size} downloading" else "${list.size - missed} downloading, $missed had no stream to download")
                return
            }
            val (w, title) = list[i]
            if (has(app, w.videoId) && all(app).first { it.key == w.videoId }.status != Downloads.FAILED) { next(i + 1); return }
            Net.async({ Downloads.pick(addons, w.type, w.videoId, minutes, cap) }) { r ->
                val s = r.getOrNull()
                if (s == null) missed++ else start(app, w, title, s, quiet = true)
                next(i + 1)
            }
        }
        next(0)
    }

    // ---- keeping track ----------------------------------------------------------------------------

    /** Asks the download service how each one is doing. */
    fun refresh(c: Context) {
        val list = all(c)
        var changed = false
        for (it in list.filter { it.status == Downloads.RUNNING || it.status == Downloads.QUEUED }) {
            val cur = runCatching { dm(c).query(DownloadManager.Query().setFilterById(it.sysId)) }.getOrNull()
            if (cur == null || !cur.moveToFirst()) { cur?.close(); it.status = Downloads.FAILED; it.why = "stopped"; changed = true; continue }
            fun col(n: String) = cur.getColumnIndex(n)
            val st = cur.getInt(col(DownloadManager.COLUMN_STATUS))
            it.got = cur.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            it.total = cur.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
            val reason = cur.getInt(col(DownloadManager.COLUMN_REASON))
            cur.close()
            when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (File(it.file).length() in 1 until Downloads.MIN_BYTES) {
                        // An error clip ("file unavailable"), not the film: don't keep it.
                        File(it.file).delete(); it.status = Downloads.FAILED; it.why = "no playable video in that copy"
                    } else { it.status = Downloads.DONE; it.why = ""; server?.rescan() }
                }
                DownloadManager.STATUS_FAILED -> { it.status = Downloads.FAILED; it.why = if (reason in 400..599) "the link stopped working" else "failed" }
                DownloadManager.STATUS_PAUSED -> { it.status = Downloads.RUNNING; it.why = when (reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "waiting for Wi-Fi"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for a connection"
                    else -> "paused" } }
                DownloadManager.STATUS_PENDING -> { it.status = Downloads.RUNNING; it.why = "waiting" }
                else -> { it.status = Downloads.RUNNING; it.why = "" }
            }
            changed = true
        }
        if (changed) save(c, list)
    }

    fun remove(c: Context, key: String) {
        val item = all(c).firstOrNull { it.key == key } ?: return
        if (item.sysId >= 0) runCatching { dm(c).remove(item.sysId) }
        File(item.file).delete()
        File(File(item.file).parentFile, File(item.file).nameWithoutExtension + ".srt").delete()
        synchronized(this) { val list = all(c); list.removeAll { it.key == key }; save(c, list) }
        server?.rescan()
    }

    /** Watched to the end: delete the download if you've asked for that. */
    fun watched(c: Context, key: String) {
        if (deleteWatched(c) && all(c).any { it.key == key && it.done }) remove(c, key)
    }

    // ---- playing ------------------------------------------------------------------------------------

    private var server: LocalFiles.Server? = null
    private val key: String by lazy { java.util.UUID.randomUUID().toString().replace("-", "").take(16) }

    fun play(app: MainActivity, item: Downloads.Item) {
        val f = File(item.file)
        if (!f.isFile) { app.toast("The file has gone: download it again"); return }
        val srv = server ?: LocalFiles.Server({ listOf(dir(app.applicationContext)) }, key).also { server = it }
        val port = srv.start(0, loopback = true)
        val id = LocalFiles.id(f)
        val base = "http://127.0.0.1:$port/$key"
        val url = "$base/v/$id/" + Uri.encode(f.name)
        val subs = if (File(f.parentFile, f.nameWithoutExtension + ".srt").isFile) listOf(Sub("$base/s/$id/sub.srt", item.sub, "saved")) else emptyList()
        val w = History.Watch.from(item.watch)
        Player.play(app, url, item.title, subs, watch = w, startAt = History.resumeAt(app, w.videoId))
    }

    // ---- screens ------------------------------------------------------------------------------------

    fun homeRow(app: MainActivity): Row? {
        if (!app.isPhone) return null
        val list = all(app)
        if (list.isEmpty()) return null
        val busy = list.count { !it.done && it.status != Downloads.FAILED }
        return Row("Downloads", (if (busy > 0) "$busy downloading · " else "") + "${list.count { it.done }} saved  ►", opens = { screen(app) })
    }

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        lateinit var tick: Runnable
        fun build(): List<Row> {
            refresh(app)
            val list = all(app).sortedByDescending { it.added }
            if (list.isEmpty()) return listOf(Row("Empty."))
            val rows = mutableListOf<Row>()
            val busy = list.filter { !it.done && it.status != Downloads.FAILED }
            val failed = list.filter { it.status == Downloads.FAILED }
            val done = list.filter { it.done }
            if (busy.isNotEmpty()) { rows += Row("──── Downloading ────"); busy.forEach { rows += Row(it.title, it.line() + "  ►", opens = { itemScreen(app, it) }) } }
            if (failed.isNotEmpty()) { rows += Row("──── Didn't finish ────"); failed.forEach { rows += Row(it.title, it.line() + "  ►", opens = { itemScreen(app, it) }) } }
            if (done.isNotEmpty()) {
                rows += Row("──── Saved ────")
                done.forEach { d -> rows += Row(d.title, d.line() + "  ►", opens = { itemScreen(app, d) }, play = { play(app, d) }) }
                val bytes = done.sumOf { File(it.file).length() }
                rows += Row(info("Space used", LocalFiles.size(bytes)))
            }
            return rows
        }
        s = Screen("Downloads", emptyList())
        tick = Runnable { if (app.isTop(s)) { s.rows = build(); app.refresh(s); Net.later(2_000, tick) } }
        s.onShow = { s.rows = build(); app.refresh(s); Net.later(2_000, tick) }
        return s
    }

    private fun itemScreen(app: MainActivity, d: Downloads.Item): Screen {
        val rows = mutableListOf<Row>()
        if (d.done) rows += Row("Play", "►", action = { play(app, d) }, play = { play(app, d) })
        rows += Row(info("Copy", d.quality))
        rows += Row(info("Status", d.line()))
        if (d.sub.isNotEmpty()) rows += Row(info("Subtitles", Languages.name(d.sub)))
        if (d.status == Downloads.FAILED) rows += Row("Try again", action = {
            val w = History.Watch.from(d.watch)
            remove(app, d.key); app.back()
            startMany(app, listOf(w to d.title), Best.minutes(w.runtime) ?: if (w.type == "movie") 110.0 else 45.0)
        })
        rows += Row(if (d.done) "Delete" else "Cancel", action = { remove(app, d.key); app.toast(if (d.done) "Deleted" else "Cancelled"); app.back() })
        return Screen(d.title, rows)
    }

    /** "⇩ Download" on a film or episode: the best fit straight away, or a copy you choose. */
    fun chooseScreen(app: MainActivity, w: History.Watch, title: String): Screen {
        val minutes = Best.minutes(w.runtime) ?: if (w.type == "movie") 110.0 else 45.0
        lateinit var s: Screen
        s = Screen("Download: $title", listOf(Row("Asking your add-ons…")))
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                val addons = Home.allAddons()
                val cap = cap(app)
                Net.async({ Downloads.choose(addons, w.type, w.videoId, minutes, cap) }) { r ->
                    val c = r.getOrNull()
                    val rows = mutableListOf<Row>()
                    if (c == null || c.found.isEmpty()) rows += Row("No stream here can be downloaded.")
                    else {
                        val best = c.best
                        rows += if (best != null) Row("Download the best fit", best.quality + (if (best.size.isNotEmpty()) " · " + best.size else "") + "  ►", action = { start(app, w, title, best); app.back() })
                        else Row("No good copy checked out: choose one below")
                        rows += Row("──── Or choose a copy (smallest first) ────")
                        c.found.forEach { st ->
                            rows += Row(st.summary, (if (st in c.broken) "✗ no video · " else "") + st.addon, action = {
                                app.toast("Checking the stream…")
                                Net.async({ StreamCheck.broken(st.url) }) { b ->
                                    if (b.getOrDefault(false)) app.toast("That copy has no playable video. Pick another.")
                                    else { start(app, w, title, st); if (app.isTop(s)) app.back() }
                                }
                            })
                        }
                    }
                    s.rows = rows
                    if (app.isTop(s)) app.refresh(s)
                }
            }
        }
        return s
    }

    /** "⇩ Download episodes": tick as many as you like, season by season. */
    fun episodesScreen(app: MainActivity, f: Full, watchFor: (Video) -> History.Watch): Screen {
        val ticked = linkedSetOf<String>()
        val eps = MetaParse.ordered(f).filter { !Details.isUpcoming(it.released) }
        lateinit var s: Screen
        fun build(): List<Row> {
            val rows = mutableListOf<Row>()
            rows += Row("Download ${ticked.size} ticked", if (ticked.isEmpty()) "" else "►", action = {
                if (ticked.isEmpty()) { app.toast("Tick some episodes first"); return@Row }
                val pick = eps.filter { it.id in ticked }.map { v -> watchFor(v) to "${f.name} ${MetaParse.epLabel(v)}" }
                startMany(app, pick, Best.minutes(f.runtime) ?: 45.0)
                app.back()
            })
            eps.groupBy { it.season }.forEach { (n, list) ->
                val all = list.all { it.id in ticked }
                rows += Row("──── " + (if (n > 0) "Season $n" else "Specials") + " ────")
                rows += Row(if (all) "Untick this season" else "Tick this season", action = {
                    if (all) ticked.removeAll(list.map { it.id }.toSet()) else ticked.addAll(list.map { it.id }); s.rows = build(); app.refresh(s)
                })
                list.forEach { v ->
                    val saved = has(app, v.id)
                    val watched = History.progress(app, v.id)?.done == true
                    rows += Row((if (v.id in ticked) "✓ " else "   ") + MetaParse.epLabel(v) + "  " + v.title,
                        listOfNotNull(if (saved) "downloaded" else null, if (watched) "●" else null).joinToString(" · "), action = {
                            if (!ticked.remove(v.id)) ticked += v.id; s.rows = build(); app.refresh(s)
                        })
                }
            }
            return rows
        }
        s = Screen("Download episodes", build())
        return s
    }

    // ---- settings ------------------------------------------------------------------------------------

    fun settingsRow(app: MainActivity): Row? = if (!app.isPhone) null else Row("Downloads", "►", opens = { settingsScreen(app) })

    private fun settingsScreen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build(): List<Row> = listOf(
            Row("Download quality", "◄ ${Downloads.capLabel(cap(app))} ►", action = { prefs(app).edit().putInt("downloadCap", Downloads.nextCap(cap(app))).apply(); s.rows = build(); app.refresh(s) }),
            Row("Download over", "◄ ${if (mobileData(app)) "Wi-Fi or mobile data" else "Wi-Fi only"} ►", action = { prefs(app).edit().putBoolean("downloadMobile", !mobileData(app)).apply(); s.rows = build(); app.refresh(s) }),
            Row("Delete once watched", "◄ ${if (deleteWatched(app)) "On" else "Off"} ►", action = { prefs(app).edit().putBoolean("downloadDeleteWatched", !deleteWatched(app)).apply(); s.rows = build(); app.refresh(s) }),
            Row("Your downloads", "►", opens = { screen(app) }),
        )
        s = Screen("Downloads", build())
        return s
    }
}
