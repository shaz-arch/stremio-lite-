package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Waiting", shared by every front-end: films that are out but have no good stream yet, or that
 * you want a better version of. Films not out to stream yet, and shows, are in Following.
 *
 * The list lives on the Stremio account as one hidden record (removed, so Stremio's own apps never
 * show it), so every device shares it. The checks here are blocking: run them in the background.
 */
object WaitingList {
    const val ID = "stremiolite:waiting"
    /** At most this many titles are checked in one go. */
    const val MAX_CHECKS = 20

    /**
     * [ready]: when a stream was first found (0 = still waiting).
     * [better]: waiting for a better version than [have] (a stream scoring [bar]); [found] is what turned up.
     */
    class Entry(
        val id: String, val type: String, val name: String, val year: String, val ready: Long,
        val better: Boolean = false, val bar: Double = 0.0, val have: String = "", val found: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("year", year).put("ready", ready)
            .apply { if (better) put("better", true).put("bar", bar).put("have", have).put("found", found) }

        fun readyWith(now: Long, what: String) = Entry(id, type, name, year, now, better, bar, have, what)

        /** Back to waiting (nothing found yet). */
        fun notReady() = Entry(id, type, name, year, 0, better, bar, have, "")
    }

    /** The list in the account's record (its JSON text), or empty. */
    fun parse(raw: String?): List<Entry> {
        val name = raw?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() } ?: return emptyList()
        val arr = runCatching { JSONArray(name) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Entry(
                    it.optString("id"), it.optString("type"), it.optString("name"), it.optString("year"), it.optLong("ready"),
                    it.optBoolean("better"), it.optDouble("bar", 0.0), it.optString("have"), it.optString("found"),
                )
            }
        }.filter { it.id.isNotEmpty() }
    }

    /** The account's record holding [list], starting from the one it has ([base], JSON text). */
    fun record(base: String?, list: List<Entry>): JSONObject {
        val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "[]")
        return item.put("name", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
            .put("removed", true).put("temp", false)
            .put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** [list] with [e] in it (in place of any entry for the same title). */
    fun with(list: List<Entry>, e: Entry): List<Entry> = list.filter { it.id != e.id } + e

    /**
     * "Tell me when a better version appears" (blocking): notes what Play best would pick now, so the
     * check knows what to beat.
     */
    fun better(addons: List<Addon>, id: String, type: String, name: String, year: String): Entry {
        val now = runCatching { StreamParse.bestPlayable(addons, type, id) }.getOrNull()
        val have = now?.first?.summary?.let { tidy(it) } ?: "nothing playable yet"
        return Entry(id, type, name, year, 0, true, now?.second ?: 0.0, have, "")
    }

    /**
     * Play best found nothing that plays for a Next watch film: it goes (back) into Waiting, not
     * ready, so Next watch stops calling it ready until a stream that plays turns up.
     */
    fun unplayable(list: List<Entry>, id: String, name: String): List<Entry> {
        NextStatus.forget(id)
        return if (list.none { it.id == id }) list + Entry(id, "movie", name, "", 0)
        else list.map { if (it.id == id) it.notReady() else it }
    }

    /**
     * Followed films (blocking): ones now out to stream come off Following ([fol], the record) and
     * join [wait]; the rest get a fresh "when and where". Returns the changed record and list (null = unchanged).
     */
    fun outCheck(addons: List<Addon>, tmdbKey: String, region: String, fol: JSONObject?, wait: List<Entry>, now: Long): Pair<JSONObject?, List<Entry>?> {
        val out = mutableListOf<Following.Film>(); val notes = mutableListOf<Following.Film>()
        Following.films(fol).take(MAX_CHECKS).forEach { f ->
            val (isOut, note) = runCatching { FilmOut.check(addons, tmdbKey, region, f.id, now) }.getOrNull() ?: return@forEach
            if (isOut) out += f else if (note != f.note) notes += Following.Film(f.id, f.name, f.year, note)
        }
        var rec = fol
        if (notes.isNotEmpty()) rec = Following.followFilms(rec, notes)
        if (out.isNotEmpty()) rec = Following.unfollowFilms(rec, out.map { it.id })
        val add = out.filter { o -> wait.none { it.id == o.id && !it.better } }.map { Entry(it.id, "movie", it.name, it.year, 0) }
        return (if (rec !== fol) rec else null) to (if (add.isNotEmpty()) wait + add else null)
    }

    /**
     * The check itself (blocking): reads the list from the account, moves followed films that are now
     * out to stream into it, asks the stream add-ons about each film still waiting, marks the ones now
     * available, sends the changes to the account, and returns the ones that became ready.
     * [tmdbKey]: with one, films only in cinemas move to Following instead ("not out yet").
     */
    fun check(authKey: String, addons: List<Addon>, tmdbKey: String, region: String, now: Long = System.currentTimeMillis()): List<Entry> {
        val arr = Sync.pullIds(authKey, listOf(ID, Following.ID))
        val objs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val raw = objs.firstOrNull { it.optString("_id") == ID }?.toString()
        val fol = objs.firstOrNull { it.optString("_id") == Following.ID }
        var all = parse(raw)
        // Followed films now out to stream move here, to be checked for a good stream.
        var moved = false
        var folNow = fol
        if (Following.films(fol).isNotEmpty()) {
            val (newFol, newAll) = outCheck(addons, tmdbKey, region, fol, all, now)
            if (newFol != null) { Sync.push(authKey, listOf(newFol)); folNow = newFol }
            if (newAll != null) { all = newAll; moved = true }
        }
        val notOut = mutableListOf<Following.Film>()
        val got = HashMap<String, String>()
        val today = ComingSoon.today(now)
        all.filter { it.ready == 0L }.take(MAX_CHECKS).forEach { e ->
            // Only in cinemas, digital later: anything found now is a fake or a cam, not "ready".
            val w = if (tmdbKey.isNotBlank() && e.type == "movie")
                runCatching { WhereToWatch.load(tmdbKey, "movie", e.id, "", region) }.getOrNull()?.takeIf { it.notOutYet(today) } else null
            if (w != null) {
                // Not out to stream yet: it moves to Following (a "better version" wait stays here).
                if (!e.better) notOut += Following.Film(e.id, e.name, e.year, w.notOutLabel(today))
                return@forEach
            }
            if (e.better) runCatching { StreamParse.betterThan(addons, e.type, e.id, e.bar) }.getOrNull()?.let { got[e.id] = tidy(it.first.summary) }
            else if (runCatching { StreamParse.availableNow(addons, e.type, e.id) }.getOrDefault(false)) got[e.id] = ""
        }
        if (notOut.isNotEmpty()) {
            Sync.push(authKey, listOf(Following.followFilms(folNow, notOut)))
            val ids = notOut.map { it.id }.toSet()
            all = all.filter { it.better || it.id !in ids }; moved = true
        }
        if (got.isEmpty()) {
            if (moved) Sync.push(authKey, listOf(record(raw, all)))
            return emptyList()
        }
        val updated = all.map { e -> got[e.id]?.let { e.readyWith(now, it) } ?: e }
        Sync.push(authKey, listOf(record(raw, updated)))
        return updated.filter { it.id in got }
    }

    private fun tidy(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
