package app.stremiolite

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Sending your keys from one device to another on the same Wi-Fi (phone → stick), so they're typed once.
 * The receiving device waits for 5 minutes and leaves a note on your account saying where it is and a
 * one-time code; the keys themselves only travel over your Wi-Fi, straight to it, never via the account.
 */
object KeyDrop {
    const val ID = "stremiolite:keydrop"
    const val FRESH_MS = 5 * 60_000L

    class Offer(val url: String, val token: String, val device: String, val at: Long) {
        val fresh get() = System.currentTimeMillis() - at < FRESH_MS
    }

    fun parse(raw: String?): Offer? {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val r = runCatching { JSONObject(o.optString("name")) }.getOrNull() ?: return null
        if (r.optString("url").isEmpty()) return null
        return Offer(r.optString("url"), r.optString("token"), r.optString("device"), r.optLong("at"))
    }

    fun record(base: String?, offer: Offer?): JSONObject {
        val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "{}")
        val body = if (offer == null) JSONObject() else JSONObject().put("url", offer.url).put("token", offer.token).put("device", offer.device).put("at", offer.at)
        return item.put("name", body.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    /** Waits on the Wi-Fi for one delivery with the right code, then stops. */
    class Receiver(private val onKeys: (JSONObject) -> Unit) {
        val token: String = java.util.UUID.randomUUID().toString().replace("-", "")
        private var socket: ServerSocket? = null

        fun start(): Int {
            val s = ServerSocket(0); socket = s
            Thread {
                val until = System.currentTimeMillis() + FRESH_MS
                s.soTimeout = 15_000
                while (!s.isClosed && System.currentTimeMillis() < until) {
                    val c = runCatching { s.accept() }.getOrNull() ?: continue
                    val done = runCatching { serve(c) }.getOrDefault(false)
                    runCatching { c.close() }
                    if (done) break
                }
                stop()
            }.apply { isDaemon = true }.start()
            return s.localPort
        }

        fun stop() { runCatching { socket?.close() }; socket = null }

        private fun serve(c: Socket): Boolean {
            c.soTimeout = 10_000
            val input = c.getInputStream()
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read(); if (b < 0) return false
                head.write(b); last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.size() > 8192) return false
            }
            val lines = head.toString("UTF-8").split("\r\n")
            val ok = lines.firstOrNull()?.startsWith("POST /k ") == true &&
                lines.any { it.equals("X-Token: $token", ignoreCase = false) }
            val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val out = c.getOutputStream()
            if (!ok || len !in 1..20_000) { out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return false }
            val body = ByteArray(len); var got = 0
            while (got < len) { val n = input.read(body, got, len - got); if (n < 0) break; got += n }
            val keys = runCatching { JSONObject(String(body, 0, got, Charsets.UTF_8)) }.getOrNull()
            if (keys == null) { out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return false }
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray()); out.flush()
            onKeys(keys)
            return true
        }
    }

    /** Sends [keys] to the waiting device (blocking). */
    fun send(offer: Offer, keys: JSONObject) {
        val c = URL(offer.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("X-Token", offer.token)
            c.setRequestProperty("Content-Type", "application/json")
            val b = keys.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            if (c.responseCode != 200) throw Http.HttpError(c.responseCode)
        } finally { c.disconnect() }
    }

    /** Keys as sent: TMDB (and country), debrid keys, MDBList, Gemini. */
    fun pack(tmdb: String, region: String, debrid: List<Pair<String, String>>, mdblist: String, gemini: String): JSONObject =
        JSONObject().put("tmdb", tmdb).put("region", region).put("debrid", StreamSetup.writeKeys(debrid)).put("mdblist", mdblist).put("gemini", gemini)
}
