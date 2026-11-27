package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Saving films and episodes for watching without internet (phone and Linux). */
object Downloads {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"

    /** "Download quality": the highest a download picks (it takes the smallest good file at that height). */
    val CAPS = listOf(2 to "Up to 720p", 3 to "Up to 1080p", 4 to "Best available")
    fun capLabel(cap: Int) = CAPS.firstOrNull { it.first == cap }?.second ?: CAPS[1].second
    fun nextCap(cap: Int) = CAPS[(CAPS.indexOfFirst { it.first == cap }.coerceAtLeast(0) + 1) % CAPS.size].first

    class Item(
        /** The film or episode id (one download each). */
        val key: String,
        val title: String,
        /** What playback is filed under (the app's own "watch" record). */
        val watch: JSONObject,
        var file: String,
        var url: String,
        var quality: String,
        var status: String,
        var sysId: Long = -1,
        var got: Long = 0,
        var total: Long = 0,
        val added: Long = System.currentTimeMillis(),
        var sub: String = "",
        var why: String = "",
    ) {
        val done get() = status == DONE
        fun toJson(): JSONObject = JSONObject().put("key", key).put("title", title).put("watch", watch).put("file", file).put("url", url)
            .put("quality", quality).put("status", status).put("sysId", sysId).put("got", got).put("total", total).put("added", added).put("sub", sub).put("why", why)

        /** "45% · 1.2 GB", "✓ 1.4 GB", "waiting for Wi-Fi"… */
        fun line(): String = when (status) {
            DONE -> "✓ " + LocalFiles.size(if (total > 0) total else File(file).length())
            FAILED -> "✗ " + why.ifEmpty { "failed" }
            RUNNING -> if (total > 0) "${(got * 100 / total).coerceIn(0, 100)}% of ${LocalFiles.size(total)}" else "starting…"
            else -> why.ifEmpty { "waiting" }
        }

        companion object {
            fun from(o: JSONObject) = Item(
                o.optString("key"), o.optString("title"), o.optJSONObject("watch") ?: JSONObject(), o.optString("file"), o.optString("url"),
                o.optString("quality"), o.optString("status"), o.optLong("sysId", -1), o.optLong("got"), o.optLong("total"),
                o.optLong("added"), o.optString("sub"), o.optString("why"),
            )
        }
    }

    fun parse(raw: String?): MutableList<Item> = runCatching {
        val a = JSONArray(raw ?: "[]"); (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Item.from(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun write(list: List<Item>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    /** Streams worth downloading, best first: cached, not a cam, at or below [cap], smallest file at the best height. */
    fun candidates(found: List<Stream>, minutes: Double, cap: Int): List<Stream> {
        val ok = found.filter { it.cached != false }.filter { Best.score(it, minutes) != null }
        val within = ok.filter { it.rank in 1..cap }.ifEmpty { ok }
        return within.sortedWith(compareBy<Stream>({ -it.rank }, { if (it.sizeMb > 0) it.sizeMb else Double.MAX_VALUE }, { it.order }))
    }

    /** Anything smaller than this once downloaded is an error clip ("file unavailable"), not the film. */
    const val MIN_BYTES = 40L * 1024 * 1024

    /** What "⇩ Download" offers: every candidate, the best fit checked to really be a video, and the ones found broken. */
    class Choice(val found: List<Stream>, val best: Stream?, val broken: Set<Stream>)

    fun choose(addons: List<Addon>, type: String, id: String, minutes: Double, cap: Int): Choice {
        val found = candidates(StreamParse.fetchAll(addons, type, id), minutes, 4)
        val broken = mutableSetOf<Stream>()
        var best: Stream? = null
        for (s in candidates(found, minutes, cap).take(6)) { if (StreamCheck.broken(s.url)) broken += s else { best = s; break } }
        return Choice(found, best, broken)
    }

    /** The stream a download takes, checked to really be a video (blocking). */
    fun pick(addons: List<Addon>, type: String, id: String, minutes: Double, cap: Int): Stream? {
        for (s in candidates(StreamParse.fetchAll(addons, type, id), minutes, cap).take(6)) if (!StreamCheck.broken(s.url)) return s
        return null
    }

    fun ext(s: Stream): String =
        Regex("""\.(MKV|MP4|M4V|AVI|WEBM|MOV)\b""").findAll(s.text).lastOrNull()?.groupValues?.get(1)?.lowercase() ?: "mkv"

    /** A tidy, unique file name for a title. */
    fun fileName(title: String, key: String, ext: String): String =
        title.replace(Regex("""[^\p{L}\p{N} \-().,']"""), "").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Video" } +
            " [" + Integer.toHexString(key.hashCode()) + "]." + ext

    /** A subtitle in [lang] from the subtitle add-ons (blocking), or null. */
    fun subtitle(addons: List<Addon>, type: String, id: String, lang: Language): Sub? {
        if (lang.codes.isEmpty()) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        for (a in addons.filter { it.supports("subtitles", type, id) }) {
            val arr = runCatching { JSONObject(Http.get(a.base + "/subtitles/" + enc(type) + "/" + enc(id) + ".json", timeoutMs = 8_000)).optJSONArray("subtitles") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = Sub(o.optString("url"), o.optString("lang"), a.name)
                if (s.url.startsWith("http") && Languages.matches(s, lang)) return s
            }
        }
        return null
    }

    /**
     * Saves [url] into [to], carrying on from a part already there. [progress] gets (bytes so far, total);
     * [stop] ends it early (the part is kept to carry on later). Throws on failure.
     */
    fun fetchTo(url: String, to: File, progress: (Long, Long) -> Unit = { _, _ -> }, stop: () -> Boolean = { false }) {
        to.parentFile?.mkdirs()
        val have = if (to.isFile) to.length() else 0L
        var link = url
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(link).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", Http.userAgent)
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399 && hops < 8) { link = URL(URL(link), c.getHeaderField("Location")).toString(); c.disconnect(); hops++; continue }
            if (code == 416 && have > 0) { c.disconnect(); progress(have, have); return }   // already complete
            if (code >= 400) { c.disconnect(); throw Http.HttpError(code) }
            break
        }
        try {
            val partial = c.responseCode == 206
            val start = if (partial) have else 0L
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { it + start } ?: -1L
            RandomAccessFile(to, "rw").use { out ->
                out.setLength(start); out.seek(start)
                var got = start
                var told = 0L
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (stop()) return
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        val now = System.currentTimeMillis()
                        if (now - told > 1_000) { told = now; progress(got, total) }
                    }
                }
                progress(got, if (total > 0) total else got)
                if (total > 0 && got < total) throw java.io.IOException("the connection ended early")
            }
        } finally { c.disconnect() }
    }
}
