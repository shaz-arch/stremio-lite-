package app.stremiolite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What's been watched and how far, Continue watching, the Library and a Watched log, kept in one
 * small JSON file on the device and synced with the Stremio account's library ([Sync]).
 *
 * The account is the source of truth: each pull replaces Continue watching and the Library with
 * what the account says, except titles changed here that haven't reached the account yet.
 */
object History {
    private const val MAX_VIDEOS = 3000
    private const val MAX_LOG = 300
    private const val PULL_EVERY_MS = 60_000L

    /** What is about to be played, so the result from the player can be filed against it. */
    class Watch(
        val id: String,
        val type: String,
        val name: String,
        val year: String,
        val videoId: String,
        val label: String,
        val runtime: String,
        val nextVideoId: String?,
        val nextLabel: String?,
        /** A show's episode ids in Stremio's order, for the account's watched marks. */
        val videoIds: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("type", type).put("name", name).put("year", year)
            .put("videoId", videoId).put("label", label).put("runtime", runtime)
            .put("nextVideoId", nextVideoId ?: "").put("nextLabel", nextLabel ?: "")
            .put("videoIds", JSONArray(videoIds))

        companion object {
            fun from(o: JSONObject) = Watch(
                o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"),
                o.optString("videoId"), o.optString("label"), o.optString("runtime"),
                o.optString("nextVideoId").ifEmpty { null }, o.optString("nextLabel").ifEmpty { null },
                o.optJSONArray("videoIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }
    }

    class Item(
        val id: String,
        val type: String,
        val name: String,
        val year: String,
        val videoId: String,
        val label: String,
        val position: Long,
        val duration: Long,
        /** True when [videoId] is the next episode, not one already started. */
        val upNext: Boolean,
        val updated: Long,
    )

    class Saved(val id: String, val type: String, val name: String, val year: String)

    class Progress(val position: Long, val duration: Long, val done: Boolean)

    class Finished(val id: String, val type: String, val name: String, val year: String, val label: String, val time: Long)

    private var loaded = false
    private lateinit var file: File
    private val videos = LinkedHashMap<String, Progress>()
    private val items = mutableListOf<Item>()
    private val library = mutableListOf<Saved>()
    private val log = mutableListOf<Finished>()
    /** The account's own copy of each item (as JSON text), kept whole so nothing Stremio stores is lost. */
    private val remote = LinkedHashMap<String, String>()
    /** Titles changed here that haven't reached the account yet. */
    private val unsent = LinkedHashSet<String>()
    private var lastPull = 0L
    private var pulling = false

    private fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        file = File(c.applicationContext.filesDir, Profiles.fileName(c, "history"))
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        json.optJSONObject("videos")?.let { v ->
            v.keys().forEach { k ->
                v.optJSONObject(k)?.let { p -> videos[k] = Progress(p.optLong("pos"), p.optLong("dur"), p.optBoolean("done")) }
            }
        }
        json.optJSONArray("items")?.forEachObj { o ->
            items += Item(
                o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"),
                o.optString("videoId"), o.optString("label"), o.optLong("pos"), o.optLong("dur"),
                o.optBoolean("upNext"), o.optLong("updated"),
            )
        }
        json.optJSONArray("library")?.forEachObj { o ->
            library += Saved(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"))
        }
        json.optJSONArray("log")?.forEachObj { o ->
            log += Finished(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"), o.optString("label"), o.optLong("time"))
        }
        json.optJSONObject("remote")?.let { r -> r.keys().forEach { k -> r.optJSONObject(k)?.let { remote[k] = it.toString() } } }
        json.optJSONArray("unsent")?.let { a -> for (i in 0 until a.length()) unsent += a.optString(i) }
    }

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(f)
    }

    // Saving: grouped (one write a moment after the last change) and done off the screen's thread.
    private val saveTask = Runnable { writeNow() }
    private val writeLock = Any()
    @Volatile private var writeSeq = 0

    /** The status shape for each title in lists ([Marks]), by title id. Rebuilt only after a change. */
    private var marks: Map<String, String>? = null

    fun marks(c: Context): Map<String, String> {
        ensure(c)
        marks?.let { return it }
        val watched = HashSet<String>()
        log.forEach { if (it.type == "movie") watched += it.id }
        remote.forEach { (id, raw) ->
            if (id in watched) return@forEach
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val st = o.optJSONObject("state") ?: return@forEach
            if (o.optString("type") == "movie" && (st.optInt("timesWatched") > 0 || st.optInt("flaggedWatched") > 0)) watched += id
        }
        val continuing = items.mapTo(HashSet()) { it.id }
        val saved = library.mapTo(HashSet()) { it.id }
        val waits = runCatching { Waiting.list(c) }.getOrDefault(emptyList()).associateBy { it.id }
        val next = NextWatch.parse(remote[NextWatch.ID]).mapTo(HashSet()) { it.id }
        val followed = runCatching { NewEpisodes.followedNow(c).map { it.first } }.getOrDefault(emptyList()).toHashSet()
        val notOut = runCatching { Follow.films(c).map { it.id } }.getOrDefault(emptyList()).toHashSet()
        val basket = Basket.parse(remote[Basket.ID]).mapTo(HashSet()) { it.id }
        val m = HashMap<String, String>()
        for (id in watched + continuing + saved + waits.keys + next + followed + notOut + basket) {
            val w = waits[id]
            Marks.pick(
                watched = id in watched, continuing = id in continuing,
                ready = w != null && w.ready > 0, better = w != null && w.better, waiting = w != null || id in notOut,
                library = id in saved, next = id in next, following = id in followed, basket = id in basket,
            )?.let { m[id] = it }
        }
        marks = m
        return m
    }

    /** When [id] was last played or finished here or on the account (0 = never). */
    fun lastPlayed(c: Context, id: String): Long {
        ensure(c)
        val here = maxOf(items.firstOrNull { it.id == id }?.updated ?: 0L, log.firstOrNull { it.id == id }?.time ?: 0L)
        val acc = remote[id]?.let { runCatching { LibraryItems.read(JSONObject(it)) }.getOrNull() }?.let { maxOf(it.lastWatched, it.updated) } ?: 0L
        return maxOf(here, acc)
    }

    /** A film marked as watched. */
    fun isWatched(c: Context, id: String) = marks(c)[id] == Marks.WATCHED

    private fun save() {
        marks = null
        Net.cancel(saveTask)
        Net.later(1_500, saveTask)
    }

    /** Writes any waiting change now (the app is going into the background). */
    fun flush() {
        if (!loaded) return
        Net.cancel(saveTask)
        writeNow()
    }

    private fun writeNow() {
        if (!loaded) return
        val seq = ++writeSeq
        val vSnap = videos.entries.toList().takeLast(MAX_VIDEOS).map { it.key to it.value }
        val iSnap = items.toList()
        val lSnap = library.toList()
        val gSnap = log.sortedByDescending { it.time }.take(MAX_LOG)
        val rSnap = remote.entries.map { it.key to it.value }
        val uSnap = unsent.toList()
        val target = file
        Net.async({
            val v = JSONObject()
            vSnap.forEach { (k, p) -> v.put(k, JSONObject().put("pos", p.position).put("dur", p.duration).put("done", p.done)) }
            val arr = JSONArray()
            iSnap.forEach { e ->
                arr.put(
                    JSONObject().put("id", e.id).put("type", e.type).put("name", e.name).put("year", e.year)
                        .put("videoId", e.videoId).put("label", e.label).put("pos", e.position).put("dur", e.duration)
                        .put("upNext", e.upNext).put("updated", e.updated)
                )
            }
            val lib = JSONArray()
            lSnap.forEach { x -> lib.put(JSONObject().put("id", x.id).put("type", x.type).put("name", x.name).put("year", x.year)) }
            val lg = JSONArray()
            gSnap.forEach { f -> lg.put(JSONObject().put("id", f.id).put("type", f.type).put("name", f.name).put("year", f.year).put("label", f.label).put("time", f.time)) }
            val head = JSONObject().put("videos", v).put("items", arr).put("library", lib).put("log", lg)
                .put("unsent", JSONArray(uSnap)).toString()
            // The account's items are already JSON text: splice them in rather than re-parsing.
            val text = StringBuilder(head.length + rSnap.sumOf { it.second.length + it.first.length + 4 } + 16)
                .append(head, 0, head.length - 1)
                .append(",\"remote\":{")
            rSnap.forEachIndexed { n, (k, raw) ->
                if (n > 0) text.append(',')
                text.append(JSONObject.quote(k)).append(':').append(raw)
            }
            text.append("}}")
            synchronized(writeLock) {
                if (seq == writeSeq) target.writeText(text.toString())
            }
        }) { }
    }

    /** Forgets what's in memory, so the next use loads the (newly chosen) profile's file. */
    fun reset() {
        marks = null
        Net.cancel(saveTask)
        loaded = false
        videos.clear(); items.clear(); library.clear(); log.clear(); remote.clear(); unsent.clear()
        lastPull = 0
        fullDone = false
        pulling = false
    }

    /** Wipes everything (signing out). */
    fun clear(c: Context) {
        ensure(c)
        videos.clear(); items.clear(); library.clear(); log.clear(); remote.clear(); unsent.clear()
        lastPull = 0
        fullDone = false
        save()
    }

    // ---- reading ---------------------------------------------------------------------------

    fun progress(c: Context, videoId: String): Progress? {
        ensure(c)
        return videos[videoId]
    }

    /** Where to start this film or episode, or 0 to start from the beginning. */
    fun resumeAt(c: Context, videoId: String): Long {
        val p = progress(c, videoId) ?: return 0
        return if (!p.done && p.position > 60_000) p.position else 0
    }

    fun item(c: Context, id: String): Item? {
        ensure(c)
        return items.firstOrNull { it.id == id }
    }

    fun continueWatching(c: Context): List<Item> {
        ensure(c)
        return items.sortedByDescending { it.updated }
    }

    fun inLibrary(c: Context, id: String): Boolean {
        ensure(c)
        return library.any { it.id == id }
    }

    fun library(c: Context): List<Saved> {
        ensure(c)
        return library.toList()
    }

    /** Finished films and episodes, newest first. */
    fun watched(c: Context): List<Finished> {
        ensure(c)
        return log.sortedByDescending { it.time }
    }

    /** Marks episodes the account says are watched, once a show's episode list is known. */
    fun applyAccountWatched(c: Context, id: String, orderedIds: List<String>) {
        ensure(c)
        val field = remote[id]?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("state")?.optString("watched")
        val done = Sync.decodeWatched(field, orderedIds)
        var changed = false
        done.forEach { vid ->
            if (videos[vid]?.done != true) {
                videos[vid] = Progress(0, videos[vid]?.duration ?: 0, true)
                changed = true
            }
        }
        if (changed) save()
    }

    // ---- writing ---------------------------------------------------------------------------

    /** Counts as finished near the end: the last 8%, or the last 2 minutes (Stremio uses 90%). */
    fun isFinished(pos: Long, dur: Long) = finished(pos, dur)

    private fun finished(pos: Long, dur: Long) = LibraryItems.finished(pos, dur)

    /**
     * Files what the player reported ([position]/[duration] in ms, null if the player didn't say;
     * [completed] when it reached the end), then sends it to the account.
     */
    fun record(c: Context, w: Watch, position: Long?, duration: Long?, completed: Boolean) {
        ensure(c)
        val pos = position ?: 0L
        val dur = duration ?: 0L
        val done = completed || finished(pos, dur)
        val now = System.currentTimeMillis()
        if (position != null || completed) {
            val wasDone = videos[w.videoId]?.done == true
            videos.remove(w.videoId)
            videos[w.videoId] = Progress(if (done) 0 else pos, dur, done || wasDone)
        }
        items.removeAll { it.id == w.id }
        when {
            !done -> items += Item(w.id, w.type, w.name, w.year, w.videoId, w.label, pos, dur, false, now)
            w.nextVideoId != null -> items += Item(w.id, w.type, w.name, w.year, w.nextVideoId, w.nextLabel ?: "", 0, 0, true, now)
        }
        if (done) {
            Offline.watched(c, w.videoId)
            log.removeAll { it.id == w.id && (it.label == w.label || it.label == "watched on another device") }
            log.add(0, Finished(w.id, w.type, w.name, w.year, w.label, now))
        }

        // The account's copy, updated the way Stremio's player does it (shared with the Linux app).
        val item = LibraryItems.update(
            existing = remote[w.id]?.let { runCatching { JSONObject(it) }.getOrNull() },
            id = w.id, type = w.type, name = w.name, videoId = w.videoId,
            pos = pos, dur = dur, done = done, positionKnown = position != null,
            videoIds = w.videoIds, alsoDone = videos.filterValues { it.done }.keys, nextVideoId = w.nextVideoId, now = now,
        )
        remote[w.id] = item.toString()
        unsent += w.id
        save()
        send(c)
    }

    /**
     * Takes a film or an episode off the watched list, here and on the account.
     * [label]: the episode label ("S01E02") for episodes, empty for films.
     */
    fun unwatch(c: Context, id: String, type: String, videoId: String, label: String, videoIds: List<String>) {
        ensure(c)
        log.removeAll { it.id == id && (label.isEmpty() || it.label.startsWith(label)) }
        videos[videoId]?.let { videos[videoId] = Progress(0, it.duration, false) }
        remote[id]?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { item ->
            remote[id] = LibraryItems.unwatch(item, videoId, if (type == "movie") emptyList() else videoIds).toString()
            unsent += id
        }
        save()
        send(c)
    }

    fun removeItem(c: Context, id: String) {
        ensure(c)
        items.removeAll { it.id == id }
        remote[id]?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { item ->
            item.optJSONObject("state")?.put("timeOffset", 0)
            item.put("_mtime", Sync.iso(System.currentTimeMillis()))
            remote[id] = item.toString()
            unsent += id
        }
        save()
        send(c)
    }

    /** Empties Continue watching (the account's resume points); watched marks stay. One sync for all. */
    fun clearContinue(c: Context) {
        ensure(c)
        val ids = items.map { it.id }
        items.clear()
        ids.forEach { id ->
            remote[id]?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { item ->
                item.optJSONObject("state")?.put("timeOffset", 0)
                item.put("_mtime", Sync.iso(System.currentTimeMillis()))
                remote[id] = item.toString()
                unsent += id
            }
        }
        save()
        send(c)
    }

    /** Empties the Library (titles stay in your history and watched marks). One sync for all. */
    fun clearLibrary(c: Context) {
        ensure(c)
        val now = Sync.iso(System.currentTimeMillis())
        library.toList().forEach { s ->
            val item = remote[s.id]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(s.id, s.type, s.name)
            item.put("removed", true).put("temp", false).put("_mtime", now)
            remote[s.id] = item.toString()
            unsent += s.id
        }
        library.clear()
        save()
        send(c)
    }

    /** Every title record on the account, read (for following: which shows you've started). */
    fun infos(c: Context): List<LibraryItems.Info> {
        ensure(c)
        return remote.values.mapNotNull { runCatching { LibraryItems.read(JSONObject(it)) }.getOrNull() }.filter { it.type != "other" }
    }

    /** The account's copy of one record (e.g. the waiting list), as JSON text. */
    fun rawRemote(c: Context, id: String): String? {
        ensure(c)
        return remote[id]
    }

    /** Stores and sends one record exactly as given. */
    fun putRemote(c: Context, item: JSONObject) {
        ensure(c)
        val id = item.optString("_id")
        remote[id] = item.toString()
        unsent += id
        save()
        send(c)
    }

    fun toggleLibrary(c: Context, s: Saved): Boolean {
        ensure(c)
        val had = library.removeAll { it.id == s.id }
        if (!had) library.add(0, s)
        val item = remote[s.id]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(s.id, s.type, s.name)
        item.put("removed", had).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
        remote[s.id] = item.toString()
        unsent += s.id
        save()
        send(c)
        return !had
    }

    // ---- syncing with the account ----------------------------------------------------------

    /** Sends titles changed here; anything that fails is retried on the next sync. */
    private fun send(c: Context) {
        val key = Account.authKey(c) ?: return
        val ids = unsent.toList()
        if (ids.isEmpty()) return
        val raw = ids.mapNotNull { remote[it] }
        Net.async({ Sync.push(key, raw.map { JSONObject(it) }) }) { r ->
            if (r.isSuccess) {
                unsent.removeAll(ids.toSet())
                save()
            }
        }
    }

    /** What a pull found, worked out off the screen's thread. */
    private class Merge(
        val items: List<Item>,
        val library: List<Saved>,
        val remote: List<Pair<String, String>>,
        val progress: Map<String, Progress>,
        val log: List<Finished>,
    )

    /**
     * Fetches the account's library (at most every few minutes unless [force]). [changed] runs only if
     * Continue watching, the Library or the Watched list actually changed, so screens aren't redrawn for nothing.
     */
    /** Runs after every successful pull (e.g. to pick up settings changed on another device). */
    var onPulled: (() -> Unit)? = null

    /** For screens showing resume points: fetches from the account unless that happened in the last 15 seconds. */
    fun pullFresh(c: Context, changed: () -> Unit) = pull(c, force = System.currentTimeMillis() - lastPull > 15_000, changed = changed)

    /** False until a full pull has happened since the app started (the first one is always full). */
    private var fullDone = false

    /**
     * Brings in changes from the account. Normally only the records that changed are fetched (the
     * account lists ids and change times first); the first pull after the app starts, and [full]
     * ones (Reload), fetch everything as a safety net.
     */
    fun pull(c: Context, force: Boolean = false, full: Boolean = false, changed: (() -> Unit)? = null) {
        ensure(c)
        val key = Account.authKey(c) ?: return
        if (pulling || (!force && !full && System.currentTimeMillis() - lastPull < PULL_EVERY_MS)) return
        pulling = true
        send(c)
        val skip = unsent.toSet()
        val doneIds = videos.filterValues { it.done }.keys.toHashSet()
        val logIds = log.map { it.id }.toHashSet()
        val whole = full || !fullDone
        val copy = if (whole) emptyMap() else LinkedHashMap(remote)
        Net.async({
            if (whole) compute(Sync.pull(key), skip, doneIds, logIds)
            else {
                // Any trouble with the change list: just do a full pull, as before.
                val meta = runCatching { Sync.meta(key) }.getOrNull()
                if (meta == null) compute(Sync.pull(key), skip, doneIds, logIds)
                else {
                    val want = Sync.changed(meta, copy.mapValues { Sync.mtimeOf(it.value) }, skip)
                    when {
                        want.isEmpty() -> null                                // nothing changed: no work at all
                        want.size > 150 -> compute(Sync.pull(key), skip, doneIds, logIds)
                        else -> {
                            // Nothing is ever dropped here (Stremio marks removals on the record itself;
                            // the full pull at start and on Reload tidies anything else).
                            val fresh = Sync.pullIds(key, want)
                            val byId = HashMap<String, JSONObject>()
                            for (i in 0 until fresh.length()) fresh.optJSONObject(i)?.let { byId[it.optString("_id")] = it }
                            val merged = JSONArray()
                            copy.forEach { (id, raw) -> if (id !in byId) runCatching { merged.put(JSONObject(raw)) } }
                            byId.values.forEach { merged.put(it) }
                            compute(merged, skip, doneIds, logIds)
                        }
                    }
                }
            }
        }) { r ->
            pulling = false
            r.onSuccess { m ->
                lastPull = System.currentTimeMillis()
                if (whole) fullDone = true
                if (m != null && apply(m)) {
                    save()
                    changed?.invoke()
                }
                onPulled?.invoke()
            }
        }
    }

    private fun compute(arr: JSONArray, skip: Set<String>, doneIds: Set<String>, logIds: Set<String>): Merge {
        val newItems = mutableListOf<Item>()
        val newLib = mutableListOf<Saved>()
        val newRemote = mutableListOf<Pair<String, String>>()
        val progress = HashMap<String, Progress>()
        val newLog = mutableListOf<Finished>()
        arr.forEachObj { o ->
            val id = o.optString("_id")
            val type = o.optString("type")
            if (id.isEmpty() || id in skip) return@forEachObj
            newRemote += id to o.toString()
            // Hidden records (waiting list, shared settings) are kept, but aren't titles.
            if (type == "other") return@forEachObj
            val name = o.optString("name")
            val removed = o.optBoolean("removed")
            val temp = o.optBoolean("temp")
            val st = o.optJSONObject("state") ?: JSONObject()
            val offset = st.optLong("timeOffset")
            val dur = st.optLong("duration")
            val videoId = st.optString("video_id").takeUnless { it.isBlank() || it == "null" } ?: id
            val updated = Sync.parseTime(st.optString("lastWatched")).takeIf { it > 0 } ?: Sync.parseTime(o.optString("_mtime"))

            if (!removed) newLib += Saved(id, type, name, "")
            if ((!removed || temp) && offset > 0) {
                val upNext = offset == 1L
                newItems += Item(id, type, name, "", videoId, if (videoId != id) episodeLabel(videoId) else "", if (upNext) 0 else offset, dur, upNext, updated)
                if (!upNext && videoId !in doneIds) progress[videoId] = Progress(offset, dur, false)
            }
            // Films watched to the end elsewhere count as watched here too.
            if (type == "movie" && (st.optInt("flaggedWatched") == 1 || st.optInt("timesWatched") > 0) && offset == 0L && id !in doneIds) {
                progress[id] = Progress(0, dur, true)
            }
            if (id !in logIds && (st.optInt("timesWatched") > 0 || st.optInt("flaggedWatched") == 1)) {
                newLog += Finished(id, type, name, "", if (type == "movie") "" else "watched on another device", updated)
            }
        }
        return Merge(newItems, newLib, newRemote, progress, newLog)
    }

    /** Applies a pull; true if anything you'd see changed. */
    private fun apply(m: Merge): Boolean {
        fun sig() = items.map { "${it.id}|${it.videoId}|${it.position / 60_000}|${it.upNext}" }.sorted().joinToString() +
            "#" + library.map { it.id }.sorted().joinToString() + "#" + log.size
        val before = sig()
        val keepItems = items.filter { it.id in unsent }
        val keepLib = library.filter { it.id in unsent }
        m.remote.forEach { (id, raw) -> if (id !in unsent) remote[id] = raw }
        var progressChanged = false
        m.progress.forEach { (vid, p) ->
            val old = videos[vid]
            if (old?.done != true && (old == null || old.position / 60_000 != p.position / 60_000 || old.done != p.done)) {
                videos[vid] = p
                progressChanged = true
            }
        }
        val logIds = log.map { it.id }.toHashSet()
        m.log.forEach { if (it.id !in logIds) log += it }
        items.clear()
        items += keepItems + m.items.filter { n -> n.id !in unsent && keepItems.none { it.id == n.id } }
        library.clear()
        library += keepLib + m.library.filter { n -> n.id !in unsent && keepLib.none { it.id == n.id } }
        marks = null
        return sig() != before || progressChanged
    }

    /** "tt123:2:3" → "S02E03". */
    private fun episodeLabel(videoId: String): String {
        val parts = videoId.split(':')
        val s = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val e = parts.lastOrNull()?.toIntOrNull()
        return if (s != null && e != null && parts.size >= 3) "S${s.toString().padStart(2, '0')}E${e.toString().padStart(2, '0')}" else ""
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ---- the pending playback survives the app being closed while the player runs ----------

    private const val PREFS = "playback"

    fun setPending(c: Context, w: Watch?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending", w?.toJson()?.toString())
            .putLong("launched", System.currentTimeMillis()).apply()
    }

    /** What was playing (if it should be filed) and when the player was started. */
    fun takePending(c: Context): Pair<Watch?, Long> {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getString("pending", null)
        val launched = p.getLong("launched", 0)
        p.edit().remove("pending").remove("launched").apply()
        return (s?.let { runCatching { Watch.from(JSONObject(it)) }.getOrNull() }) to launched
    }
}
