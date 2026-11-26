package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Next watch: titles you've lined up, in your own order. Kept on the Stremio account as one hidden
 * record (removed, so Stremio's own apps never show it), so every device shares the list.
 * A title leaves it once you start it (it's in Continue watching then) or finish it.
 */
object NextWatch {
    const val ID = "stremiolite:next"

    /** One title; an episode put here from Ready has its video id as [id] ("tt…:2:5") and [show] set. */
    class Entry(val id: String, val type: String, val name: String, val year: String, val show: String = "") {
        fun json(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("year", year)
            .apply { if (show.isNotEmpty()) put("show", show) }
        val isEpisode get() = show.isNotEmpty()
    }

    fun parse(raw: String?): List<Entry> {
        val name = raw?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() } ?: return emptyList()
        val arr = runCatching { JSONArray(name) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Entry(it.optString("id"), it.optString("type"), it.optString("name"), it.optString("year"), it.optString("show")) }
        }.filter { it.id.isNotEmpty() }.distinctBy { it.id }
    }

    /** The account record holding [list], keeping anything else [base] has. */
    fun record(base: String?, list: List<Entry>, id: String = ID): JSONObject {
        val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(id, "other", "[]")
        return item.put("name", JSONArray().apply { list.forEach { put(it.json()) } }.toString())
            .put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** The list without titles you've started or finished. */
    fun stillNext(list: List<Entry>, started: (String) -> Boolean): List<Entry> = list.filter { !started(it.id) }

    fun added(list: List<Entry>, e: Entry): List<Entry> = list.filter { it.id != e.id } + e
    fun removed(list: List<Entry>, id: String): List<Entry> = list.filter { it.id != id }
}

/**
 * The basket: titles you liked the look of while browsing, to decide about later (Next watch,
 * Favourites, Waiting). Same record shape as [NextWatch], under its own hidden id.
 */
object Basket {
    const val ID = "stremiolite:basket"
    fun parse(raw: String?) = NextWatch.parse(raw)
    fun record(base: String?, list: List<NextWatch.Entry>) = NextWatch.record(base, list, ID)
}

/** Episodes you've dismissed from Ready, kept on the account so every device leaves them out. */
object ReadyGone {
    const val ID = "stremiolite:readygone"
    private const val KEEP = 300

    fun parse(raw: String?): Set<String> = NextWatch.parse(raw).mapTo(LinkedHashSet()) { it.id }

    /** The record with [id] added (the oldest dropped past a few hundred). */
    fun add(base: String?, id: String): JSONObject {
        val ids = (parse(base) + id).toList().takeLast(KEEP)
        return NextWatch.record(base, ids.map { NextWatch.Entry(it, "", "", "") }, ID)
    }
}
