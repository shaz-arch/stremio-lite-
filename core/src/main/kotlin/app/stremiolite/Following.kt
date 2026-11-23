package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which shows you follow for new episodes and seasons, kept apart from the Library:
 *  - shows you've started watching are followed automatically;
 *  - shows you follow on purpose (the "Tell me about new episodes and seasons" button);
 *  - your own list rows (e.g. the MDBList Watchlist);
 *  minus anything you've stopped following. Stored on the account as one hidden record.
 */
object Following {
    const val ID = "stremiolite:following"

    class Choices(val follow: Map<String, String>, val unfollow: Set<String>)

    enum class Section { CAUGHT_UP, WATCHING, NOT_STARTED, FINISHED }

    fun read(record: JSONObject?): Choices {
        val o = record?.optString("name")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Choices(emptyMap(), emptySet())
        val follow = LinkedHashMap<String, String>()
        o.optJSONArray("follow")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { follow[it.optString("id")] = it.optString("name") } }
        val unfollow = o.optJSONArray("unfollow")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        return Choices(follow, unfollow)
    }

    private fun write(record: JSONObject?, c: Choices): JSONObject {
        val o = JSONObject()
            .put("follow", JSONArray(c.follow.map { (id, name) -> JSONObject().put("id", id).put("name", name) }))
            .put("unfollow", JSONArray(c.unfollow.toList()))
        val item = record ?: Sync.newItem(ID, "other", "{}")
        return item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** The record after following [id] on purpose (and no longer "stopped"). */
    fun follow(record: JSONObject?, id: String, name: String): JSONObject {
        val c = read(record)
        return write(record, Choices(c.follow + (id to name), c.unfollow - id))
    }

    /** The record after stopping following [id] (it stays stopped even if you watch more). */
    fun unfollow(record: JSONObject?, id: String): JSONObject {
        val c = read(record)
        return write(record, Choices(c.follow - id, c.unfollow + id))
    }

    /** A show counts as started once any episode has been watched or played. */
    fun started(i: LibraryItems.Info) = i.type == "series" &&
        (i.watchedField.isNotEmpty() || i.offset > 0 || i.timesWatched > 0 || (i.videoId != i.id && i.videoId.isNotEmpty()))

    /**
     * Every followed show (id to name): started shows, chosen ones, and [fromLists] (your list
     * rows), minus the ones you've stopped following.
     */
    fun shows(infos: List<LibraryItems.Info>, record: JSONObject?, fromLists: List<Pair<String, String>> = emptyList()): List<Pair<String, String>> {
        val c = read(record)
        val auto = infos.filter { started(it) }.sortedByDescending { it.updated }.map { it.id to it.name }
        return (auto + c.follow.map { it.key to it.value } + fromLists).distinctBy { it.first }.filter { it.first !in c.unfollow }
    }

    fun isFollowed(id: String, infos: List<LibraryItems.Info>, record: JSONObject?): Boolean {
        val c = read(record)
        if (id in c.unfollow) return false
        return id in c.follow || infos.any { it.id == id && started(it) }
    }

    /** Where a followed show belongs, given its episodes and the ones you've watched. */
    fun section(show: Full, watched: Set<String>, info: LibraryItems.Info?): Section {
        val out = show.videos.filter { it.season > 0 && it.released.length == 10 && it.released <= Releases.day() }
        val inProgress = info != null && info.offset > 0
        val seen = out.count { it.id in watched }
        // An ended show with nothing more coming has nothing to wait for (it stays in Continue watching).
        if (show.status.equals("Ended", true) && Releases.upcoming(show, 3650).isEmpty()) return Section.FINISHED
        return when {
            seen == 0 && !inProgress -> Section.NOT_STARTED
            out.isNotEmpty() && out.all { it.id in watched } -> Section.CAUGHT_UP
            else -> Section.WATCHING
        }
    }

    /**
     * Where you're up to in a show: "on S01E02 · 33 more" while an episode is in progress
     * (from Continue watching), otherwise "next S01E03 · 32 more".
     */
    fun progressText(show: Full, watched: Set<String>, info: LibraryItems.Info? = null): String {
        val out = show.videos.filter { it.season > 0 && it.released.length == 10 && it.released <= Releases.day() }
            .sortedWith(compareBy({ it.season }, { it.episode }))
        val current = info?.takeIf { it.continuing }?.let { i -> out.indexOfFirst { it.id == i.videoId } }?.takeIf { it >= 0 }
        if (current != null) {
            val after = out.size - current - 1
            val verb = if (info.upNext) "next" else "on"
            return "$verb ${MetaParse.epLabel(out[current])}" + if (after > 0) " · $after more" else ""
        }
        val left = out.filter { it.id !in watched }
        val next = left.firstOrNull() ?: return "up to date"
        return "next ${MetaParse.epLabel(next)}" + if (left.size > 1) " · ${left.size - 1} more" else ""
    }
}
