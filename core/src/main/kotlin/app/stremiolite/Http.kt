package app.stremiolite

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain blocking HTTP, shared by the Android app and the Linux app. (On Android, Net.init installs
 * the extra trusted certificates globally, so these connections get them too.)
 */
object Http {
    class HttpError(val code: Int) : IOException("HTTP $code")

    /** Set by each app, e.g. "Stremonino/1.14 (Android TV)". */
    @Volatile
    var userAgent = "Stremonino"

    fun get(url: String, timeoutMs: Int = 15_000, allowErrors: Boolean = false): String =
        request(url, null, timeoutMs, allowErrors)

    /** POST a JSON body. API errors come back as JSON, so error codes are allowed through. */
    fun postJson(url: String, body: String, timeoutMs: Int = 15_000): String =
        request(url, body, timeoutMs, allowErrors = true)

    /** Sends JSON with [method] (POST, PUT…) and returns the status code with the reply text. */
    fun sendJson(url: String, method: String, body: String, timeoutMs: Int = 30_000): Pair<Int, String> {
        val c = open(url, timeoutMs)
        try {
            c.requestMethod = method
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
        } finally {
            c.disconnect()
        }
    }

    fun getBytes(url: String, timeoutMs: Int = 15_000): ByteArray {
        val c = open(url, timeoutMs)
        try {
            if (c.responseCode >= 400) throw HttpError(c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun request(url: String, body: String?, timeoutMs: Int, allowErrors: Boolean): String {
        val c = open(url, timeoutMs)
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code >= 400 && !allowErrors) throw HttpError(code)
            return text
        } finally {
            c.disconnect()
        }
    }
}

/** Base64 that works on Android 5 (java.util.Base64 needs Android 8) and on desktop Java. */
object B64 {
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(CHARS[b0 shr 2])
            sb.append(CHARS[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) CHARS[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) CHARS[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filter { it != '=' && !it.isWhitespace() }.map { c ->
            when (c) { '-' -> '+'; '_' -> '/'; else -> c }
        }
        val out = java.io.ByteArrayOutputStream(clean.size * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in clean) {
            val v = CHARS.indexOf(c)
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
