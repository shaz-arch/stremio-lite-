package app.stremiolite

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

/**
 * Streams that need special request headers (an add-on's behaviorHints.proxyHeaders): VLC and mpv
 * are given a link on this device only, which fetches the real one with those headers added. Live
 * (HLS) playlists are rewritten so every part goes the same way.
 */
object HeaderBridge {
    private class Link(val url: String, val headers: Map<String, String>)
    private val links = LinkedHashMap<String, Link>()
    private var server: ServerSocket? = null

    @Synchronized private fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { serve(c) }; runCatching { c.close() } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return s.localPort
    }

    /** A link VLC or mpv can open that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>): String {
        val token = synchronized(links) {
            links.entries.firstOrNull { it.value.url == url && it.value.headers == headers }?.key
                ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16).also {
                    links[it] = Link(url, headers)
                    if (links.size > 500) { val i = links.keys.iterator(); i.next(); i.remove() }
                }
        }
        val name = url.substringBefore('?').substringAfterLast('/').ifEmpty { "video" }.take(60)
        return "http://127.0.0.1:${start()}/h/$token/$name"
    }

    /** The real link and its headers behind one of our links, or null (for players that take headers themselves). */
    fun lookup(wrapped: String): Pair<String, Map<String, String>>? {
        if (!wrapped.startsWith("http://127.0.0.1:") || "/h/" !in wrapped) return null
        val token = wrapped.substringAfter("/h/").substringBefore('/')
        if (wrapped.substringAfter("/h/$token/").startsWith("u/")) return null
        return synchronized(links) { links[token] }?.let { it.url to it.headers }
    }

    fun headersOf(proxyHeaders: org.json.JSONObject?): Map<String, String> {
        val req = proxyHeaders?.optJSONObject("request") ?: return emptyMap()
        return req.keys().asSequence().associateWith { req.optString(it) }.filterValues { it.isNotEmpty() }
    }

    private fun b64(s: String) = B64.encode(s.toByteArray(Charsets.UTF_8)).replace('+', '-').replace('/', '_').trimEnd('=')
    private fun unb64(s: String) = String(B64.decode(s), Charsets.UTF_8)

    private fun serve(c: Socket) {
        val input = c.getInputStream()
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        while (true) {
            val b = input.read(); if (b < 0) return
            if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); if (l.isEmpty()) break; lines += l; sb.setLength(0) } else sb.append(b.toChar())
            if (lines.size > 100) return
        }
        val req = lines.firstOrNull()?.split(' ') ?: return
        val method = req.getOrNull(0) ?: return
        val path = req.getOrNull(1) ?: return
        val out = c.getOutputStream()
        fun notFound() = out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        if (!path.startsWith("/h/")) return notFound()
        val parts = path.removePrefix("/h/").split('/')
        val link = synchronized(links) { links[parts.getOrNull(0)] } ?: return notFound()
        // /h/<token>/u/<part, base64>/<name>: a part of a live playlist.
        val target = if (parts.getOrNull(1) == "u") runCatching { unb64(URLDecoder.decode(parts.getOrNull(2) ?: "", "UTF-8")) }.getOrNull() ?: return notFound() else link.url
        val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
        val up = URL(target).openConnection() as HttpURLConnection
        try {
            up.connectTimeout = 15_000; up.readTimeout = 60_000
            up.instanceFollowRedirects = true
            link.headers.forEach { (k, v) -> up.setRequestProperty(k, v) }
            if (!link.headers.keys.any { it.equals("User-Agent", true) }) up.setRequestProperty("User-Agent", Http.userAgent)
            if (range != null) up.setRequestProperty("Range", range)
            if (method == "HEAD") up.requestMethod = "HEAD"
            val code = up.responseCode
            val type = up.getHeaderField("Content-Type").orEmpty()
            val playlist = "mpegurl" in type.lowercase() || up.url.path.lowercase().endsWith(".m3u8")
            if (playlist && method != "HEAD" && code < 400) {
                val base = up.url
                val token = parts[0]
                val port = start()
                fun via(u: String): String {
                    val abs = URL(base, u).toString()
                    return "http://127.0.0.1:$port/h/$token/u/${b64(abs)}/" + abs.substringBefore('?').substringAfterLast('/').ifEmpty { "part" }.take(40)
                }
                val text = up.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val body = text.lines().joinToString("\n") { l ->
                    when {
                        l.isBlank() -> l
                        l.startsWith("#") -> Regex("""URI="([^"]+)"""").replace(l) { m -> "URI=\"" + via(m.groupValues[1]) + "\"" }
                        else -> via(l.trim())
                    }
                }.toByteArray(Charsets.UTF_8)
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body); out.flush(); return
            }
            val head = StringBuilder("HTTP/1.1 $code ${up.responseMessage ?: ""}\r\n")
            for (h in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) up.getHeaderField(h)?.let { head.append("$h: $it\r\n") }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method != "HEAD") ((if (code >= 400) up.errorStream else up.inputStream) ?: return).use { copy(it, out) }
            out.flush()
        } finally { up.disconnect() }
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n) }
    }
}
