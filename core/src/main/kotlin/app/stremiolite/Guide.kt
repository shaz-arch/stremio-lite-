package app.stremiolite

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * TV guides (XMLTV, plain or .gz) for Live TV: what's on now, next and later.
 *
 * Guides can be huge (every channel in a country), so a guide is read as a stream and only the
 * next [AHEAD_MS] of programmes is kept, with descriptions trimmed: small enough for the stick.
 */
object Guide {
    class Prog(val start: Long, val stop: Long, val title: String, val desc: String)

    /** [progs] by the guide's channel id; [names] maps simplified channel names (and ids) to ids. */
    /** [covered]: the channel keys this was read for (see [keys]); null means every channel in the guide. */
    class Data(val at: Long, val progs: Map<String, List<Prog>>, val names: Map<String, String>, val covered: Set<String>?)

    /**
     * What identifies [c] in a guide: its tvg-id, and its simplified name (and id) prefixed "n:".
     * Reading a guide for just these keeps it small: a country's guide covers ~1,000 channels.
     */
    fun keys(c: LiveTv.Channel): Set<String> = setOfNotNull(
        c.tvgId.takeIf { it.isNotEmpty() },
        c.tvgId.takeIf { it.isNotEmpty() }?.let { "n:" + simple(it) },
        "n:" + simple(c.name),
    )

    /** For [channels]: each guide link they use, with the keys to read it for (adding to what's read already). */
    fun needs(channels: List<LiveTv.Channel>, yours: String): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        for (c in channels) for (u in guidesFor(c, yours)) out.getOrPut(u) { HashSet() } += keys(c)
        return out.filter { (u, k) -> isDue(u, k) }.mapValues { (u, k) -> k + (cache[u]?.covered ?: emptySet()) }
    }

    private fun isDue(u: String, k: Set<String>): Boolean {
        if (u.isBlank() || loading.containsKey(u) || failed.containsKey(u)) return false
        val d = cache[u] ?: return true
        return System.currentTimeMillis() - d.at > KEEP_MS || (d.covered != null && !d.covered.containsAll(k))
    }

    private const val AHEAD_MS = 14 * 3600_000L
    private const val KEEP_MS = 3 * 3600_000L
    private const val DESC_MAX = 240

    private val cache = ConcurrentHashMap<String, Data>()
    private val loading = ConcurrentHashMap<String, Boolean>()
    private val failed = ConcurrentHashMap<String, String>()

    /** The guide if it has been read (even if due a refresh). */
    fun get(url: String): Data? = cache[url]

    /** Why the guide couldn't be read, if it couldn't. */
    fun error(url: String): String? = failed[url]

    fun isLoading(url: String) = loading.containsKey(url)

    /** Of [urls], those that need reading now (never read, or older than three hours, and not being read). */
    fun due(urls: Collection<String>): List<String> = urls.filter { u ->
        u.isNotBlank() && !loading.containsKey(u) && (cache[u]?.let { System.currentTimeMillis() - it.at > KEEP_MS } ?: !failed.containsKey(u))
    }.distinct()

    fun forget(url: String? = null) {
        if (url == null) { cache.clear(); failed.clear() } else { cache.remove(url); failed.remove(url) }
    }

    /** A plain reason a guide couldn't be read. */
    fun why(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is java.net.UnknownHostException -> return "the address ${t.message?.substringAfter('"')?.substringBefore('"')?.ifBlank { null } ?: ""} doesn't exist (or you're offline)".replace("  ", " ")
                is java.net.SocketTimeoutException -> return "the server took too long"
                is java.net.ConnectException -> return "the server refused the connection"
                is javax.net.ssl.SSLException -> return "couldn't make a secure connection"
                is java.util.zip.ZipException -> return "the file isn't a proper .gz guide"
                is Http.HttpError -> return when (t.code) {
                    404, 410 -> "the guide isn't there any more (${t.code})"
                    401, 403 -> "the server won't give it out (${t.code})"
                    else -> "the server said ${t.code}"
                }
                is IllegalStateException -> return t.message ?: "no programmes in it"
            }
            t = t.cause
        }
        return e.message ?: e.javaClass.simpleName
    }

    /** Downloads and reads a guide. Blocking: a big guide takes a while on the stick. */
    fun load(url: String, wanted: Set<String>? = null): Data {
        loading[url] = true
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", Http.userAgent)
                if (c.responseCode >= 400) throw Http.HttpError(c.responseCode)
                val d = parse(c.inputStream, System.currentTimeMillis(), wanted)
                // Read for some channels only, an empty result just means none of them are in this guide.
                if (d.progs.isEmpty() && wanted == null) throw IllegalStateException("No programmes in that guide")
                cache[url] = d
                failed.remove(url)
                return d
            } finally {
                c.disconnect()
            }
        } catch (e: Throwable) {
            // Recorded so it isn't tried again and again (each try is a big download).
            failed[url] = if (e is OutOfMemoryError) "too big for this device's memory" else why(e)
            throw if (e is Exception) e else RuntimeException(failed[url], e)
        } finally {
            loading.remove(url)
        }
    }

    /** Reads XMLTV from [input] (gzipped or not), keeping programmes that end after [now] and start within [AHEAD_MS]. */
    fun parse(input: InputStream, now: Long, wanted: Set<String>? = null): Data {
        // Guide channel ids to keep (null: all). Channels are listed before programmes in XMLTV.
        val keep: HashSet<String>? = if (wanted == null) null else HashSet()
        fun wantedId(id: String) = keep == null || id in keep || id in wanted!! || ("n:" + simple(id)) in wanted
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val reader = InputStreamReader(if (gz) GZIPInputStream(buffered, 64 * 1024) else buffered, Charsets.UTF_8)
        val progs = HashMap<String, MutableList<Prog>>()
        val names = HashMap<String, String>()
        val sb = StringBuilder()
        val chunk = CharArray(64 * 1024)
        val until = now + AHEAD_MS
        while (true) {
            val n = reader.read(chunk)
            if (n > 0) sb.append(chunk, 0, n)
            var i = 0
            // Where an element starts that isn't complete yet (kept for the next chunk), or -1.
            var waiting = -1
            while (true) {
                val p = sb.indexOf("<programme", i)
                val ch = sb.indexOf("<channel", i)
                val start = if (p < 0) ch else if (ch < 0) p else minOf(p, ch)
                if (start < 0) break
                val isProg = start == p
                val endTag = if (isProg) "</programme>" else "</channel>"
                val openEnd = sb.indexOf(">", start)
                if (openEnd < 0) { waiting = start; break }
                val end = if (!isProg && sb[openEnd - 1] == '/') openEnd + 1 // <channel id="x"/>
                else sb.indexOf(endTag, openEnd).let { if (it < 0) -1 else it + endTag.length }
                if (end < 0) { waiting = start; break }
                val el = sb.substring(start, end)
                if (isProg) {
                    // Cheap check of the channel before reading the whole programme.
                    val id = el.substringAfter("channel=\"", "").substringBefore('"')
                    if (wantedId(id)) programme(el, now, until)?.let { (cid, prog) -> progs.getOrPut(cid) { ArrayList() } += prog }
                } else channel(el, names, wanted, keep)
                i = end
            }
            // Keep an unfinished element, or else just a possible tag start ("<progr…") at the very end.
            val keepFrom = if (waiting >= 0) waiting else maxOf(i, sb.lastIndexOf("<").takeIf { it >= i && sb.length - it < 16 } ?: sb.length)
            sb.delete(0, keepFrom.coerceAtMost(sb.length))
            if (n < 0) break
        }
        progs.values.forEach { it.sortBy { p -> p.start } }
        // (No putIfAbsent: it's missing on Android 5, the 2016 stick.)
        progs.keys.forEach { id -> val k = simple(id); if (!names.containsKey(k)) names[k] = id }
        return Data(System.currentTimeMillis(), progs, names, wanted)
    }

    private val attrRe = Regex("""(\w[\w-]*)="([^"]*)"""")

    private fun attrs(openTag: String): Map<String, String> = attrRe.findAll(openTag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun inner(el: String, tag: String): String? {
        val s = el.indexOf("<$tag")
        if (s < 0) return null
        val o = el.indexOf('>', s)
        if (o < 0 || el[o - 1] == '/') return null
        val e = el.indexOf("</$tag>", o)
        if (e < 0) return null
        return unescape(el.substring(o + 1, e).trim())
    }

    private fun channel(el: String, names: HashMap<String, String>, wanted: Set<String>?, keep: HashSet<String>?) {
        val id = attrs(el.substring(0, el.indexOf('>') + 1))["id"] ?: return
        val found = ArrayList<String>()
        var at = 0
        while (true) {
            val s = el.indexOf("<display-name", at)
            if (s < 0) break
            val o = el.indexOf('>', s)
            val e = el.indexOf("</display-name>", o)
            if (o < 0 || e < 0) break
            found += simple(unescape(el.substring(o + 1, e)))
            at = e
        }
        if (wanted != null && keep != null) {
            val hit = id in wanted || ("n:" + simple(id)) in wanted || found.any { ("n:" + it) in wanted }
            if (!hit) return
            keep += id
        }
        found.forEach { if (!names.containsKey(it)) names[it] = id }
    }

    private fun programme(el: String, now: Long, until: Long): Pair<String, Prog>? {
        val a = attrs(el.substring(0, el.indexOf('>') + 1))
        val start = time(a["start"]) ?: return null
        val stop = time(a["stop"]) ?: (start + 30 * 60_000L)
        if (stop <= now || start >= until) return null
        val id = a["channel"] ?: return null
        val title = inner(el, "title") ?: return null
        val sub = inner(el, "sub-title")?.takeIf { it.isNotBlank() && it != title }
        val desc = listOfNotNull(sub, inner(el, "desc")).joinToString(": ").let { if (it.length > DESC_MAX) it.take(DESC_MAX - 1).trimEnd() + "…" else it }
        return id to Prog(start, stop, title, desc)
    }

    /** "20261124120000 +0000" (the offset is optional) → epoch milliseconds. */
    fun time(s: String?): Long? {
        if (s == null) return null
        val t = s.trim()
        if (t.length < 12 || !t.take(12).all { it.isDigit() }) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(t.substring(0, 4).toInt(), t.substring(4, 6).toInt() - 1, t.substring(6, 8).toInt(),
            t.substring(8, 10).toInt(), t.substring(10, 12).toInt(), t.substring(12, 14.coerceAtMost(t.length)).toIntOrNull() ?: 0)
        var ms = cal.timeInMillis
        val off = Regex("""([+-])(\d{2}):?(\d{2})""").find(t.substring(12))
        if (off != null) {
            val mins = off.groupValues[2].toInt() * 60 + off.groupValues[3].toInt()
            ms -= (if (off.groupValues[1] == "+") 1 else -1) * mins * 60_000L
        }
        return ms
    }

    private fun unescape(s: String): String {
        var t = s.trim()
        if (t.startsWith("<![CDATA[")) t = t.removePrefix("<![CDATA[").removeSuffix("]]>")
        if (!t.contains('&')) return t
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&amp;", "&")
    }

    /** Lower-case letters and digits, without a trailing HD/UK/FHD/4K: "Sky News HD" and "SkyNews.uk" both give "skynews". */
    fun simple(name: String): String {
        var s = name.lowercase().filter { it.isLetterOrDigit() }
        var changed = true
        while (changed) {
            changed = false
            for (suffix in listOf("fhd", "uhd", "hd", "sd", "uk", "4k", "gb")) {
                if (s.length > suffix.length + 2 && s.endsWith(suffix)) { s = s.dropLast(suffix.length); changed = true }
            }
        }
        return s
    }

    /** The programmes for [c] (now first), from the first of [guides] that knows it. */
    fun lookup(c: LiveTv.Channel, guides: List<String>, now: Long = System.currentTimeMillis()): List<Prog> {
        for (u in guides) {
            val d = cache[u] ?: continue
            val id = c.tvgId.takeIf { it.isNotEmpty() && d.progs.containsKey(it) }
                ?: c.tvgId.takeIf { it.isNotEmpty() }?.let { d.names[simple(it)] }
                ?: d.names[simple(c.name)]
                ?: continue
            val list = d.progs[id]?.filter { it.stop > now } ?: continue
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    /** The guides worth trying for [c]: its playlist's own, then your TV guide link. */
    fun guidesFor(c: LiveTv.Channel, yours: String): List<String> = (pick(c) + yours).filter { it.isNotBlank() }.distinct()

    /**
     * Of a playlist's guide links, the ones for this channel. One link: that one. Several (one per
     * country, like "…_UK1.xml.gz"): those for the country in the channel's tvg-id ("BBCOne.uk").
     * Never all of them: dozens of country guides would be far too much for the stick.
     */
    fun pick(c: LiveTv.Channel): List<String> {
        val links = c.guide.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (links.size <= 1) return links
        val cc = c.tvgId.substringAfterLast('.', "").uppercase().takeIf { it.length == 2 } ?: return emptyList()
        val codes = if (cc == "UK" || cc == "GB") listOf("UK", "GB") else listOf(cc)
        fun file(u: String) = u.substringAfterLast('/').substringBefore('?').uppercase()
        // The country's own guide ("…_UK1.xml.gz") first; then one that merely mentions it ("…_RAKUTEN_FR1").
        val exact = links.filter { u -> codes.any { code -> Regex("""(^|[_\-.])$code\d*\.""").containsMatchIn(file(u)) } }
        val loose = links.filter { u -> codes.any { code -> Regex("""(^|[_\-.])$code(\d*|_[A-Z]+\d*)?\.""").containsMatchIn(file(u)) } }
        return (exact.ifEmpty { loose }).take(1)
    }

    /** "13:00" in the device's time. */
    fun clock(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(ms))

    /** What's on now, for list rows: "Pointless" (empty if there's no guide for it). */
    fun nowTitle(c: LiveTv.Channel, guides: List<String>): String =
        lookup(c, guides).firstOrNull()?.takeIf { it.start <= System.currentTimeMillis() }?.title ?: ""
}
