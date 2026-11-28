package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Stremio account's library records, shared by every front-end: Continue watching, the
 * Library, watched marks and resume points, plus the hidden "other" records (shared settings,
 * the waiting list, following…). Each is kept whole, as JSON text by id, so nothing Stremio
 * stores is lost.
 *
 * The account is the source of truth: a pull replaces every record with the account's copy, except
 * records changed here that haven't reached the account yet ([isUnsent]); those are sent first and
 * retried on every sync until they arrive.
 *
 * Each front-end keeps its own file ([load] at start, [all] and [unsentIds] when saving: told by
 * [onChanged]) and builds its own views (Continue watching rows, marks…) from the records.
 */
object AccountLibrary {
    private const val PULL_EVERY_MS = 60_000L
    /** More changed records than this: fetch everything instead of one by one. */
    private const val MAX_BY_ID = 150

    private val records = LinkedHashMap<String, String>()
    private val unsent = LinkedHashSet<String>()
    private var pulling = false
    /** False until a full pull has happened since the app started (or the profile changed). */
    private var fullDone = false

    /** When the last pull finished (0 = not yet). */
    var lastPull = 0L
        private set

    /** Goes up with every change, so views built from the records know when to rebuild. */
    var version = 0
        private set

    /** Goes up when another profile's records are loaded (or they're forgotten). */
    private var generation = 0

    /** Runs after every change, so the front-end saves its file. */
    var onChanged: (() -> Unit)? = null

    private fun changed() {
        version++
        onChanged?.invoke()
    }

    // ---- the front-end's file -----------------------------------------------------------------

    /** Replaces what's in memory with what the front-end's file holds. Doesn't count as a change. */
    fun load(saved: Map<String, String>, unsentIds: Collection<String>) {
        records.clear(); records.putAll(saved)
        unsent.clear(); unsent.addAll(unsentIds)
        generation++
        version++
    }

    /** Forgets what's in memory, and when it last synced (another profile, or signing out). */
    fun forget() {
        records.clear(); unsent.clear()
        lastPull = 0; fullDone = false; pulling = false
        generation++
        version++
    }

    /** Wipes every record (signing out), and saves that. */
    fun clear() {
        forget()
        changed()
    }

    /** Every record, as (id, JSON text), for saving. */
    fun all(): List<Pair<String, String>> = records.entries.map { it.key to it.value }

    fun unsentIds(): List<String> = unsent.toList()

    fun isUnsent(id: String) = id in unsent

    // ---- reading -----------------------------------------------------------------------------

    /** One record as JSON text (e.g. the waiting list), or null. */
    fun raw(id: String): String? = records[id]

    /** One record, as a copy you can change and [put] back. */
    fun get(id: String): JSONObject? = records[id]?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Every record, read. */
    fun infos(): List<LibraryItems.Info> = records.values.mapNotNull { runCatching { LibraryItems.read(JSONObject(it)) }.getOrNull() }

    // ---- changing ----------------------------------------------------------------------------

    /** Stores [items] exactly as given and sends them to the account in one go. */
    fun put(authKey: String?, items: List<JSONObject>) {
        if (items.isEmpty()) return
        items.forEach { val id = it.optString("_id"); records[id] = it.toString(); unsent += id }
        changed()
        send(authKey)
    }

    fun put(authKey: String?, item: JSONObject) = put(authKey, listOf(item))

    /**
     * Files a play on the title's record, the way Stremio's own player does it.
     * [alsoDone]: episodes this device knows are finished, to keep in the watched marks.
     */
    fun recordPlay(
        authKey: String?, id: String, type: String, name: String, videoId: String,
        pos: Long, dur: Long, done: Boolean, positionKnown: Boolean,
        videoIds: List<String>, alsoDone: Set<String>, nextVideoId: String?, now: Long = System.currentTimeMillis(),
    ) = put(authKey, LibraryItems.update(
        existing = get(id), id = id, type = type, name = name, videoId = videoId,
        pos = pos, dur = dur, done = done, positionKnown = positionKnown,
        videoIds = videoIds, alsoDone = alsoDone, nextVideoId = nextVideoId, now = now,
    ))

    /** Takes a film or an episode off the watched marks ([videoIds]: a show's episodes in Stremio's order). */
    fun unwatch(authKey: String?, id: String, type: String, videoId: String, videoIds: List<String>) {
        val item = get(id) ?: return
        put(authKey, LibraryItems.unwatch(item, videoId, if (type == "movie") emptyList() else videoIds))
    }

    /** Clears the resume points of [ids] (off Continue watching); watched marks stay. */
    fun removeFromContinue(authKey: String?, ids: Collection<String>) {
        val now = Sync.iso(System.currentTimeMillis())
        put(authKey, ids.mapNotNull { id ->
            get(id)?.also { it.optJSONObject("state")?.put("timeOffset", 0); it.put("_mtime", now) }
        })
    }

    /** A title on the Library, or off it (it stays in your history and watched marks). */
    class Title(val id: String, val type: String, val name: String)

    fun setInLibrary(authKey: String?, titles: Collection<Title>, inLibrary: Boolean) {
        val now = Sync.iso(System.currentTimeMillis())
        put(authKey, titles.map { t ->
            (get(t.id) ?: Sync.newItem(t.id, t.type, t.name)).put("removed", !inLibrary).put("temp", false).put("_mtime", now)
        })
    }

    // ---- syncing with the account ------------------------------------------------------------

    /** Sends records changed here; anything that fails is retried on the next sync. */
    fun send(authKey: String?) {
        val key = authKey ?: return
        unsent.retainAll(records.keys)   // nothing to send for these
        val ids = unsent.toList()
        if (ids.isEmpty()) return
        val sent = ids.mapNotNull { id -> records[id]?.let { id to it } }
        val profile = generation
        Platform.async.background({ Sync.push(key, sent.map { JSONObject(it.second) }) }) { r ->
            if (r.isSuccess && generation == profile) {
                // A record changed again while it was being sent stays unsent, so the newer copy goes too.
                sent.forEach { (id, raw) -> if (records[id] == raw) unsent.remove(id) }
                changed()
            }
        }
    }

    /**
     * What a pull brought: every record now (the account's, with records not yet sent kept as they
     * are here, which [skipped] lists), and whether it was a [whole] fetch.
     */
    class Pulled(val records: JSONArray, val skipped: Set<String>, val whole: Boolean)

    /**
     * Brings in changes from the account (at most once a minute unless [force]). Normally only the
     * records that changed are fetched (the account lists ids and change times first); the first pull
     * after the app starts, and [full] ones (Reload), fetch everything as a safety net.
     *
     * [prepare] runs in the background with what came, so a front-end can build its views off the
     * screen's thread; [done] then runs on the screen's thread with its result. It gets null when
     * nothing changed, and isn't called at all when no pull happened (too soon, or already pulling).
     */
    fun <T> pull(
        authKey: String?, force: Boolean = false, full: Boolean = false,
        prepare: (Pulled) -> T, done: (Result<T?>) -> Unit,
    ) {
        val key = authKey ?: return
        if (pulling || (!force && !full && System.currentTimeMillis() - lastPull < PULL_EVERY_MS)) return
        pulling = true
        val started = System.currentTimeMillis()
        send(key)
        val skip = unsent.toSet()
        val whole = full || !fullDone
        val copy = LinkedHashMap(records)
        val profile = generation
        Platform.async.background({
            fetch(key, whole, copy, skip)?.let { (fresh, everything) ->
                val now = merge(fresh, everything, copy, skip)
                val arr = JSONArray()
                now.values.forEach { raw -> runCatching { arr.put(JSONObject(raw)) } }
                now to prepare(Pulled(arr, skip, everything))
            }
        }) { r ->
            // Another profile was chosen (or signed out) while this ran: its result isn't for this one.
            if (generation != profile) return@background
            pulling = false
            Timings.record(if (whole) "Account sync (full)" else "Account sync", System.currentTimeMillis() - started)
            r.onSuccess { got ->
                lastPull = System.currentTimeMillis()
                if (whole) fullDone = true
                if (got != null) apply(got.first)
            }
            done(r.map { it?.second })
        }
    }

    /** The account's records ([Pair.second]: everything, or only those that changed), or null if nothing changed. */
    private fun fetch(key: String, whole: Boolean, copy: Map<String, String>, skip: Set<String>): Pair<JSONArray, Boolean>? {
        if (whole) return Sync.pull(key) to true
        // Any trouble with the change list: just do a full pull, as before.
        val meta = runCatching { Sync.meta(key) }.getOrNull() ?: return Sync.pull(key) to true
        val want = Sync.changed(meta, copy.mapValues { Sync.mtimeOf(it.value) }, skip)
        return when {
            want.isEmpty() -> null
            want.size > MAX_BY_ID -> Sync.pull(key) to true
            else -> Sync.pullIds(key, want) to false
        }
    }

    /**
     * The records after a pull. Everything fetched: the account's records exactly (those removed on
     * the account go here too), except [skip]. Only some: those replace their old copies; nothing is
     * dropped (Stremio marks removals on the record itself, and the next full pull tidies the rest).
     */
    private fun merge(fresh: JSONArray, everything: Boolean, copy: Map<String, String>, skip: Set<String>): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!everything) out.putAll(copy)
        for (i in 0 until fresh.length()) {
            val o = fresh.optJSONObject(i) ?: continue
            val id = o.optString("_id")
            if (id.isEmpty() || id in skip) continue
            out[id] = o.toString()
        }
        for (id in skip) copy[id]?.let { out[id] = it }
        return out
    }

    /** Takes a pull's records, keeping anything changed here meanwhile (not sent yet). */
    private fun apply(now: Map<String, String>) {
        val mine = unsent.mapNotNull { id -> records[id]?.let { id to it } }
        records.clear()
        records.putAll(now)
        mine.forEach { (id, raw) -> records[id] = raw }
        changed()
    }
}
