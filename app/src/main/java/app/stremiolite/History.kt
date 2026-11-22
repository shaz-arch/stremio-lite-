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
    private const val PULL_EVERY_MS = 2 * 60_000L

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
    /** The account's own copy of each item, kept whole so nothing Stremio stores is lost when we update it. */
    private val remote = LinkedHashMap<String, JSONObject>()
    /** Titles changed here that haven't reached the account yet. */
    private val unsent = LinkedHashSet<String>()
    private var lastPull = 0L
    private var pulling = false

    private fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        file = File(c.applicationContext.filesDir, "history.json")
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
        json.optJSONObject("remote")?.let { r -> r.keys().forEach { k -> r.optJSONObject(k)?.let { remote[k] = it } } }
        json.optJSONArray("unsent")?.let { a -> for (i in 0 until a.length()) unsent += a.optString(i) }
    }

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(f)
    }

    private fun save() {
        val v = JSONObject()
        videos.entries.toList().takeLast(MAX_VIDEOS).forEach { (k, p) ->
            v.put(k, JSONObject().put("pos", p.position).put("dur", p.duration).put("done", p.done))
        }
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("type", e.type).put("name", e.name).put("year", e.year)
                    .put("videoId", e.videoId).put("label", e.label).put("pos", e.position).put("dur", e.duration)
                    .put("upNext", e.upNext).put("updated", e.updated)
            )
        }
        val lib = JSONArray()
        library.forEach { s -> lib.put(JSONObject().put("id", s.id).put("type", s.type).put("name", s.name).put("year", s.year)) }
        val lg = JSONArray()
        log.sortedByDescending { it.time }.take(MAX_LOG).forEach { f ->
            lg.put(JSONObject().put("id", f.id).put("type", f.type).put("name", f.name).put("year", f.year).put("label", f.label).put("time", f.time))
        }
        val r = JSONObject()
        remote.forEach { (k, o) -> r.put(k, o) }
        val json = JSONObject().put("videos", v).put("items", arr).put("library", lib).put("log", lg)
            .put("remote", r).put("unsent", JSONArray(unsent.toList()))
        runCatching { file.writeText(json.toString()) }
    }

    /** Wipes everything (signing out). */
    fun clear(c: Context) {
        ensure(c)
        videos.clear(); items.clear(); library.clear(); log.clear(); remote.clear(); unsent.clear()
        lastPull = 0
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
        val field = remote[id]?.optJSONObject("state")?.optString("watched")
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

    private fun finished(pos: Long, dur: Long) = dur > 0 && (pos >= dur * 0.92 || dur - pos < 120_000)

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
            log.removeAll { it.id == w.id && (it.label == w.label || it.label == "watched on another device") }
            log.add(0, Finished(w.id, w.type, w.name, w.year, w.label, now))
        }

        // The account's copy, updated the way Stremio's player does it.
        val item = remote[w.id] ?: Sync.newItem(w.id, w.type, w.name)
        item.put("name", w.name).put("type", w.type).put("_mtime", Sync.iso(now))
        val st = item.optJSONObject("state") ?: JSONObject().also { item.put("state", it) }
        st.put("lastWatched", Sync.iso(now))
        if (st.optString("video_id") != w.videoId) {
            st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
            st.put("timeWatched", 0).put("flaggedWatched", 0).put("video_id", w.videoId).put("timeOffset", 0)
        }
        val gained = (pos - st.optLong("timeOffset")).coerceAtLeast(0)
        st.put("timeWatched", st.optLong("timeWatched") + gained)
        st.put("overallTimeWatched", st.optLong("overallTimeWatched") + gained)
        if (dur > 0) st.put("duration", dur)
        if (done) {
            st.put("flaggedWatched", 1).put("timesWatched", st.optInt("timesWatched") + 1)
            if (w.videoIds.isNotEmpty()) {
                val marked = Sync.decodeWatched(st.optString("watched"), w.videoIds).toMutableSet()
                w.videoIds.filterTo(marked) { videos[it]?.done == true }
                marked += w.videoId
                st.put("watched", Sync.encodeWatched(marked, w.videoIds))
            }
            if (w.nextVideoId != null) {
                // Stremio's "next episode" marker: the next video with a position of 1
                st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
                st.put("video_id", w.nextVideoId).put("timeWatched", 0).put("flaggedWatched", 0).put("timeOffset", 1)
            } else {
                st.put("timeOffset", 0)
            }
        } else if (position != null) {
            st.put("timeOffset", pos.coerceAtLeast(1))
        }
        remote[w.id] = item
        unsent += w.id
        save()
        send(c)
    }

    fun removeItem(c: Context, id: String) {
        ensure(c)
        items.removeAll { it.id == id }
        remote[id]?.let { item ->
            item.optJSONObject("state")?.put("timeOffset", 0)
            item.put("_mtime", Sync.iso(System.currentTimeMillis()))
            unsent += id
        }
        save()
        send(c)
    }

    fun toggleLibrary(c: Context, s: Saved): Boolean {
        ensure(c)
        val had = library.removeAll { it.id == s.id }
        if (!had) library.add(0, s)
        val item = remote[s.id] ?: Sync.newItem(s.id, s.type, s.name)
        item.put("removed", had).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
        remote[s.id] = item
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
        val payload = ids.mapNotNull { remote[it] }.map { JSONObject(it.toString()) }
        Net.async({ Sync.push(key, payload) }) { r ->
            if (r.isSuccess) {
                unsent.removeAll(ids.toSet())
                save()
            }
        }
    }

    /** Fetches the account's library (at most every couple of minutes unless [force]); [done] after merging. */
    fun pull(c: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        ensure(c)
        val key = Account.authKey(c) ?: return
        if (pulling || (!force && System.currentTimeMillis() - lastPull < PULL_EVERY_MS)) return
        pulling = true
        send(c)
        Net.async({ Sync.pull(key) }) { r ->
            pulling = false
            r.onSuccess { arr ->
                lastPull = System.currentTimeMillis()
                merge(arr)
                save()
                done?.invoke()
            }
        }
    }

    private fun merge(arr: JSONArray) {
        val keepItems = items.filter { it.id in unsent }
        val keepLib = library.filter { it.id in unsent }
        val newItems = mutableListOf<Item>()
        val newLib = mutableListOf<Saved>()
        val logIds = log.map { it.id }.toHashSet()

        arr.forEachObj { o ->
            val id = o.optString("_id")
            val type = o.optString("type")
            if (id.isEmpty() || type == "other" || id in unsent) return@forEachObj
            remote[id] = o
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
                if (!upNext && videos[videoId]?.done != true) videos[videoId] = Progress(offset, dur, false)
            }
            // Films watched to the end elsewhere count as watched here too.
            if (type == "movie" && (st.optInt("flaggedWatched") == 1 || st.optInt("timesWatched") > 0) && offset == 0L) {
                if (videos[id]?.done != true) videos[id] = Progress(0, dur, true)
            }
            if (id !in logIds && (st.optInt("timesWatched") > 0 || st.optInt("flaggedWatched") == 1)) {
                log += Finished(id, type, name, "", if (type == "movie") "" else "watched on another device", updated)
            }
        }
        items.clear()
        items += keepItems + newItems.filter { n -> keepItems.none { it.id == n.id } }
        library.clear()
        library += keepLib + newLib.filter { n -> keepLib.none { it.id == n.id } }
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
