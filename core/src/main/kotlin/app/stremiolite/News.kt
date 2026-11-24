package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * News: film and TV headlines from publishers' own feeds (RSS or Atom). Shows the headline and
 * the summary the publisher puts in its feed, with a link to the full article.
 *
 * The starter list lives in the repo (news/feeds.json) so dead feeds can be fixed without a
 * build; the copy bundled with the app is used when GitHub can't be reached.
 */
object News {
    const val LIST_URL = "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/news/feeds.json"
    private const val KEEP_MS = 15 * 60_000L

    class Feed(val name: String, val url: String)

    class Item(val title: String, val link: String, val summary: String, val at: Long, val source: String) {
        /** Set when the headline names something in your library. */
        var about: String = ""
    }

    // ---- which feeds -----------------------------------------------------------------------------

    fun parseList(json: String): List<Feed> = feeds(JSONObject(json).optJSONArray("feeds")?.toString())

    fun feeds(json: String?): List<Feed> {
        if (json.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isBlank()) null else Feed(o.optString("name").ifBlank { LiveTv.nameFor(url) }, url)
        }
    }

    fun feedsJson(list: List<Feed>): String = JSONArray(list.map { JSONObject().put("name", it.name).put("url", it.url) }).toString()

    @Volatile private var starter: List<Feed>? = null

    /** The starter feeds: the latest list from GitHub, or the bundled one. Blocking. */
    fun starter(): List<Feed> {
        starter?.let { return it }
        val fresh = runCatching { parseList(Http.get(LIST_URL, 8_000)) }.getOrNull()?.takeIf { it.isNotEmpty() }
        val bundled = News::class.java.getResourceAsStream("/stremonino-feeds.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return (fresh ?: parseList(bundled)).also { starter = it }
    }

    // ---- reading feeds ---------------------------------------------------------------------------

    private val read = ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    private val failed = ConcurrentHashMap<String, String>()

    fun error(f: Feed): String? = failed[f.url]
    fun count(f: Feed): Int? = read[f.url]?.second?.size

    /** A feed's stories (kept for 15 minutes). Blocking. */
    fun load(f: Feed, fresh: Boolean = false): List<Item> {
        if (!fresh) read[f.url]?.takeIf { System.currentTimeMillis() - it.first < KEEP_MS }?.let { return it.second }
        try {
            val c = URL(f.url).openConnection() as HttpURLConnection
            val text = try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.instanceFollowRedirects = true
                // Some publishers refuse unknown readers: say we're an ordinary feed reader.
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Stremonino feed reader)")
                c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                if (c.responseCode >= 400) throw Http.HttpError(c.responseCode)
                c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } finally { c.disconnect() }
            val items = parse(text, f.name)
            if (items.isEmpty()) throw IllegalStateException("no stories in it (is it a news feed?)")
            read[f.url] = System.currentTimeMillis() to items
            failed.remove(f.url)
            return items
        } catch (e: Exception) {
            failed[f.url] = Guide.why(e)
            throw e
        }
    }

    fun forget() { read.clear(); failed.clear() }

    /** Reads RSS 2.0 (<item>) or Atom (<entry>). */
    fun parse(text: String, source: String): List<Item> {
        val out = ArrayList<Item>()
        val tag = if (text.contains("<item", ignoreCase = true)) "item" else "entry"
        var at = 0
        while (true) {
            val s = text.indexOf("<$tag", at).takeIf { it >= 0 } ?: break
            val e = text.indexOf("</$tag>", s).takeIf { it >= 0 } ?: break
            val el = text.substring(s, e)
            at = e + tag.length + 3
            val title = clean(inner(el, "title") ?: continue)
            if (title.isBlank()) continue
            val link = inner(el, "link")?.trim()?.takeIf { it.startsWith("http") }
                ?: Regex("""<link[^>]*href="([^"]+)"""").find(el)?.groupValues?.get(1)
                ?: inner(el, "guid")?.trim()?.takeIf { it.startsWith("http") } ?: ""
            val summary = clean(inner(el, "description") ?: inner(el, "summary") ?: inner(el, "content") ?: "")
                .let { if (it.length > 400) it.take(399).substringBeforeLast(' ') + "…" else it }
            val date = time(inner(el, "pubDate") ?: inner(el, "published") ?: inner(el, "updated") ?: inner(el, "dc:date") ?: "")
            out += Item(title, link, if (summary == title) "" else summary, date, source)
        }
        return out
    }

    private fun inner(el: String, tag: String): String? {
        val s = Regex("<" + Regex.escape(tag) + "(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(el) ?: return null
        if (s.value.endsWith("/>")) return null
        val e = el.indexOf("</$tag>", s.range.last, ignoreCase = true).takeIf { it >= 0 } ?: return null
        return el.substring(s.range.last + 1, e)
    }

    /** Text from feed markup: CDATA unwrapped, HTML tags dropped, entities turned back into characters. */
    fun clean(raw: String): String {
        var t = raw.trim()
        t = t.replace("<![CDATA[", "").replace("]]>", "")
        // Feeds often escape their HTML: decode once, drop tags, then decode what's left.
        t = entities(t)
        t = t.replace(Regex("<[^>]*>"), " ")
        t = entities(t)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun entities(s: String): String {
        if (!s.contains('&')) return s
        var t = Regex("&#(x?)([0-9a-fA-F]+);").replace(s) { m ->
            val n = runCatching { m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16) }.getOrNull()
            if (n == null || n <= 0 || n > 0x10FFFF) "" else String(Character.toChars(n))
        }
        t = t.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&rsquo;", "’").replace("&lsquo;", "‘")
            .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&hellip;", "…").replace("&ndash;", "–").replace("&mdash;", "—")
            .replace("&pound;", "£").replace("&euro;", "€").replace("&copy;", "©").replace("&reg;", "®").replace("&trade;", "™")
            .replace("&eacute;", "é").replace("&egrave;", "è").replace("&aacute;", "á").replace("&ntilde;", "ñ").replace("&ouml;", "ö").replace("&uuml;", "ü")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        return t
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** RSS dates ("Tue, 24 Nov 2026 22:10:00 +0000" or "GMT") and Atom dates ("2026-11-24T22:10:00Z", "+01:00"). 0 if unreadable. */
    fun time(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.UK)
        cal.clear()
        Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*(Z|[+-]\d{2}:?\d{2})?""").find(t)?.let { m ->
            val g = m.groupValues
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
            return cal.timeInMillis - offset(g[7])
        }
        Regex("""(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([A-Za-z]+|[+-]\d{4})?""").find(t)?.let { m ->
            val g = m.groupValues
            val mon = months.indexOf(g[2].lowercase()).takeIf { it >= 0 } ?: return 0
            val year = g[3].toInt().let { if (it < 100) 2000 + it else it }
            cal.set(year, mon, g[1].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
            val z = g[7]
            val off = when {
                z.startsWith("+") || z.startsWith("-") -> offset(z)
                z.equals("EST", true) -> -5 * 3600_000L
                z.equals("EDT", true) -> -4 * 3600_000L
                z.equals("PST", true) -> -8 * 3600_000L
                z.equals("PDT", true) -> -7 * 3600_000L
                z.equals("BST", true) -> 3600_000L
                else -> 0L
            }
            return cal.timeInMillis - off
        }
        return 0
    }

    private fun offset(z: String): Long {
        if (z.isEmpty() || z == "Z") return 0
        val d = z.filter { it.isDigit() }
        if (d.length < 4) return 0
        val mins = d.substring(0, 2).toInt() * 60 + d.substring(2, 4).toInt()
        return (if (z.startsWith("-")) -1 else 1) * mins * 60_000L
    }

    // ---- putting it together ---------------------------------------------------------------------

    /**
     * Stories from [feeds], newest first, without repeats. Those naming something in [titles]
     * (your library and followed shows) get [Item.about] set.
     */
    fun merge(lists: List<List<Item>>, titles: List<String>): List<Item> {
        val seen = HashSet<String>()
        val all = lists.flatten().sortedByDescending { it.at }.filter { seen.add(it.link.ifBlank { it.title }.lowercase()) }
        val names = titles.map { it.trim() }.filter { it.length >= 4 }.distinctBy { it.lowercase() }
            // Long names first, so "Alien: Romulus" wins over "Alien".
            .sortedByDescending { it.length }
            .map { it to Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(it) + "($|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
        for (item in all) {
            names.firstOrNull { (_, re) -> re.containsMatchIn(item.title) }?.let { item.about = it.first }
        }
        return all.take(120)
    }

    /** "5 min", "2h", "3d" ago. */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0) return ""
        val m = ((now - at) / 60_000).coerceAtLeast(0)
        return when {
            m < 60 -> "${m.coerceAtLeast(1)} min"
            m < 48 * 60 -> "${m / 60}h"
            else -> "${m / (24 * 60)}d"
        }
    }
}
