package app.stremiolite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What's been watched and how far, plus the Library, kept in one small JSON file on the device.
 *
 *  - videos:  per film or episode id, the last position and length, and whether it was finished
 *  - items:   one entry per film or show for Continue watching (for a show: the episode in progress,
 *             or the next one once an episode is finished)
 *  - library: titles saved with "Add to library"
 */
object History {
    private const val MAX_ITEMS = 40
    private const val MAX_VIDEOS = 3000

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
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("type", type).put("name", name).put("year", year)
            .put("videoId", videoId).put("label", label).put("runtime", runtime)
            .put("nextVideoId", nextVideoId ?: "").put("nextLabel", nextLabel ?: "")

        companion object {
            fun from(o: JSONObject) = Watch(
                o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"),
                o.optString("videoId"), o.optString("label"), o.optString("runtime"),
                o.optString("nextVideoId").ifEmpty { null }, o.optString("nextLabel").ifEmpty { null },
            )
        }
    }

    class Item(
        val id: String,
        val type: String,
        val name: String,
        val year: String,
        /** The episode (or film) to carry on with. */
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

    private var loaded = false
    private val videos = LinkedHashMap<String, Progress>()
    private val items = mutableListOf<Item>()
    private val library = mutableListOf<Saved>()
    private lateinit var file: File

    private fun ensure(c: Context) {
        if (loaded) return
        loaded = true
        file = File(c.filesDir, "history.json")
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        json.optJSONObject("videos")?.let { v ->
            v.keys().forEach { k ->
                v.optJSONObject(k)?.let { p -> videos[k] = Progress(p.optLong("pos"), p.optLong("dur"), p.optBoolean("done")) }
            }
        }
        json.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                items += Item(
                    o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"),
                    o.optString("videoId"), o.optString("label"), o.optLong("pos"), o.optLong("dur"),
                    o.optBoolean("upNext"), o.optLong("updated"),
                )
            }
        }
        json.optJSONArray("library")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                library += Saved(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("year"))
            }
        }
    }

    private fun save() {
        val v = JSONObject()
        videos.entries.toList().takeLast(MAX_VIDEOS).forEach { (k, p) ->
            v.put(k, JSONObject().put("pos", p.position).put("dur", p.duration).put("done", p.done))
        }
        val arr = JSONArray()
        items.sortedByDescending { e -> e.updated }.take(MAX_ITEMS).forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("type", e.type).put("name", e.name).put("year", e.year)
                    .put("videoId", e.videoId).put("label", e.label).put("pos", e.position).put("dur", e.duration)
                    .put("upNext", e.upNext).put("updated", e.updated)
            )
        }
        val lib = JSONArray()
        library.forEach { s -> lib.put(JSONObject().put("id", s.id).put("type", s.type).put("name", s.name).put("year", s.year)) }
        runCatching { file.writeText(JSONObject().put("videos", v).put("items", arr).put("library", lib).toString()) }
    }

    /** A film or episode counts as finished near the end: the last 8%, or the last 2 minutes. */
    private fun finished(pos: Long, dur: Long) = dur > 0 && (pos >= dur * 0.92 || dur - pos < 120_000)

    /**
     * Files what the player reported. [position]/[duration] are in ms (null if the player didn't say).
     * [completed] is true when the player says it reached the end.
     */
    fun record(c: Context, w: Watch, position: Long?, duration: Long?, completed: Boolean) {
        ensure(c)
        val pos = position ?: 0L
        val dur = duration ?: 0L
        val done = completed || finished(pos, dur)
        if (position != null || completed) {
            videos.remove(w.videoId)
            videos[w.videoId] = Progress(if (done) 0 else pos, dur, done || videos[w.videoId]?.done == true)
        }
        items.removeAll { it.id == w.id }
        val now = System.currentTimeMillis()
        when {
            !done -> items += Item(w.id, w.type, w.name, w.year, w.videoId, w.label, pos, dur, false, now)
            w.nextVideoId != null -> items += Item(w.id, w.type, w.name, w.year, w.nextVideoId, w.nextLabel ?: "", 0, 0, true, now)
            // a finished film, or the last episode: drops off Continue watching
        }
        save()
    }

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

    fun removeItem(c: Context, id: String) {
        ensure(c)
        items.removeAll { it.id == id }
        save()
    }

    fun inLibrary(c: Context, id: String): Boolean {
        ensure(c)
        return library.any { it.id == id }
    }

    fun toggleLibrary(c: Context, s: Saved): Boolean {
        ensure(c)
        val had = library.removeAll { it.id == s.id }
        if (!had) library.add(0, s)
        save()
        return !had
    }

    fun library(c: Context): List<Saved> {
        ensure(c)
        return library.toList()
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    // ---- the pending playback survives the app being closed while the player runs ----

    private const val PREFS = "playback"

    fun setPending(c: Context, w: Watch?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pending", w?.toJson()?.toString()).apply()
    }

    fun takePending(c: Context): Watch? {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getString("pending", null) ?: return null
        p.edit().remove("pending").apply()
        return runCatching { Watch.from(JSONObject(s)) }.getOrNull()
    }
}
