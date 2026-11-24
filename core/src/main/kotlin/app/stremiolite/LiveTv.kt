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

    /** [tvgId] is the playlist's tvg-id (matches the guide); [guide] is the playlist's own TV guide link. */
    class Channel(
        val name: String,
        val group: String,
        val urls: List<String>,
        val note: String = "",
        val tvgId: String = "",
        val guide: String = "",
    ) {
        /** The same channel listed under [from] (a favourite, a search result). */
        fun from(from: String) = Channel(name, from, urls, note, tvgId, guide)
    }

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

    /** [added] is when it was added (0 if not known: added before this was kept). */
    /** [hidden]: groups ("g:News") and channels ([channelKey]) not shown. They stay in the playlist. */
    class Playlist(val name: String, val url: String, val added: Long = 0, val hidden: Set<String> = emptySet()) {
        fun renamed(to: String) = Playlist(to, url, added, hidden)
        fun withHidden(h: Set<String>) = Playlist(name, url, added, h)

        /** [key] hidden if it was shown, shown if it was hidden. */
        fun toggled(key: String) = withHidden(if (key in hidden) hidden - key else hidden + key)
    }

    fun groupKey(g: Group) = "g:" + g.name
    fun channelKey(c: Channel) = "c:" + c.name + "|" + c.urls.first()

    /** [groups] without what [p] hides (and without groups left empty). */
    fun visible(p: Playlist, groups: List<Group>): List<Group> {
        if (p.hidden.isEmpty()) return groups
        return groups.filter { groupKey(it) !in p.hidden }
            .map { g -> Group(g.name, g.channels.filter { channelKey(it) !in p.hidden }) }
            .filter { it.channels.isNotEmpty() }
    }

    /** Radio stations in a playlist (by name or group), for "Hide radio stations". */
    fun radio(groups: List<Group>): List<Channel> {
        val re = Regex("""\bradio\b""", RegexOption.IGNORE_CASE)
        return groups.flatMap { g -> g.channels.filter { re.containsMatchIn(it.name) || re.containsMatchIn(g.name) } }
    }

    /** Recently played channels: [c] first, then the others (up to five). */
    fun played(recent: List<Channel>, c: Channel): List<Channel> = (listOf(c) + recent.filterNot { same(it, c) }).take(5)

    fun playlists(json: String?): List<Playlist> {
        if (json.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null else Playlist(
                o.optString("name").ifBlank { nameFor(url) }, url, o.optLong("added"),
                o.optJSONArray("hide")?.let { h -> (0 until h.length()).map { h.optString(it) }.toSet() } ?: emptySet(),
            )
        }
    }

    fun playlistsJson(list: List<Playlist>): String =
        JSONArray(list.map { p ->
            JSONObject().put("name", p.name).put("url", p.url).also {
                if (p.added > 0) it.put("added", p.added)
                if (p.hidden.isNotEmpty()) it.put("hide", JSONArray(p.hidden.toList()))
            }
        }).toString()

    /** A short name for a playlist link: its site, e.g. "example.com". */
    fun nameFor(url: String): String {
        val u = runCatching { URL(url) }.getOrNull() ?: return "Playlist"
        val host = u.host.removePrefix("www.")
        // A playlist kept on GitHub: "Free-TV playlist_uk" says more than "raw.githubusercontent.com".
        if (host.endsWith("githubusercontent.com") || host == "github.com" || host.endsWith("github.io")) {
            val parts = u.path.split('/').filter { it.isNotEmpty() }
            val file = parts.lastOrNull()?.substringBeforeLast('.')?.ifBlank { null }
            val owner = if (host.endsWith("github.io")) host.substringBefore('.') else parts.firstOrNull()
            return listOfNotNull(owner, file).joinToString(" ").ifBlank { host }
        }
        return host.ifBlank { "Playlist" }
    }

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
    fun parseM3u(text: String, fallbackGroup: String = "Channels", guideUrl: String = guideLink(text)): List<Group> {
        val order = LinkedHashMap<String, MutableList<Channel>>()
        var name: String? = null
        var group: String? = null
        var tvg = ""
        val guide = guideUrl
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    name = line.substringAfterLast(',', "").trim().ifEmpty { attr(line, "tvg-name") }
                    group = attr(line, "group-title")
                    tvg = attr(line, "tvg-id")
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val g = group?.ifBlank { null } ?: fallbackGroup
                    val n = name?.ifBlank { null } ?: line.substringAfterLast('/').take(40)
                    order.getOrPut(g) { mutableListOf() } += Channel(n, g, listOf(line), "", tvg, guide)
                    name = null
                    group = null
                    tvg = ""
                }
            }
        }
        return order.map { (g, list) -> Group(g, list) }
    }

    /**
     * The TV guide links in a playlist's #EXTM3U line (url-tvg or x-tvg-url), comma-separated.
     * Some playlists list one guide per country; [Guide.guidesFor] picks the ones each channel needs.
     */
    fun guideLink(text: String): String {
        val head = text.lineSequence().take(5).firstOrNull { it.trim().startsWith("#EXTM3U", ignoreCase = true) } ?: return ""
        return attr(head, "url-tvg").ifEmpty { attr(head, "x-tvg-url") }.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
    }

    /**
     * Many IPTV services give the playlist as …/get.php?username=…&password=… and the guide beside
     * it as …/xmltv.php with the same login. Returns that guide link, or "" for other playlists.
     */
    fun providerGuide(url: String): String {
        val u = runCatching { URL(url) }.getOrNull() ?: return ""
        if (!u.path.endsWith("/get.php")) return ""
        val q = (u.query ?: "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val user = q["username"] ?: return ""
        val pass = q["password"] ?: return ""
        return "${u.protocol}://${u.authority}${u.path.removeSuffix("get.php")}xmltv.php?username=$user&password=$pass"
    }

    private fun attr(line: String, key: String): String =
        Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim() ?: ""

    /** A playlist as last read: its groups, plus what "About this playlist" shows. */
    class Loaded(val at: Long, val groups: List<Group>, val bytes: Int, val guide: String)

    /** Playlists read lately (by link), so search and About don't fetch them every time. */
    private val loaded = ConcurrentHashMap<String, Loaded>()

    /** Fetches and reads a playlist. Blocking. Big playlists take a while on the stick, so allow time. */
    fun loadPlaylist(p: Playlist, fresh: Boolean = true): List<Group> = read(p, fresh).groups

    /** Like [loadPlaylist], with the details too. */
    fun read(p: Playlist, fresh: Boolean = true): Loaded {
        if (!fresh) loaded[p.url]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.let { return it }
        val bytes = Http.getBytes(p.url, 30_000)
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.contains("#EXTINF", ignoreCase = true)) throw IllegalStateException("That link isn't an M3U playlist")
        val guide = guideLink(text).ifEmpty { providerGuide(p.url) }
        return Loaded(System.currentTimeMillis(), parseM3u(text, guideUrl = guide), bytes.size, guide).also { loaded[p.url] = it }
    }

    /**
     * What "About this playlist" shows, as label/value lines: channels and groups, how many are
     * on air, favourites from it, quality, guide, size, and what kind of link it is.
     */
    fun about(p: Playlist, l: Loaded, favs: List<Channel>, day: (Long) -> String): List<Pair<String, String>> {
        val all = l.groups.flatMap { it.channels }
        val out = mutableListOf<Pair<String, String>>()
        out += "Name" to p.name
        if (p.added > 0) out += "Added" to day(p.added)
        out += "Channels" to "${all.size} in ${l.groups.size} group${if (l.groups.size == 1) "" else "s"}"
        if (l.groups.size > 1) out += "Biggest groups" to l.groups.sortedByDescending { it.channels.size }.take(3).joinToString(", ") { "${it.name} ${it.channels.size}" }
        if (p.hidden.isNotEmpty()) {
            val hg = l.groups.count { groupKey(it) in p.hidden }
            val hc = all.count { channelKey(it) in p.hidden }
            out += "Hidden" to listOfNotNull(if (hg > 0) "$hg group${if (hg == 1) "" else "s"}" else null, if (hc > 0) "$hc channel${if (hc == 1) "" else "s"}" else null).joinToString(", ")
        }
        val checked = all.mapNotNull { known(it) }
        out += "On air" to if (checked.isEmpty()) "not checked yet (open a group)" else
            "${checked.count { it.isNotEmpty() }} live, ${checked.count { it.isEmpty() }} off air (of ${checked.size} checked)"
        val mine = favs.count { f -> all.any { same(it, f) } }
        out += "In favourites" to "$mine"
        val names = all.map { it.name.uppercase() }
        val uhdRe = Regex("""\b(4K|UHD)\b""")
        val hdRe = Regex("""\b(F?HD|1080P?|720P?)\b""")
        val uhd = names.count { uhdRe.containsMatchIn(it) }
        val hd = names.count { hdRe.containsMatchIn(it) }
        if (hd + uhd > 0) out += "Quality in names" to listOfNotNull(if (hd > 0) "$hd HD" else null, if (uhd > 0) "$uhd 4K" else null).joinToString(", ")
        val seen = HashSet<String>()
        val repeats = all.count { !seen.add(it.urls.first()) }
        if (repeats > 0) out += "Listed twice" to "$repeats (same link)"
        val links = l.guide.split(',').filter { it.isNotBlank() }
        val needed = all.flatMap { Guide.guidesFor(it, "") }.distinct()
        val matched = if (links.isEmpty()) 0 else all.count { Guide.lookup(it, Guide.guidesFor(it, "")).isNotEmpty() }
        val kind = (if (l.guide == providerGuide(p.url)) "from your provider" else "included") +
            if (links.size > 1) " (${links.size} guides, ${needed.size} used)" else ""
        out += "TV guide" to when {
            links.isEmpty() -> "none: add your own in Live TV"
            needed.none { Guide.get(it) != null } -> "$kind, not read yet (open a group)"
            else -> "$kind: $matched of ${all.size} channels listed"
        }
        needed.mapNotNull { u -> Guide.error(u)?.let { u to it } }.forEach { (u, e) ->
            out += "Guide problem" to "${u.substringAfter("://").take(40)}: $e"
        }
        out += "Size" to if (l.bytes >= 1_000_000) "%.1f MB".format(l.bytes / 1_000_000.0) else "${(l.bytes + 999) / 1000} KB"
        out += "Read" to day(l.at)
        val u = runCatching { URL(p.url) }.getOrNull()
        out += "Server" to (u?.host ?: "?")
        out += "Connection" to if (p.url.startsWith("https://")) "encrypted (https)" else "not encrypted (http)"
        if (hasLogin(p.url)) out += "Login in link" to "yes: keep this link private"
        return out
    }

    /** True if the link carries a username, password or token (an IPTV login). */
    fun hasLogin(url: String) = Regex("""[?&](username|password|user|pass|token|key)=""", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
        Regex("""//[^/@]+:[^/@]+@""").containsMatchIn(url)

    // ---- favourites --------------------------------------------------------------------------

    /** Your favourite channels, from any list: [Channel.group] holds where it came from. */
    fun favourites(json: String?): List<Channel> {
        if (json.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val urls = o.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
            if (urls.isEmpty()) null else Channel(o.optString("name"), o.optString("from"), urls, o.optString("note"), o.optString("tvg"), o.optString("guide"))
        }
    }

    fun favouritesJson(list: List<Channel>): String = JSONArray(list.map { c ->
        JSONObject().put("name", c.name).put("from", c.group).put("urls", JSONArray(c.urls)).also {
            if (c.note.isNotEmpty()) it.put("note", c.note)
            if (c.tvgId.isNotEmpty()) it.put("tvg", c.tvgId)
            if (c.guide.isNotEmpty()) it.put("guide", c.guide)
        }
    }).toString()

    /** The same channel (same name and first link), wherever it's listed. */
    fun same(a: Channel, b: Channel) = a.name == b.name && a.urls.firstOrNull() == b.urls.firstOrNull()

    fun isFavourite(favs: List<Channel>, c: Channel) = favs.any { same(it, c) }

    /** [favs] with [c] added (at the end) or taken off. [from] names the list it came from. */
    fun toggle(favs: List<Channel>, c: Channel, from: String): List<Channel> =
        if (isFavourite(favs, c)) favs.filterNot { same(it, c) } else favs + c.from(from)

    /**
     * Favourites saved from a playlist keep its guide link and channel id as they were then. After
     * the playlist loads, this brings them up to date (null if nothing changed). It fixes favourites
     * saved before build 124, which kept only a playlist's first guide (Albania's, for Free-TV).
     */
    fun refreshFavourites(favs: List<Channel>, groups: List<Group>): List<Channel>? {
        val all = groups.flatMap { it.channels }
        var changed = false
        val out = favs.map { f ->
            val now = all.firstOrNull { same(it, f) } ?: return@map f
            if (now.guide == f.guide && now.tvgId == f.tvgId) f
            else { changed = true; Channel(f.name, f.group, f.urls, f.note, now.tvgId, now.guide) }
        }
        return if (changed) out else null
    }

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
    fun everything(favs: List<Channel>, playlists: List<Playlist>, freeHidden: Set<String> = emptySet()): List<Channel> {
        val out = ArrayList<Channel>(favs)
        runCatching { visible(Playlist("", "", 0, freeHidden), load()) }.getOrNull()
            ?.forEach { g -> g.channels.forEach { out += it.from("Free channels · " + g.name) } }
        for (p in playlists) {
            val groups = runCatching { visible(p, loadPlaylist(p, fresh = false)) }.getOrNull() ?: continue
            groups.forEach { g -> g.channels.forEach { out += it.from(p.name + " · " + g.name) } }
        }
        return out
    }

    /** "Checked 40 of 120 · 35 live" while a list is being checked, or "" once it's done. */
    fun progress(channels: List<Channel>): String {
        val done = channels.mapNotNull { known(it) }
        if (done.size >= channels.size) return ""
        return "Checking ${done.size} of ${channels.size} · ${done.count { it.isNotEmpty() }} live"
    }

    // ---- copying Live TV (to another profile, or in a shared setup) ---------------------------

    /** Someone's Live TV: their playlists, favourites and TV guide link. */
    class Bundle(val playlists: List<Playlist>, val favourites: List<Channel>, val guide: String) {
        val isEmpty get() = playlists.isEmpty() && favourites.isEmpty() && guide.isEmpty()

        /** "2 playlists, 5 favourites, a TV guide link" */
        fun summary(): String = listOfNotNull(
            playlists.size.takeIf { it > 0 }?.let { "$it playlist${if (it == 1) "" else "s"}" },
            favourites.size.takeIf { it > 0 }?.let { "$it favourite${if (it == 1) "" else "s"}" },
            if (guide.isNotEmpty()) "a TV guide link" else null,
        ).joinToString(", ").ifEmpty { "nothing yet" }

        /** True if any link carries a login, so whoever gets it can use that account. */
        fun hasLogins() = playlists.any { hasLogin(it.url) } || hasLogin(guide)

        fun json(): JSONObject = JSONObject().put("p", playlistsJson(playlists)).put("f", favouritesJson(favourites)).put("g", guide)

        /** These added to [mine]: playlists and favourites it doesn't have yet; its guide link stays if it has one. */
        fun into(mine: Bundle) = Bundle(
            mine.playlists + playlists.filter { t -> mine.playlists.none { it.url == t.url } },
            mine.favourites + favourites.filter { t -> !isFavourite(mine.favourites, t) },
            mine.guide.ifEmpty { guide },
        )
    }

    fun bundle(o: JSONObject?): Bundle? {
        o ?: return null
        return Bundle(playlists(o.optString("p")), favourites(o.optString("f")), o.optString("g")).takeIf { !it.isEmpty }
    }
}
