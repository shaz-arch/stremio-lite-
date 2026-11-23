package app.stremiolite.linux

import app.stremiolite.Language
import app.stremiolite.LibraryItems
import app.stremiolite.Languages
import app.stremiolite.Pref
import app.stremiolite.Stream
import app.stremiolite.Sync
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Files in ~/.config/stremonino (moved from ~/.config/stremio-lean on first run): the sign-in, settings,
 * and caches of the account's data. The first profile uses the folder itself; others get profiles/<id>/.
 */
object Store {
    val root: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        val old = File(base, "stremio-lean")
        val now = File(base, "stremonino")
        if (!now.exists() && old.isDirectory) old.renameTo(now)
        now.apply { mkdirs() }
    }

    private var current: File? = null

    /** The current profile's folder. */
    val dir: File get() = current ?: root

    fun use(d: File) { d.mkdirs(); current = d }

    fun file(name: String) = File(dir, name)

    fun readJson(name: String): JSONObject = runCatching { JSONObject(file(name).readText()) }.getOrDefault(JSONObject())

    fun writeJson(name: String, o: JSONObject) {
        val f = file(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(f)
        if (name == "account.json") runCatching { f.setReadable(false, false); f.setReadable(true, true) }
    }
}

/** The signed-in Stremio account. */
object Account {
    private var cache: JSONObject? = null
    private fun data() = cache ?: Store.readJson("account.json").also { cache = it }

    val authKey: String? get() = data().optString("authKey").ifEmpty { null }
    val email: String get() = data().optString("email")

    fun save(authKey: String, email: String) {
        val o = JSONObject().put("authKey", authKey).put("email", email)
        cache = o
        Store.writeJson("account.json", o)
    }

    /** Forgets what's in memory (after switching profile). */
    fun reload() { cache = null }

    fun clear() {
        cache = JSONObject()
        Store.file("account.json").delete()
        Store.file("addons.json").delete()
        Store.file("library.json").delete()
    }
}

/** Settings on this computer. Theme, subtitle language and hidden rows are shared through the account. */
object Prefs {
    private var cache: JSONObject? = null
    private fun data() = cache ?: Store.readJson("settings.json").also { cache = it }
    private fun save() = Store.writeJson("settings.json", data())

    var theme: Int
        get() = data().optInt("theme", 0)
        set(v) { data().put("theme", v); save() }
    var subLang: Int
        get() = data().optInt("subLang", 1)
        set(v) { data().put("subLang", v); save() }
    var autoNext: Boolean
        get() = data().optBoolean("autoNext", true)
        set(v) { data().put("autoNext", v); save() }
    var clock: Int
        get() = data().optInt("clock", 0)
        set(v) { data().put("clock", v); save() }
    var hideWatched: Boolean
        get() = data().optBoolean("hideWatched", false)
        set(v) { data().put("hideWatched", v); save() }
    var player: String
        get() = data().optString("player", "mpv").ifBlank { "mpv" }
        set(v) { data().put("player", v); save() }
    var playerArgs: String
        get() = data().optString("playerArgs", "")
        set(v) { data().put("playerArgs", v); save() }
    var syncedAt: Long
        get() = data().optLong("syncedAt", 0)
        set(v) { data().put("syncedAt", v); save() }
    var hiddenRows: Set<String>
        get() = data().optJSONArray("hiddenRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        set(v) { data().put("hiddenRows", JSONArray(v.toList())); save() }

    fun language(): Language = Languages.all.getOrElse(subLang) { Languages.all[1] }

    /** Forgets what's in memory (after switching profile). */
    fun reload() { cache = null }

    /** The shared settings, as "Share my setup" sends them (same shape as the Android app's). */
    fun exportShared(): JSONObject {
        val d = data()
        val o = JSONObject()
        SHARED.forEach { k -> if (d.has(k)) o.put(k, d.opt(k)) }
        return o.put("hiddenRows", JSONArray(hiddenRows.toList()))
    }

    /** Takes someone's shared settings, plus their hidden rows and [alsoHide]. */
    fun importShared(o: JSONObject, alsoHide: Set<String>) {
        val d = data()
        SHARED.forEach { k -> if (o.has(k)) d.put(k, o.opt(k)) }
        val theirs = o.optJSONArray("hiddenRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        d.put("hiddenRows", JSONArray((hiddenRows + theirs + alsoHide).toList()))
        save()
        syncOut()
    }

    // ---- shared with the other devices (the "stremiolite:settings" record) ------------------

    private const val SYNC_ID = "stremiolite:settings"

    /** Shared settings this build knows; one this computer has never had is always taken from the account. */
    private val SHARED = listOf("theme", "subLang", "autoNext", "clock", "hideWatched")

    /** Takes the account's shared settings if they're newer than this computer's. */
    fun syncIn(): Boolean {
        val raw = Lib.raw(SYNC_ID) ?: return false
        val o = runCatching { JSONObject(raw.optString("name")) }.getOrNull() ?: return false
        val updated = o.optLong("updated")
        val d = data()
        val newer = updated > syncedAt
        var changed = false
        for (k in SHARED) {
            if (!o.has(k) || (!newer && d.has(k))) continue
            if (d.opt(k) != o.opt(k)) { d.put(k, o.opt(k)); changed = true }
        }
        if (newer) o.optJSONArray("hiddenRows")?.let { d.put("hiddenRows", it); changed = true }
        if (newer) d.put("syncedAt", updated)
        save()
        return changed
    }

    /** Sends theme, clock, subtitle language, auto-next, watched-films choice and hidden rows, keeping anything else the record holds. */
    fun syncOut() {
        val now = System.currentTimeMillis()
        val raw = Lib.raw(SYNC_ID)
        val o = raw?.let { runCatching { JSONObject(it.optString("name")) }.getOrNull() } ?: JSONObject()
        // Only settings chosen on this computer (or already taken from the account): never a default.
        val d = data()
        SHARED.forEach { k -> if (d.has(k)) o.put(k, d.opt(k)) }
        o.put("hiddenRows", JSONArray(hiddenRows.toList())).put("updated", now)
        syncedAt = now
        val item = raw ?: Sync.newItem(SYNC_ID, "other", "{}")
        item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(now))
        Lib.put(item)
    }
}

object Look {
    fun theme(): Theme = themes.getOrElse(Prefs.theme) { themes[0] }

    /** The same clock choices as the phone and stick (shared through the account); empty pattern = off. */
    class Clock(val name: String, val pattern: String)

    val clocks = listOf(
        Clock("Day/month, 24 h", "dd/MM HH:mm"),
        Clock("Weekday, day/month, 24 h", "EEE dd/MM HH:mm"),
        Clock("Time only, 24 h", "HH:mm"),
        Clock("Day/month, 12 h", "dd/MM h:mm a"),
        Clock("Off", ""),
        // With seconds (added after "Off" so existing choices keep their place in the shared settings).
        Clock("Day/month, 24 h, seconds", "dd/MM HH:mm:ss"),
        Clock("Weekday, day/month, 24 h, seconds", "EEE dd/MM HH:mm:ss"),
        Clock("Time only, 24 h, seconds", "HH:mm:ss"),
        Clock("Day/month, 12 h, seconds", "dd/MM h:mm:ss a"),
    )

    fun clock(): Clock = clocks.getOrElse(Prefs.clock) { clocks[0] }
}

/** The release last played per show, so the next episode keeps the same look and timing. */
object Binge {
    fun lastFor(showId: String): Pref? {
        val v = Store.readJson("binge.json").optString(showId).ifEmpty { return null }
        val p = v.split('|')
        return if (p.size >= 3) Pref(p[0], p[1], p[2]) else null
    }

    fun remember(showId: String, s: Stream) {
        val o = Store.readJson("binge.json")
        o.put(showId, "${s.group}|${s.quality}|${s.source}")
        val keys = o.keys().asSequence().toList()
        if (keys.size > 40) keys.take(keys.size - 40).forEach { o.remove(it) }
        Store.writeJson("binge.json", o)
    }
}

/** What is being played, so the result can be filed against it. */
class Watch(
    val id: String,
    val type: String,
    val name: String,
    val videoId: String,
    val label: String,
    val runtime: String,
    val videoIds: List<String> = emptyList(),
    val nextVideoId: String? = null,
    val nextLabel: String? = null,
)

/**
 * The account's library records: Continue watching, Library, watched marks and resume points,
 * plus the hidden records (shared settings, waiting list). Cached on disk, synced with the account.
 */
object Lib {
    private val records = LinkedHashMap<String, JSONObject>()
    private val unsent = LinkedHashSet<String>()
    private var loaded = false
    private var pulling = false
    var lastPull = 0L
        private set

    private fun ensure() {
        if (loaded) return
        loaded = true
        val o = Store.readJson("library.json")
        o.optJSONArray("records")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { records[it.optString("_id")] = it } }
        o.optJSONArray("unsent")?.let { a -> for (i in 0 until a.length()) unsent += a.optString(i) }
    }

    private fun save() {
        val o = JSONObject().put("records", JSONArray(records.values.toList())).put("unsent", JSONArray(unsent.toList()))
        runCatching { Store.writeJson("library.json", o) }
    }

    fun reset() {
        records.clear(); unsent.clear(); loaded = true; lastPull = 0
    }

    /** Forgets what's in memory so the next use reads the (new) profile's file. */
    fun unload() {
        records.clear(); unsent.clear(); loaded = false; lastPull = 0
    }

    fun raw(id: String): JSONObject? { ensure(); return records[id] }

    fun put(item: JSONObject) {
        ensure()
        val id = item.optString("_id")
        records[id] = item
        unsent += id
        save()
        send()
    }

    fun infos(): List<LibraryItems.Info> { ensure(); return records.values.map { LibraryItems.read(it) } }

    fun continueWatching() = infos().filter { it.continuing }.sortedByDescending { it.updated }
    fun library() = infos().filter { it.inLibrary }.sortedByDescending { it.updated }
    fun watchedFilms() = infos().filter { it.filmWatched }.sortedByDescending { it.updated }

    fun info(id: String) = raw(id)?.let { LibraryItems.read(it) }

    fun inLibrary(id: String) = info(id)?.inLibrary == true

    /** Where to resume [videoId], in ms (0 = from the start). */
    fun resumeAt(id: String, videoId: String): Long {
        val i = info(id) ?: return 0
        return if (i.videoId == videoId && i.offset > 60_000 && !i.upNext &&
            !(i.duration > 0 && LibraryItems.finished(i.offset, i.duration))) i.offset else 0
    }

    fun filmWatched(id: String) = info(id)?.filmWatched == true

    /** Episodes the account marks as watched, given the show's episodes in Stremio's order. */
    fun watchedEpisodes(id: String, order: List<String>): Set<String> =
        info(id)?.let { Sync.decodeWatched(it.watchedField, order) } ?: emptySet()

    fun record(w: Watch, pos: Long, dur: Long, positionKnown: Boolean, done: Boolean) {
        ensure()
        if (done) Watched.add(w)
        val item = LibraryItems.update(
            existing = records[w.id], id = w.id, type = w.type, name = w.name, videoId = w.videoId,
            pos = pos, dur = dur, done = done, positionKnown = positionKnown,
            videoIds = w.videoIds, alsoDone = emptySet(), nextVideoId = w.nextVideoId,
        )
        put(item)
    }

    /** Takes a film or episode off the watched list ([videoIds]: the show's episodes in Stremio's order). */
    fun unwatch(id: String, type: String, videoId: String, videoIds: List<String>) {
        ensure()
        val item = records[id] ?: return
        put(LibraryItems.unwatch(item, videoId, if (type == "movie") emptyList() else videoIds))
    }

    fun toggleLibrary(id: String, type: String, name: String): Boolean {
        ensure()
        val had = inLibrary(id)
        val item = records[id] ?: Sync.newItem(id, type, name)
        item.put("removed", had).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
        put(item)
        return !had
    }

    /** Stores several changed records and sends them in one go. */
    fun putAll(items: List<JSONObject>) {
        ensure()
        items.forEach { val id = it.optString("_id"); records[id] = it; unsent += id }
        save()
        send()
    }

    /** Empties Continue watching (resume points); watched marks stay. */
    fun clearContinue() {
        val now = Sync.iso(System.currentTimeMillis())
        putAll(continueWatching().mapNotNull { i -> records[i.id]?.also { it.optJSONObject("state")?.put("timeOffset", 0); it.put("_mtime", now) } })
    }

    /** Empties the Library; history and watched marks stay. */
    fun clearLibrary() {
        val now = Sync.iso(System.currentTimeMillis())
        putAll(library().mapNotNull { i -> records[i.id]?.also { it.put("removed", true).put("temp", false).put("_mtime", now) } })
    }

    fun removeFromContinue(id: String) {
        ensure()
        val item = records[id] ?: return
        item.optJSONObject("state")?.put("timeOffset", 0)
        item.put("_mtime", Sync.iso(System.currentTimeMillis()))
        put(item)
    }

    private fun send() {
        val key = Account.authKey ?: return
        val ids = unsent.toList()
        if (ids.isEmpty()) return
        val items = ids.mapNotNull { records[it] }.map { JSONObject(it.toString()) }
        App.async({ Sync.push(key, items) }) { r ->
            if (r.isSuccess) {
                unsent.removeAll(ids.toSet())
                save()
            }
        }
    }

    /** Fetches the account's library; [done] runs on the main loop afterwards. */
    fun pull(force: Boolean = false, done: (() -> Unit)? = null) {
        ensure()
        val key = Account.authKey ?: return
        if (pulling || (!force && System.currentTimeMillis() - lastPull < 60_000)) return
        pulling = true
        send()
        App.async({ Sync.pull(key) }) { r ->
            pulling = false
            r.onSuccess { arr ->
                lastPull = System.currentTimeMillis()
                val keep = unsent.toSet()
                val fresh = LinkedHashMap<String, JSONObject>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("_id")
                    if (id.isEmpty()) continue
                    fresh[id] = if (id in keep) records[id] ?: o else o
                }
                keep.forEach { id -> records[id]?.let { fresh.putIfAbsent(id, it) } }
                records.clear()
                records.putAll(fresh)
                save()
                if (Prefs.syncIn()) App.refresh()
                done?.invoke()
            }.onFailure { e -> App.toast("Couldn't sync your history: ${Net.describe(e)}") }
        }
    }
}


/**
 * Films and episodes finished on this computer (newest first), like the phone's Watched list,
 * plus films the account marks as watched from other devices.
 */
object Watched {
    class Entry(val id: String, val type: String, val name: String, val videoId: String, val label: String, val time: Long)

    private fun read(): List<Entry> {
        val a = Store.readJson("watched.json").optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Entry(it.optString("id"), it.optString("type"), it.optString("name"), it.optString("videoId"), it.optString("label"), it.optLong("time")) }
        }
    }

    private fun write(list: List<Entry>) {
        val a = JSONArray()
        list.take(300).forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("videoId", it.videoId).put("label", it.label).put("time", it.time)) }
        Store.writeJson("watched.json", JSONObject().put("list", a))
    }

    fun add(w: Watch) {
        val e = Entry(w.id, w.type, w.name, w.videoId, w.label, System.currentTimeMillis())
        write(listOf(e) + read().filter { !(it.id == e.id && it.videoId == e.videoId) })
    }

    fun remove(id: String, videoId: String) = write(read().filter { !(it.id == id && (it.videoId == videoId || videoId.isEmpty())) })

    fun list(): List<Entry> {
        val mine = read()
        val films = Lib.watchedFilms().filter { f -> mine.none { it.id == f.id } }
            .map { Entry(it.id, it.type, it.name, it.id, "", it.updated) }
        return (mine.filter { it.type != "movie" || Lib.filmWatched(it.id) } + films).sortedByDescending { it.time }
    }
}
