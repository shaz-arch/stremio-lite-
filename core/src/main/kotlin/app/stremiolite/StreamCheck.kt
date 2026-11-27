package app.stremiolite

import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks what a stream link really serves, just before playing. A cached release can still turn
 * out to hold no playable video; ElfHosted then serves a small "No playable video found" clip
 * instead. Real films and episodes are far bigger, so a tiny file means a broken release.
 */
object StreamCheck {
    /** Anything smaller than this isn't a real film or episode. */
    private const val MIN_BYTES = 40L * 1024 * 1024

    /** The size in bytes of what the link serves (following redirects), or null if it can't tell. */
    fun size(link: String, timeoutMs: Int = 6_000): Long? {
        var url = link
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", Http.userAgent)
                c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: return null
                    url = URL(URL(url), next).toString()
                    return@repeat
                }
                if (code >= 400) return null
                // "bytes 0-0/1234567" gives the whole size; otherwise Content-Length (when the range was ignored)
                c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { return it }
                return if (code == 200) c.getHeaderField("Content-Length")?.toLongOrNull() else null
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    /** True if the link serves something too small to be the real video. Unknown counts as fine. */
    fun broken(link: String): Boolean = if (YouTube.idOf(link) != null) false else runCatching { size(link) }.getOrNull()?.let { it in 1 until MIN_BYTES } ?: false
}
