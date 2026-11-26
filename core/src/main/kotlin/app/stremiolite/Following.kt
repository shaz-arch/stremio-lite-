package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which shows you follow for new episodes and seasons, kept apart from the Library:
 *  - shows you've started watching, only if you've turned that on (off by default);
 *  - shows you follow on purpose (the "Tell me about new episodes and seasons" button);
 *  - your own list rows (e.g. the MDBList Watchlist);
 *  minus anything you've stopped following. Stored on the account as one hidden record.
 */
object Following {
    const val ID = "stremiolite:following"

    /** A film followed until it's out to stream; [note] is where and when ("Netflix 23 Dec"). */
    class Film(val id: String, val name: String, val year: String, val note: String = "")

    /** How a show is followed: every new episode, or only a new season starting. */
    const val EPISODES = "episodes"
    const val SEASONS = "seasons"

    /**
     * [modes] and [since] are for shows followed on purpose: how, and when you started following
     * (episodes out before then aren't news).
     */
    class Choices(
        val follow: Map<String, String>, val unfollow: Set<String>, val films: List<Film> = emptyList(),
        val modes: Map<String, String> = emptyMap(), val since: Map<String, Long> = emptyMap(),
    )

    enum class Section { CAUGHT_UP, WATCHING, NOT_STARTED, FINISHED }

    fun read(record: JSONObject?): Choices {
        val o = record?.optString("name")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Choices(emptyMap(), emptySet())
        val films = o.optJSONArray("films")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Film(it.optString("id"), it.optString("name"), it.optString("year"), it.optString("note")) }
        }.orEmpty().filter { it.id.isNotEmpty() }
        val follow = LinkedHashMap<String, String>()
        val modes = HashMap<String, String>(); val since = HashMap<String, Long>()
        o.optJSONArray("follow")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                val id = it.optString("id")
                follow[id] = it.optString("name")
                it.optString("mode").takeIf { m -> m == EPISODES || m == SEASONS }?.let { m -> modes[id] = m }
                it.optLong("since").takeIf { t -> t > 0 }?.let { t -> since[id] = t }
            }
        }
        val unfollow = o.optJSONArray("unfollow")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        return Choices(follow, unfollow, films, modes, since)
    }

    private fun write(record: JSONObject?, c: Choices): JSONObject {
        val o = JSONObject()
            .put("follow", JSONArray(c.follow.map { (id, name) ->
                JSONObject().put("id", id).put("name", name).apply {
                    c.modes[id]?.let { put("mode", it) }
                    c.since[id]?.let { put("since", it) }
                }
            }))
            .put("unfollow", JSONArray(c.unfollow.toList()))
            .put("films", JSONArray(c.films.map { JSONObject().put("id", it.id).put("name", it.name).put("year", it.year).put("note", it.note) }))
        val item = record ?: Sync.newItem(ID, "other", "{}")
        return item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** The record after following [id] on purpose, for [mode] (and no longer "stopped"). */
    fun follow(record: JSONObject?, id: String, name: String, mode: String = EPISODES, now: Long = System.currentTimeMillis()): JSONObject {
        val c = read(record)
        return write(record, Choices(c.follow + (id to name), c.unfollow - id, c.films, c.modes + (id to mode), c.since + (id to (c.since[id] ?: now))))
    }

    /**
     * How [id] is followed. Shows you chose before modes existed: every episode. Ones followed
     * because they're favourites or in a list row: new seasons.
     */
    fun mode(record: JSONObject?, id: String): String {
        val c = read(record)
        return c.modes[id] ?: if (id in c.follow) EPISODES else SEASONS
    }

    /** When you started following [id] on purpose (0 = not known). */
    fun since(record: JSONObject?, id: String): Long = read(record).since[id] ?: 0L

    /** The record after stopping following [id] (it stays stopped even if you watch more). */
    fun unfollow(record: JSONObject?, id: String): JSONObject {
        val c = read(record)
        return write(record, Choices(c.follow - id, c.unfollow + id, c.films, c.modes - id, c.since - id))
    }

    // ---- films not out to stream yet ---------------------------------------------------------

    fun films(record: JSONObject?): List<Film> = read(record).films

    /** The record with [films] followed (new ones added, notes updated). */
    fun followFilms(record: JSONObject?, films: List<Film>): JSONObject {
        val c = read(record)
        val by = LinkedHashMap<String, Film>(); c.films.forEach { by[it.id] = it }
        films.forEach { f -> by[f.id] = if (f.note.isBlank()) (by[f.id]?.let { Film(f.id, f.name, f.year, it.note) } ?: f) else f }
        return write(record, Choices(c.follow, c.unfollow, by.values.toList(), c.modes, c.since))
    }

    fun unfollowFilms(record: JSONObject?, ids: Collection<String>): JSONObject {
        val c = read(record)
        return write(record, Choices(c.follow, c.unfollow, c.films.filter { it.id !in ids }, c.modes, c.since))
    }

    /** Your favourite shows are followed by themselves (unless you've stopped them). */
    fun favouriteShows(infos: List<LibraryItems.Info>) = infos.filter { it.type == "series" && it.inLibrary }

    /** A show counts as started once any episode has been watched or played. */
    fun started(i: LibraryItems.Info) = i.type == "series" &&
        (i.watchedField.isNotEmpty() || i.offset > 0 || i.timesWatched > 0 || (i.videoId != i.id && i.videoId.isNotEmpty()))

    /**
     * Every followed show (id to name): started shows, chosen ones, and [fromLists] (your list
     * rows), minus the ones you've stopped following.
     */
    fun shows(infos: List<LibraryItems.Info>, record: JSONObject?, fromLists: List<Pair<String, String>> = emptyList(), autoStarted: Boolean = false): List<Pair<String, String>> {
        val c = read(record)
        val auto = if (!autoStarted) emptyList() else infos.filter { started(it) }.sortedByDescending { it.updated }.map { it.id to it.name }
        val favs = favouriteShows(infos).sortedByDescending { it.updated }.map { it.id to it.name }
        return (auto + favs + c.follow.map { it.key to it.value } + fromLists).distinctBy { it.first }.filter { it.first !in c.unfollow }
    }

    /** Shows you've stopped following (kept stopped even if they're in a list row). */
    fun stopped(record: JSONObject?): Set<String> = read(record).unfollow

    /** Ended, with nothing more announced. */
    fun ended(show: Full) = show.status.equals("Ended", true) && Releases.upcoming(show, 3650).isEmpty()

    /** The right-hand text for a show that's waiting: its next episode and day, or that it's all out. */
    fun nextText(show: Full): String {
        Releases.upcoming(show, 3650).firstOrNull()?.let { return "${MetaParse.epLabel(it)} ${Releases.dayName(it.released)}" }
        if (ended(show)) return "ended · all out"
        return "no date yet"
    }

    fun isFollowed(id: String, infos: List<LibraryItems.Info>, record: JSONObject?, autoStarted: Boolean = false): Boolean {
        val c = read(record)
        if (id in c.unfollow) return false
        return id in c.follow || c.films.any { it.id == id } || favouriteShows(infos).any { it.id == id } || (autoStarted && infos.any { it.id == id && started(it) })
    }

    /** Where a followed show belongs, given its episodes and the ones you've watched. */
    fun section(show: Full, watched: Set<String>, info: LibraryItems.Info?): Section {
        val out = show.videos.filter { it.season > 0 && it.released.length == 10 && it.released <= Releases.day() }
        val inProgress = info != null && info.offset > 0
        val seen = out.count { it.id in watched }
        // Only an ended show you've watched to the end drops off. One you haven't started, or are
        // part-way through, stays: there's still something to watch.
        if (ended(show) && out.isNotEmpty() && seen == out.size) return Section.FINISHED
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
