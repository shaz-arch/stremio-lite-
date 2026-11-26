package app.stremiolite

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

/**
 * Google Drive videos: sign-in (only the "drive.file" permission, so Stremonino sees just the
 * files a person picks), the list of picked videos, and a small local bridge that plays them
 * (Drive wants a sign-in on every request, which video players can't send).
 */
object GDrive {
    const val ANDROID_ID = "1057144830851-oj132p8ogplug0mm685f20p0ilm61f2j.apps.googleusercontent.com"
    const val TV_ID = "1057144830851-6i3q6s7ff8d6ecvi89irje8ommp2t3qf.apps.googleusercontent.com"
    const val DESKTOP_ID = "1057144830851-e76he188istc9p3v09vs5coe09qkrd6r.apps.googleusercontent.com"
    /** The phone comes back from Google's picker through this address. */
    const val ANDROID_SCHEME = "com.googleusercontent.apps.1057144830851-oj132p8ogplug0mm685f20p0ilm61f2j"
    const val ANDROID_REDIRECT = "$ANDROID_SCHEME:/oauth2redirect"
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    /** Secrets for the TV and Linux sign-ins, put in by the build (never in the code). */
    private val secrets: Properties by lazy {
        Properties().apply { runCatching { GDrive::class.java.getResourceAsStream("/stremonino-google.properties")?.use { load(it) } } }
    }
    val tvSecret: String get() = secrets.getProperty("tv", "")
    val desktopSecret: String get() = secrets.getProperty("desktop", "")
    fun secretFor(client: String) = when (client) { TV_ID -> tvSecret; DESKTOP_ID -> desktopSecret; else -> "" }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** URL-safe base64 without padding (java.util.Base64 isn't on Android 5). */
    private fun b64url(b: ByteArray): String {
        val t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val n = ((b[i].toInt() and 255) shl 16) or ((if (i + 1 < b.size) b[i + 1].toInt() and 255 else 0) shl 8) or (if (i + 2 < b.size) b[i + 2].toInt() and 255 else 0)
            sb.append(t[(n shr 18) and 63]).append(t[(n shr 12) and 63])
            if (i + 1 < b.size) sb.append(t[(n shr 6) and 63])
            if (i + 2 < b.size) sb.append(t[n and 63])
            i += 3
        }
        return sb.toString()
    }

    /** A PKCE pair: (verifier, challenge). */
    fun pkce(): Pair<String, String> {
        val v = ByteArray(48).also { SecureRandom().nextBytes(it) }.let(::b64url)
        return v to b64url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
    }

    /** Google's sign-in, with its file picker opening straight after (phone and Linux). */
    fun pickerUrl(client: String, redirect: String, challenge: String): String =
        "https://accounts.google.com/o/oauth2/v2/auth?client_id=${enc(client)}&redirect_uri=${enc(redirect)}" +
            "&response_type=code&scope=${enc(SCOPE)}&code_challenge=$challenge&code_challenge_method=S256" +
            "&access_type=offline&prompt=consent&trigger_onepick=true&allow_multiple=true&allow_folder_selection=true"

    class Tokens(val access: String, val refresh: String, val expires: Long)

    private fun form(url: String, fields: Map<String, String>): Pair<Int, JSONObject> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(fields.filterValues { v -> v.isNotEmpty() }.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray()) }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return code to (runCatching { JSONObject(body) }.getOrNull() ?: JSONObject())
        } finally { c.disconnect() }
    }

    private fun tokens(o: JSONObject, keepRefresh: String = "") = Tokens(
        o.optString("access_token"), o.optString("refresh_token").ifEmpty { keepRefresh },
        System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000)

    class Failed(msg: String) : Exception(msg)

    /** Blocking: the code from the sign-in page, for tokens. */
    fun exchange(client: String, code: String, verifier: String, redirect: String): Tokens {
        val (status, o) = form("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to client, "client_secret" to secretFor(client), "code" to code, "code_verifier" to verifier,
            "redirect_uri" to redirect, "grant_type" to "authorization_code"))
        if (status >= 400 || o.optString("access_token").isEmpty()) throw Failed(o.optString("error_description", o.optString("error", "sign-in failed ($status)")))
        return tokens(o)
    }

    /** Blocking: a fresh access token. */
    fun refresh(client: String, refresh: String): Tokens {
        val (status, o) = form("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to client, "client_secret" to secretFor(client), "refresh_token" to refresh, "grant_type" to "refresh_token"))
        if (status >= 400 || o.optString("access_token").isEmpty()) throw Failed(o.optString("error_description", o.optString("error", "Google sign-in expired")))
        return tokens(o, refresh)
    }

    // ---- TV sign-in: a code typed at google.com/device on the phone ---------------------------

    class DeviceCode(val device: String, val user: String, val url: String, val interval: Int, val until: Long)

    fun deviceStart(): DeviceCode {
        val (status, o) = form("https://oauth2.googleapis.com/device/code", mapOf("client_id" to TV_ID, "scope" to SCOPE))
        if (status >= 400) throw Failed(o.optString("error_description", o.optString("error", "Google didn't answer ($status)")))
        return DeviceCode(o.optString("device_code"), o.optString("user_code"), o.optString("verification_url", "https://www.google.com/device"),
            o.optInt("interval", 5).coerceAtLeast(5), System.currentTimeMillis() + o.optLong("expires_in", 900) * 1000)
    }

    /** Blocking: tokens once approved, null while waiting; throws if refused or expired. */
    fun devicePoll(d: DeviceCode): Tokens? {
        val (_, o) = form("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to TV_ID, "client_secret" to tvSecret, "device_code" to d.device,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        if (o.optString("access_token").isNotEmpty()) return tokens(o)
        return when (o.optString("error")) {
            "authorization_pending", "slow_down" -> null
            "access_denied" -> throw Failed("You said no on the phone")
            "expired_token" -> throw Failed("The code ran out: start again")
            else -> throw Failed(o.optString("error_description", o.optString("error", "sign-in failed")))
        }
    }

    // ---- the picked videos ---------------------------------------------------------------------

    class File(val id: String, val name: String, val size: Long, val durationMs: Long, val folder: Boolean)

    private fun get(access: String, url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $access")
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code >= 400) throw Failed(runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull() ?: "Drive said $code")
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    private fun query(access: String, q: String): List<File> {
        val out = mutableListOf<File>(); var page = ""
        repeat(10) {
            val url = "https://www.googleapis.com/drive/v3/files?pageSize=200&orderBy=name&q=${enc(q)}" +
                "&fields=${enc("nextPageToken,files(id,name,size,mimeType,videoMediaMetadata(durationMillis))")}" +
                (if (page.isNotEmpty()) "&pageToken=${enc(page)}" else "")
            val o = get(access, url)
            val a = o.optJSONArray("files")
            if (a != null) for (i in 0 until a.length()) a.optJSONObject(i)?.let { f ->
                out += File(f.optString("id"), f.optString("name"), f.optString("size").toLongOrNull() ?: 0,
                    f.optJSONObject("videoMediaMetadata")?.optString("durationMillis")?.toLongOrNull() ?: 0,
                    f.optString("mimeType") == "application/vnd.google-apps.folder")
            }
            page = o.optString("nextPageToken")
            if (page.isEmpty()) return out
        }
        return out
    }

    /** Blocking: every video you've picked, plus what's inside picked folders where Google allows it. */
    fun videos(access: String): List<File> {
        val direct = query(access, "mimeType contains 'video/' and trashed = false")
        val folders = runCatching { query(access, "mimeType = 'application/vnd.google-apps.folder' and trashed = false") }.getOrDefault(emptyList())
        val inside = folders.take(20).flatMap { f -> runCatching { query(access, "'${f.id}' in parents and mimeType contains 'video/' and trashed = false") }.getOrDefault(emptyList()) }
        return (direct + inside).distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** Blocking: changes a picked file (PATCH, sent as POST with an override for old Androids). */
    private fun patch(access: String, id: String, body: JSONObject) {
        val c = URL("https://www.googleapis.com/drive/v3/files/${enc(id)}?supportsAllDrives=true").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Authorization", "Bearer $access")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code >= 400) {
                val err = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                throw Failed(runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull() ?: "Drive said $code")
            }
        } finally { c.disconnect() }
    }

    /** Renames the file in your Drive. */
    fun rename(access: String, id: String, name: String) = patch(access, id, JSONObject().put("name", name))

    /** Moves the file to your Drive's bin (Google keeps it 30 days). */
    fun trash(access: String, id: String) = patch(access, id, JSONObject().put("trashed", true))

    /**
     * Groups and hidden videos, kept on your Stremio account (Stremonino only; Drive is untouched),
     * so every device shows the same arrangement.
     */
    object Org {
        const val ID = "stremiolite:drive"
        class State(val groups: Map<String, List<String>>, val hidden: Set<String>) {
            fun groupOf(id: String) = groups.entries.firstOrNull { id in it.value }?.key
            fun put(id: String, group: String?) = State(
                groups.mapValues { (_, v) -> v - id }.filterValues { it.isNotEmpty() }.toMutableMap().apply { if (group != null) this[group] = (this[group].orEmpty()) + id },
                hidden)
            fun hide(id: String, on: Boolean) = State(groups, if (on) hidden + id else hidden - id)
        }
        fun parse(raw: String?): State {
            val o = raw?.let { runCatching { JSONObject(JSONObject(it).optString("name")) }.getOrNull() } ?: return State(emptyMap(), emptySet())
            val g = o.optJSONObject("groups")
            val groups = LinkedHashMap<String, List<String>>()
            g?.keys()?.forEach { k -> groups[k] = g.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() }
            val h = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
            return State(groups, h)
        }
        fun record(base: String?, s: State): JSONObject {
            val item = base?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "{}")
            val g = JSONObject(); s.groups.forEach { (k, v) -> g.put(k, org.json.JSONArray(v)) }
            return item.put("name", JSONObject().put("groups", g).put("hidden", org.json.JSONArray(s.hidden.toList())).toString())
                .put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
        }
    }

    fun nice(f: File): String {
        val parts = mutableListOf<String>()
        if (f.durationMs > 0) parts += "${f.durationMs / 60_000} min"
        if (f.size > 0) parts += if (f.size >= 1_000_000_000) String.format("%.1f GB", f.size / 1e9) else "${f.size / 1_000_000} MB"
        return parts.joinToString(" · ")
    }

    // ---- the local bridge: http://127.0.0.1:<port>/drive/<id> → Drive, with the sign-in added ----

    class Bridge(private val access: () -> String) {
        private var server: ServerSocket? = null
        val port: Int get() = server?.localPort ?: 0

        @Synchronized fun start(): Int {
            server?.takeIf { !it.isClosed }?.let { return it.localPort }
            val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = s
            Thread {
                while (!s.isClosed) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { runCatching { serve(c) }; runCatching { c.close() } }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return s.localPort
        }

        fun url(id: String, name: String): String = "http://127.0.0.1:${start()}/drive/${enc(id)}/${enc(name).replace("+", "%20")}"

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
            val id = path.removePrefix("/drive/").substringBefore('/').let { URLDecoder.decode(it, "UTF-8") }
            val range = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
            val out = c.getOutputStream()
            if (!path.startsWith("/drive/") || id.isEmpty()) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
            val up = URL("https://www.googleapis.com/drive/v3/files/${enc(id)}?alt=media&supportsAllDrives=true").openConnection() as HttpURLConnection
            try {
                up.connectTimeout = 15_000; up.readTimeout = 60_000
                up.setRequestProperty("Authorization", "Bearer ${access()}")
                if (range != null) up.setRequestProperty("Range", range)
                if (method == "HEAD") up.requestMethod = "HEAD"
                val code = up.responseCode
                val head = StringBuilder("HTTP/1.1 $code ${up.responseMessage ?: ""}\r\n")
                for (h in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) up.getHeaderField(h)?.let { head.append("$h: $it\r\n") }
                if (up.getHeaderField("Accept-Ranges") == null) head.append("Accept-Ranges: bytes\r\n")
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
}
