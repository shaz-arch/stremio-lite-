package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Live TV: a hand-picked list of channels that broadcasters stream free and openly themselves
 * (news, S4C, shopping), plus playlists (M3U links) people add for themselves.
 *
 * The list lives in the repo (live/channels.json) so a moved link can be fixed without a new
 * build; the copy bundled with the app is used when GitHub can't be reached.
 */
object LiveTv {
    const val LIST_URL = "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/live/channels.json"

    class Channel(val name: String, val group: String, val urls: List<String>, val note: String = "")

    class Group(val name: String, val channels: List<Channel>)

    // ---- the curated list ---------------------------------------------------------------

    fun bundled(): String =
        LiveTv::class.java.getResourceAsStream("/stremonino-live.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    fun parseList(json: String): List<Group> {
        val groups = JSONObject(json).optJSONArray("groups") ?: return emptyList()
        return (0 until groups.length()).mapNotNull { i ->
            val g = groups.optJSONObject(i) ?: return@mapNotNull null
            val name = g.optString("name")
            val chs = g.optJSONArray("channels") ?: JSONArray()
            val list = (0 until chs.length()).mapNotNull { j ->
                val c = chs.optJSONObject(j) ?: return@mapNotNull null
                val urls = c.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
                if (urls.isEmpty()) null else Channel(c.optString("name"), name, urls, c.optString("note"))
            }
            if (list.isEmpty()) null else Group(name, list)
        }
    }

    @Volatile private var cached: List<Group>? = null

    /** The latest list from GitHub, or the bundled one. Blocking. */
    fun load(): List<Group> {
        cached?.let { return it }
        val fresh = runCatching { parseList(Http.get(LIST_URL, 8_000)) }.getOrNull()?.takeIf { it.isNotEmpty() }
        return (fresh ?: parseList(bundled())).also { cached = it }
    }

    fun forget() { cached = null; working.clear(); loaded.clear() }

    // ---- is it on air? -----------------------------------------------------------------

    /** Channel name → (checked at, the link that works or "" if none). Kept for ten minutes. */
    private val working = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val KEEP_MS = 10 * 60_000L

    private fun key(c: Channel) = c.name + "|" + c.urls.joinToString("|")

    /** The saved result for [c]: the working link, "" for off air, or null if not checked lately. */
    fun known(c: Channel): String? = working[key(c)]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second

    /** Tries each link in turn and returns the first that answers with a playlist, or null. Blocking. */
    fun check(c: Channel, timeoutMs: Int = 6_000): String? {
        known(c)?.let { return it.ifEmpty { null } }
        val ok = c.urls.firstOrNull { alive(it, timeoutMs) }
        working[key(c)] = System.currentTimeMillis() to (ok ?: "")
        return ok
    }

    /** True if [url] answers with an HLS or DASH playlist (reads only the first bit). */
    fun alive(url: String, timeoutMs: Int = 6_000): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", Http.userAgent)
            if (c.responseCode >= 400) return@runCatching false
            val head = c.inputStream.use { s ->
                val buf = ByteArray(1024)
                var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                String(buf, 0, n, Charsets.UTF_8)
            }
            head.contains("#EXTM3U") || head.contains("<MPD")
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    // ---- your playlists (M3U links) --------------------------------------------------------

    class Playlist(val name: String, val url: String)

    fun playlists(json: String?): List<Playlist> {
        if (json.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null else Playlist(o.optString("name").ifBlank { nameFor(url) }, url)
        }
    }

    fun playlistsJson(list: List<Playlist>): String =
        JSONArray(list.map { JSONObject().put("name", it.name).put("url", it.url) }).toString()

    /** A short name for a playlist link: its site, e.g. "example.com". */
    fun nameFor(url: String): String =
        runCatching { URL(url).host.removePrefix("www.") }.getOrNull()?.ifBlank { null } ?: "Playlist"

    /** A link people paste: trimmed, with https:// added when it's missing. Null if it isn't a link. */
    fun cleanLink(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        return runCatching { URL(withScheme); withScheme }.getOrNull()
    }

    /**
     * Reads an M3U playlist: each #EXTINF line names the channel (group-title gives its group),
     * and the next link line is its stream. Groups keep the playlist's order.
     */
    fun parseM3u(text: String, fallbackGroup: String = "Channels"): List<Group> {
        val order = LinkedHashMap<String, MutableList<Channel>>()
        var name: String? = null
        var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = line.substringAfterLast(',', "").trim().ifEmpty { attr(line, "tvg-name") }
                    group = attr(line, "group-title")
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val g = group?.ifBlank { null } ?: fallbackGroup
                    val n = name?.ifBlank { null } ?: line.substringAfterLast('/').take(40)
                    order.getOrPut(g) { mutableListOf() } += Channel(n, g, listOf(line))
                    name = null
                    group = null
                }
            }
        }
        return order.map { (g, list) -> Group(g, list) }
    }

    private fun attr(line: String, key: String): String =
        Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim() ?: ""

    /** Playlists read lately (link → read at, groups), so search doesn't fetch them every time. */
    private val loaded = ConcurrentHashMap<String, Pair<Long, List<Group>>>()

    /** Fetches and reads a playlist. Blocking. Big playlists take a while on the stick, so allow time. */
    fun loadPlaylist(p: Playlist, fresh: Boolean = true): List<Group> {
        if (!fresh) loaded[p.url]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second }
        val text = Http.getBytes(p.url, 30_000).toString(Charsets.UTF_8)
        if (!text.contains("#EXTINF", ignoreCase = true)) throw IllegalStateException("That link isn't an M3U playlist")
        return parseM3u(text).also { loaded[p.url] = System.currentTimeMillis() to it }
    }

    // ---- favourites --------------------------------------------------------------------------

    /** Your favourite channels, from any list: [Channel.group] holds where it came from. */
    fun favourites(json: String?): List<Channel> {
        if (json.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val urls = o.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            if (urls.isEmpty()) null else Channel(o.optString("name"), o.optString("from"), urls, o.optString("note"))
        }
    }

    fun favouritesJson(list: List<Channel>): String = JSONArray(list.map { c ->
        JSONObject().put("name", c.name).put("from", c.group).put("urls", JSONArray(c.urls)).also { if (c.note.isNotEmpty()) it.put("note", c.note) }
    }).toString()

    /** The same channel (same name and first link), wherever it's listed. */
    fun same(a: Channel, b: Channel) = a.name == b.name && a.urls.firstOrNull() == b.urls.firstOrNull()

    fun isFavourite(favs: List<Channel>, c: Channel) = favs.any { same(it, c) }

    /** [favs] with [c] added (at the end) or taken off. [from] names the list it came from. */
    fun toggle(favs: List<Channel>, c: Channel, from: String): List<Channel> =
        if (isFavourite(favs, c)) favs.filterNot { same(it, c) } else favs + Channel(c.name, from, c.urls, c.note)

    // ---- search --------------------------------------------------------------------------------

    private fun simple(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Channels whose name matches [query]: names starting with it first. */
    fun matching(channels: List<Channel>, query: String): List<Channel> {
        val q = simple(query)
        if (q.isEmpty()) return emptyList()
        val hits = channels.filter { simple(it.name).contains(q) }
        val seen = HashSet<String>()
        return hits.sortedBy { if (simple(it.name).startsWith(q)) 0 else 1 }.filter { seen.add(it.name + "|" + it.urls.first()) }
    }

    /**
     * Every channel Live TV knows: your favourites, the broadcasters' list and your playlists
     * (each channel's group says where it's from). Blocking; a playlist that fails is skipped.
     */
    fun everything(favs: List<Channel>, playlists: List<Playlist>): List<Channel> {
        val out = ArrayList<Channel>(favs)
        runCatching { load() }.getOrNull()?.forEach { g -> g.channels.forEach { out += Channel(it.name, "Live TV · " + g.name, it.urls, it.note) } }
        for (p in playlists) {
            val groups = runCatching { loadPlaylist(p, fresh = false) }.getOrNull() ?: continue
            groups.forEach { g -> g.channels.forEach { out += Channel(it.name, p.name + " · " + g.name, it.urls) } }
        }
        return out
    }

    /** "Checked 40 of 120 · 35 live" while a list is being checked, or "" once it's done. */
    fun progress(channels: List<Channel>): String {
        val done = channels.mapNotNull { known(it) }
        if (done.size >= channels.size) return ""
        return "Checking ${done.size} of ${channels.size} · ${done.count { it.isNotEmpty() }} live"
    }
}
