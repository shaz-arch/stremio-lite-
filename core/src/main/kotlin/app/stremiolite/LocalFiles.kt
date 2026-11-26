package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** Video files in your own folders (an external drive, a mounted Google Drive), and sharing them on your home Wi-Fi. */
object LocalFiles {
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "wmv", "mpg", "mpeg", "flv", "3gp", "ogv")

    fun isVideo(f: File) = f.isFile && f.extension.lowercase() in VIDEO

    /** (folders, videos) directly in [dir], both by name; hidden ones left out. */
    fun list(dir: File): Pair<List<File>, List<File>> {
        val all = dir.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        return all.filter { it.isDirectory }.sortedBy { it.name.lowercase() } to all.filter { isVideo(it) }.sortedBy { it.name.lowercase() }
    }

    /** Every video under [roots] (up to [max]), for sharing. */
    fun all(roots: List<File>, max: Int = 5000): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File, depth: Int) {
            if (out.size >= max || depth > 8) return
            val (dirs, vids) = list(d)
            out += vids
            dirs.forEach { walk(it, depth + 1) }
        }
        roots.filter { it.isDirectory }.forEach { walk(it, 0) }
        return out.take(max)
    }

    fun id(f: File): String = MessageDigest.getInstance("SHA-1").digest(f.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun size(bytes: Long) = if (bytes >= 1_000_000_000) String.format("%.1f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"

    /** Folders mounted under /run/media/<you> or /media (where Linux puts plugged-in drives). */
    fun mountedDrives(): List<File> {
        val user = System.getProperty("user.name").orEmpty()
        return listOf(File("/run/media/$user"), File("/media/$user"), File("/media"), File("/mnt"))
            .flatMap { it.listFiles()?.filter { d -> d.isDirectory && !d.name.startsWith(".") && d.name != user }.orEmpty() }.distinctBy { it.absolutePath }
    }

    // ---- sharing over your home Wi-Fi ------------------------------------------------------------

    /** This computer's address on the home network (192.168…, 10…, 172.16–31…). */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
            .sortedBy { if (it.startsWith("192.168.")) 0 else if (it.startsWith("10.")) 1 else 2 }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(it) }
    }.getOrNull()

    /** Where the share is, kept on the account so the TV finds it: address, a random key, the computer's name. */
    object Record {
        const val ID = "stremiolite:myfiles"
        /** A phone sharing its own videos (kept apart from a computer's share). */
        const val PHONE_ID = "stremiolite:phonefiles"
        class Share(val url: String, val name: String, val at: Long)
        fun parse(raw: String?): Share? {
            val o = raw?.let { runCatching { JSONObject(JSONObject(it).optString("name")) }.getOrNull() } ?: return null
            if (o.optString("url").isEmpty()) return null
            return Share(o.optString("url"), o.optString("pc"), o.optLong("at"))
        }
        fun record(base: String?, s: Share?, id: String = ID): JSONObject {
            val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(id, "other", "{}")
            val body = if (s == null) JSONObject() else JSONObject().put("url", s.url).put("pc", s.name).put("at", s.at)
            return item.put("name", body.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
        }
    }

    class Shared(val id: String, val name: String, val folder: String, val size: Long)

    /** On the TV: the shared list (blocking). */
    fun fetch(share: Record.Share): List<Shared> {
        val c = URL(share.url.trimEnd('/') + "/files.json").openConnection() as HttpURLConnection
        c.connectTimeout = 4_000; c.readTimeout = 15_000
        try {
            val a = JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Shared(it.optString("id"), it.optString("name"), it.optString("folder"), it.optLong("size")) }
        } finally { c.disconnect() }
    }

    fun playUrl(share: Record.Share, f: Shared) = share.url.trimEnd('/') + "/v/" + f.id + "/" + URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")

    /** The computer side: http://<address>:<port>/<key>/files.json and /<key>/v/<id>/<name>, with seeking. */
    class Server(private val roots: () -> List<File>, private val key: String) {
        private var socket: ServerSocket? = null
        private var index: Map<String, File> = emptyMap()
        private var indexAt = 0L
        val port get() = socket?.localPort ?: 0

        @Synchronized fun start(port: Int = 8781): Int {
            socket?.takeIf { !it.isClosed }?.let { return it.localPort }
            val s = runCatching { ServerSocket(port) }.getOrElse { ServerSocket(0) }
            socket = s
            Thread {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { runCatching { serve(c) }; runCatching { c.close() } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return s.localPort
        }

        fun stop() { runCatching { socket?.close() }; socket = null }

        @Synchronized private fun files(): Map<String, File> {
            if (System.currentTimeMillis() - indexAt > 60_000) { index = all(roots()).associateBy { id(it) }; indexAt = System.currentTimeMillis() }
            return index
        }

        private fun serve(c: Socket) {
            val input = c.getInputStream()
            val lines = mutableListOf<String>(); val sb = StringBuilder()
            while (true) {
                val b = input.read(); if (b < 0) return
                if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); if (l.isEmpty()) break; lines += l; sb.setLength(0) } else sb.append(b.toChar())
                if (lines.size > 100) return
            }
            val req = lines.firstOrNull()?.split(' ') ?: return
            val method = req.getOrNull(0).orEmpty()
            val path = URLDecoder.decode(req.getOrNull(1).orEmpty().substringBefore('?'), "UTF-8")
            val out = c.getOutputStream()
            fun send(code: String, type: String, body: ByteArray) {
                out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
            }
            if (!path.startsWith("/$key/")) { send("404 Not Found", "text/plain", ByteArray(0)); return }
            val rest = path.removePrefix("/$key/")
            if (rest == "files.json") {
                val rootsNow = roots()
                val a = JSONArray()
                files().values.sortedBy { it.absolutePath.lowercase() }.forEach { f ->
                    val root = rootsNow.firstOrNull { f.absolutePath.startsWith(it.absolutePath) }
                    val folder = f.parentFile?.absolutePath?.removePrefix(root?.parentFile?.absolutePath.orEmpty())?.trim('/').orEmpty()
                    a.put(JSONObject().put("id", id(f)).put("name", f.name).put("folder", folder).put("size", f.length()))
                }
                send("200 OK", "application/json; charset=utf-8", a.toString().toByteArray()); return
            }
            if (!rest.startsWith("v/")) { send("404 Not Found", "text/plain", ByteArray(0)); return }
            val f = files()[rest.removePrefix("v/").substringBefore('/')] ?: run { send("404 Not Found", "text/plain", ByteArray(0)); return }
            val len = f.length()
            val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter('=')?.trim()
            var start = 0L; var end = len - 1
            if (range != null) {
                val a = range.substringBefore('-'); val b = range.substringAfter('-').substringBefore(',')
                if (a.isNotEmpty()) start = a.toLongOrNull() ?: 0
                if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(len - 1)
                if (a.isEmpty() && b.isNotEmpty()) { start = (len - (b.toLongOrNull() ?: 0)).coerceAtLeast(0); end = len - 1 }
            }
            val type = when (f.extension.lowercase()) { "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; else -> "video/*" }
            val head = (if (range != null) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/$len\r\n" else "HTTP/1.1 200 OK\r\n") +
                "Content-Type: $type\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            if (method == "HEAD") { out.flush(); return }
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024); var left = end - start + 1
                while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
            }
            out.flush()
        }
    }
}
