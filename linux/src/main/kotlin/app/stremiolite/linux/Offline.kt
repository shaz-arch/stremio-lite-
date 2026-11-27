package app.stremiolite.linux

import app.stremiolite.Best
import app.stremiolite.Downloads
import app.stremiolite.Full
import app.stremiolite.Languages
import app.stremiolite.LocalFiles
import app.stremiolite.MetaParse
import app.stremiolite.Stream
import app.stremiolite.StreamParse
import app.stremiolite.Sub
import app.stremiolite.Video
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Films and episodes saved to ~/Videos/Stremonino for watching without internet (one at a time). */
object Offline {
    val dir: File get() = File(System.getProperty("user.home"), "Videos/Stremonino").also { it.mkdirs() }

    private val lock = Any()
    private fun store() = Store.file("downloads.json")
    fun all(): MutableList<Downloads.Item> = synchronized(lock) { Downloads.parse(runCatching { store().readText() }.getOrNull()) }
    private fun save(list: List<Downloads.Item>) = synchronized(lock) { runCatching { store().writeText(Downloads.write(list)) } }
    private fun update(key: String, change: (Downloads.Item) -> Unit) = synchronized(lock) { val l = all(); l.firstOrNull { it.key == key }?.let(change); save(l) }
    fun has(key: String) = all().any { it.key == key }

    fun toJson(w: Watch): JSONObject = JSONObject().put("id", w.id).put("type", w.type).put("name", w.name).put("videoId", w.videoId)
        .put("label", w.label).put("runtime", w.runtime).put("videoIds", JSONArray(w.videoIds)).put("nextVideoId", w.nextVideoId ?: "").put("nextLabel", w.nextLabel ?: "")
    fun watchOf(o: JSONObject) = Watch(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("videoId"), o.optString("label"), o.optString("runtime"),
        o.optJSONArray("videoIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        o.optString("nextVideoId").ifEmpty { null }, o.optString("nextLabel").ifEmpty { null })

    // ---- the worker --------------------------------------------------------------------------------

    @Volatile private var working = false
    @Volatile private var cancel: String? = null

    /** Starts (or carries on) the queue; called at start-up too, so unfinished ones resume. */
    fun kick() {
        synchronized(lock) { if (working) return; working = true }
        Thread {
            try {
                while (true) {
                    val item = all().firstOrNull { it.status == Downloads.QUEUED || it.status == Downloads.RUNNING } ?: break
                    update(item.key) { it.status = Downloads.RUNNING; it.why = "" }
                    val part = File(item.file + ".part")
                    var last = 0L
                    val r = runCatching {
                        Downloads.fetchTo(item.url, part, progress = { got, total ->
                            val now = System.currentTimeMillis()
                            if (now - last > 1_000) { last = now; update(item.key) { it.got = got; it.total = total }; App.post { App.refresh() } }
                        }, stop = { cancel == item.key })
                    }
                    if (cancel == item.key) { cancel = null; part.delete(); continue }
                    r.onSuccess {
                        if (part.length() < Downloads.MIN_BYTES) {
                            // An error clip ("file unavailable"), not the film: don't keep it.
                            part.delete()
                            update(item.key) { it.status = Downloads.FAILED; it.why = "no playable video in that copy" }
                            App.post { App.refresh() }
                            return@onSuccess
                        }
                        part.renameTo(File(item.file))
                        update(item.key) { it.status = Downloads.DONE; it.got = File(item.file).length(); it.total = it.got }
                        App.post { App.toast("Downloaded: ${item.title}"); Home.redraw(); App.refresh() }
                    }.onFailure { e ->
                        update(item.key) { it.status = Downloads.FAILED; it.why = if (e is app.stremiolite.Http.HttpError) "the link stopped working" else (e.message ?: "failed").take(40) }
                        App.post { App.refresh() }
                    }
                }
            } finally { working = false }
        }.apply { isDaemon = true }.start()
    }

    fun start(w: Watch, title: String, s: Stream, quiet: Boolean = false) {
        all().firstOrNull { it.key == w.videoId }?.let { if (it.status != Downloads.FAILED) { if (!quiet) App.toast("Already in Downloads"); return }; remove(w.videoId) }
        val file = File(dir, Downloads.fileName(title, w.videoId, Downloads.ext(s)))
        val item = Downloads.Item(w.videoId, title, toJson(w), file.absolutePath, s.url, s.quality + (if (s.size.isNotEmpty()) " · " + s.size else ""), Downloads.QUEUED)
        synchronized(lock) { val l = all(); l.removeAll { it.key == item.key }; l += item; save(l) }
        if (!quiet) App.toast("Downloading: $title")
        val addons = Home.all(); val lang = Prefs.language()
        App.async({
            val sub = Downloads.subtitle(addons, w.type, w.videoId, lang) ?: return@async null
            Downloads.fetchTo(sub.url, File(file.parentFile, file.nameWithoutExtension + ".srt")); sub.lang
        }) { r -> r.getOrNull()?.let { l -> update(item.key) { it.sub = l } } }
        kick()
    }

    fun startMany(list: List<Pair<Watch, String>>, minutes: Double) {
        if (list.isEmpty()) return
        App.toast("Finding streams for ${list.size} episode" + (if (list.size == 1) "" else "s") + "…")
        val addons = Home.all(); val cap = Prefs.downloadCap
        App.async({ list.map { (w, t) -> Triple(w, t, if (all().any { it.key == w.videoId && it.status != Downloads.FAILED }) null else Downloads.pick(addons, w.type, w.videoId, minutes, cap)) } }) { r ->
            val got = r.getOrDefault(emptyList())
            var missed = 0
            got.forEach { (w, t, s) -> if (s != null) start(w, t, s, quiet = true) else if (!has(w.videoId)) missed++ }
            App.toast(if (missed == 0) "${list.size} queued for download" else "${list.size - missed} queued, $missed had no stream to download")
        }
    }

    fun remove(key: String) {
        val item = all().firstOrNull { it.key == key } ?: return
        if (item.status == Downloads.RUNNING) cancel = key
        File(item.file).delete(); File(item.file + ".part").delete()
        File(File(item.file).parentFile, File(item.file).nameWithoutExtension + ".srt").delete()
        synchronized(lock) { val l = all(); l.removeAll { it.key == key }; save(l) }
    }

    fun watched(key: String) { if (Prefs.downloadDeleteWatched && all().any { it.key == key && it.done }) remove(key) }

    fun play(d: Downloads.Item) {
        val f = File(d.file)
        if (!f.isFile) { App.toast("The file has gone: download it again"); return }
        val w = watchOf(d.watch)
        val srt = File(f.parentFile, f.nameWithoutExtension + ".srt")
        Player.play(f.absolutePath, d.title, if (srt.isFile) Sub(srt.absolutePath, d.sub, "saved") else null, Lib.resumeAt(w.id, w.videoId), video = w.videoId) { r ->
            if (!r.failed) After.played(w, r)
        }
    }

    // ---- screens -----------------------------------------------------------------------------------

    fun homeRow(): Row? {
        val list = all(); if (list.isEmpty()) return null
        val busy = list.count { !it.done && it.status != Downloads.FAILED }
        return Row("Downloads", (if (busy > 0) "$busy downloading · " else "") + "${list.count { it.done }} saved  ►", opens = { screen() })
    }

    fun screen(): Screen {
        lateinit var s: Screen
        fun build() {
            val list = all().sortedByDescending { it.added }
            val rows = mutableListOf<Row>()
            if (list.isEmpty()) rows += Row("Empty.")
            val busy = list.filter { !it.done && it.status != Downloads.FAILED }
            val failed = list.filter { it.status == Downloads.FAILED }
            val done = list.filter { it.done }
            if (busy.isNotEmpty()) { rows += Row("──── Downloading ────"); busy.forEach { rows += Row(it.title, it.line() + "  ►", opens = { item(it) }) } }
            if (failed.isNotEmpty()) { rows += Row("──── Didn't finish ────"); failed.forEach { rows += Row(it.title, it.line() + "  ►", opens = { item(it) }) } }
            if (done.isNotEmpty()) {
                rows += Row("──── Saved in ~/Videos/Stremonino ────")
                done.forEach { d -> rows += Row(d.title, d.line() + "  ►", opens = { item(d) }, play = { play(d) }) }
                rows += Row(info("Space used", LocalFiles.size(done.sumOf { File(it.file).length() })))
            }
            s.rows = rows; App.refresh()
        }
        s = Screen("Downloads", emptyList())
        fun tick() { if (App.isTop(s)) { build(); App.later(1_500) { tick() } } }
        s.onShow = { build(); App.later(1_500) { tick() } }
        return s
    }

    private fun item(d: Downloads.Item): Screen {
        val rows = mutableListOf<Row>()
        if (d.done) rows += Row("Play", "►", action = { play(d) }, play = { play(d) })
        rows += Row(info("Copy", d.quality))
        rows += Row(info("Status", d.line()))
        if (d.sub.isNotEmpty()) rows += Row(info("Subtitles", Languages.name(d.sub)))
        if (d.status == Downloads.FAILED) rows += Row("Try again", action = {
            val w = watchOf(d.watch); remove(d.key); App.back()
            startMany(listOf(w to d.title), Best.minutes(w.runtime) ?: if (w.type == "movie") 110.0 else 45.0)
        })
        rows += Row(if (d.done) "Delete" else "Cancel", action = { remove(d.key); App.toast(if (d.done) "Deleted" else "Cancelled"); App.back() })
        return Screen(d.title, rows)
    }

    fun chooseScreen(w: Watch, title: String): Screen {
        val minutes = Best.minutes(w.runtime) ?: if (w.type == "movie") 110.0 else 45.0
        lateinit var s: Screen
        var started = false
        s = Screen("Download: $title", listOf(Row("Asking your add-ons…")))
        s.onShow = {
            if (!started) {
                started = true
                val addons = Home.all()
                val cap = Prefs.downloadCap
                App.async({ Downloads.choose(addons, w.type, w.videoId, minutes, cap) }) { r ->
                    val c = r.getOrNull()
                    val rows = mutableListOf<Row>()
                    if (c == null || c.found.isEmpty()) rows += Row("No stream here can be downloaded.")
                    else {
                        val best = c.best
                        rows += if (best != null) Row("Download the best fit", best.quality + (if (best.size.isNotEmpty()) " · " + best.size else "") + "  ►", action = { start(w, title, best); App.back() })
                        else Row("No good copy checked out: choose one below")
                        rows += Row("──── Or choose a copy (smallest first) ────")
                        c.found.forEach { st ->
                            rows += Row(st.summary, (if (st in c.broken) "✗ no video · " else "") + st.addon, action = {
                                App.toast("Checking the stream…")
                                App.async({ app.stremiolite.StreamCheck.broken(st.url) }) { b ->
                                    if (b.getOrDefault(false)) App.toast("That copy has no playable video. Pick another.")
                                    else { start(w, title, st); if (App.isTop(s)) App.back() }
                                }
                            })
                        }
                    }
                    s.rows = rows; App.refresh()
                }
            }
        }
        return s
    }

    fun episodesScreen(f: Full, watchFor: (Video) -> Watch, upcoming: (Video) -> Boolean): Screen {
        val ticked = linkedSetOf<String>()
        val eps = MetaParse.ordered(f).filter { !upcoming(it) }
        val watched = Lib.watchedEpisodes(f.id, MetaParse.stremioOrder(f))
        lateinit var s: Screen
        fun build() {
            val rows = mutableListOf<Row>()
            rows += Row("Download ${ticked.size} ticked", if (ticked.isEmpty()) "" else "►", action = {
                if (ticked.isEmpty()) { App.toast("Tick some episodes first"); return@Row }
                startMany(eps.filter { it.id in ticked }.map { v -> watchFor(v) to "${f.name} ${MetaParse.epLabel(v)}" }, Best.minutes(f.runtime) ?: 45.0)
                App.back()
            })
            eps.groupBy { it.season }.forEach { (n, list) ->
                val allOn = list.all { it.id in ticked }
                rows += Row("──── " + (if (n > 0) "Season $n" else "Specials") + " ────")
                rows += Row(if (allOn) "Untick this season" else "Tick this season", action = {
                    if (allOn) ticked.removeAll(list.map { it.id }.toSet()) else ticked.addAll(list.map { it.id }); build()
                })
                list.forEach { v ->
                    rows += Row((if (v.id in ticked) "✓ " else "   ") + MetaParse.epLabel(v) + "  " + v.title,
                        listOfNotNull(if (has(v.id)) "downloaded" else null, if (v.id in watched) "●" else null).joinToString(" · "),
                        action = { if (!ticked.remove(v.id)) ticked += v.id; build() })
                }
            }
            s.rows = rows; App.refresh()
        }
        s = Screen("Download episodes", emptyList())
        s.onShow = { build() }
        return s
    }

    fun settingsRow() = Row("Downloads", "►", opens = { settings() })

    private fun settings(): Screen {
        lateinit var s: Screen
        fun build() {
            s.rows = listOf(
                Row("Download quality", "◄ ${Downloads.capLabel(Prefs.downloadCap)} ►", action = { Prefs.downloadCap = Downloads.nextCap(Prefs.downloadCap); build() }),
                Row("Delete once watched", "◄ ${if (Prefs.downloadDeleteWatched) "On" else "Off"} ►", action = { Prefs.downloadDeleteWatched = !Prefs.downloadDeleteWatched; build() }),
                Row("Your downloads", "►", opens = { screen() }),
            )
            App.refresh()
        }
        s = Screen("Downloads", emptyList())
        s.onShow = { build() }
        return s
    }
}
