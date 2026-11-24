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

    fun forget() { cached = null; working.clear() }

    // ---- is it on air? -----------------------------------------------------------------

    /** Channel name → (checked at, the link that works or "" if none). Kept for ten minutes. */
    private val working = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val KEEP_MS = 10 * 60_000L

    /** The saved result for [c]: the working link, "" for off air, or null if not checked lately. */
    fun known(c: Channel): String? = working[c.group + "/" + c.name]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.second

    /** Tries each link in turn and returns the first that answers with a playlist, or null. Blocking. */
    fun check(c: Channel): String? {
        known(c)?.let { return it.ifEmpty { null } }
        val ok = c.urls.firstOrNull { alive(it) }
        working[c.group + "/" + c.name] = System.currentTimeMillis() to (ok ?: "")
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

    /** Fetches and reads a playlist. Blocking. Big playlists take a while on the stick, so allow time. */
    fun loadPlaylist(p: Playlist): List<Group> {
        val text = Http.getBytes(p.url, 30_000).toString(Charsets.UTF_8)
        if (!text.contains("#EXTINF", ignoreCase = true)) throw IllegalStateException("That link isn't an M3U playlist")
        return parseM3u(text)
    }
}
