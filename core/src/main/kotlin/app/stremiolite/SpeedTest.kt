package app.stremiolite

import java.net.HttpURLConnection
import java.net.URL

/**
 * How fast streams reach this device: a few seconds of a real stream for a test film, read and
 * thrown away. Kept per device (your connection, not your account). Play best then prefers files
 * your connection keeps up with; it never refuses to play if none fit.
 */
object SpeedTest {
    class Result(val at: Long, val mbps: Double)

    const val EVERY_MS = 6 * 3600_000L        // a quiet re-check at most this often
    private const val RECENT_MS = 7 * 24 * 3600_000L
    private const val KEEP = 5
    /** Share of the measured speed a stream should use, leaving room for dips. */
    private const val HEADROOM = 0.7

    /** Reads up to [maxBytes] or [maxMs] of [url] and returns the speed in Mbps (blocking). */
    fun measure(url: String, maxBytes: Long = 8_000_000, maxMs: Long = 8_000): Double {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = true
            if (c.responseCode >= 400) throw Http.HttpError(c.responseCode)
            val input = c.inputStream
            val buf = ByteArray(64 * 1024)
            var got = 0L
            var start = 0L
            while (got < maxBytes) {
                val n = input.read(buf)
                if (n < 0) break
                if (start == 0L) start = System.nanoTime()   // time from the first data, not the connecting
                else got += n
                if ((System.nanoTime() - start) / 1_000_000 > maxMs) break
            }
            val secs = (System.nanoTime() - start) / 1e9
            if (got < 256 * 1024 || secs <= 0) throw IllegalStateException("too little data to measure")
            return got * 8 / secs / 1e6
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /** A stream to test with: the one Play best would choose for the test film. */
    fun testUrl(addons: List<Addon>): String? {
        val found = StreamParse.fetchAll(addons.filter { !it.isLocal }, "movie", Health.TEST_ID)
        return Best.pick(found.filter { it.cached == true }.ifEmpty { found }, 142.0)?.url
    }

    fun parse(raw: String?): List<Result> = raw.orEmpty().split(',').mapNotNull { p ->
        val (a, b) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val at = a.toLongOrNull(); val m = b.toDoubleOrNull()
        if (at != null && m != null) Result(at, m) else null
    }

    fun write(list: List<Result>): String = list.takeLast(KEEP).joinToString(",") { "${it.at}:${"%.1f".format(java.util.Locale.UK, it.mbps)}" }

    fun added(list: List<Result>, r: Result) = (list + r).takeLast(KEEP)

    /** The usual speed lately (the middle of your last few checks), or null if none this week. */
    fun typical(list: List<Result>, now: Long = System.currentTimeMillis()): Double? {
        val recent = list.filter { now - it.at < RECENT_MS }.map { it.mbps }.sorted()
        if (recent.isEmpty()) return null
        return if (recent.size % 2 == 1) recent[recent.size / 2] else (recent[recent.size / 2 - 1] + recent[recent.size / 2]) / 2
    }

    /** What a stream should stay under, for Play best. */
    fun usable(typical: Double?) = typical?.let { it * HEADROOM }

    fun due(list: List<Result>, now: Long = System.currentTimeMillis()) = list.none { now - it.at < EVERY_MS }

    /** "18 Mbps: fine for files up to about 11 GB for a 2-hour film". */
    fun advice(mbps: Double): String {
        val gb = usable(mbps)!! * 7200 / 8 / 1000
        val size = if (gb >= 10) "%.0f".format(java.util.Locale.UK, gb) else "%.1f".format(java.util.Locale.UK, gb)
        return "%.0f Mbps: fine for files up to about $size GB for a 2-hour film".format(java.util.Locale.UK, mbps)
    }
}
