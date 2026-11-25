package app.stremiolite

import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Type on your phone": while a typing box is open, a tiny web page on this device, reachable only on
 * the home network. Scan the QR, type on the phone, and the text appears on the TV as you type;
 * Go on the phone submits. The link carries a one-off secret, nothing goes through the internet,
 * and the page closes with the typing box.
 */
class PhoneKeyboard(
    private val title: String,
    private val initial: String,
    /** The text so far, as it's typed (called on a background thread). */
    private val onText: (String) -> Unit,
    /** Go pressed on the phone (background thread). */
    private val onGo: (String) -> Unit,
) {
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    @Volatile private var open = false

    /** The link for the QR, or null if the device isn't on a local network. */
    var link: String? = null
        private set

    /** Starts listening; returns the link (null if there's no local network address). */
    fun start(address: String? = localAddress()): String? {
        val ip = address ?: return null
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ip, 0)) } }.getOrNull() ?: return null
        server = s
        open = true
        link = "http://$ip:${s.localPort}/?k=$token"
        Thread {
            while (open) {
                val c = try { s.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                Thread { runCatching { serve(c) }; runCatching { c.close() } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "phone-keyboard" }.start()
        return link
    }

    fun stop() {
        open = false
        runCatching { server?.close() }
        server = null
    }

    private fun serve(c: Socket) {
        c.soTimeout = 10_000
        val input = c.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val key = query.split('&').firstOrNull { it.startsWith("k=") }?.removePrefix("k=")
        val length = lines.firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0

        if (key != token) return reply(c, 403, "text/plain", "This link has expired. Scan the code on the TV again.")
        val body = if (length in 1..4096) readBody(input, length) else ""
        when {
            method == "GET" && path == "/" -> reply(c, 200, "text/html; charset=utf-8", page(title, initial))
            method == "POST" && path == "/t" -> { onText(body); reply(c, 204, "text/plain", "") }
            method == "POST" && path == "/go" -> { onGo(body); reply(c, 200, "text/plain", "ok") }
            else -> reply(c, 404, "text/plain", "")
        }
    }

    private fun readHead(input: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 8192) {
            val ch = input.read()
            if (ch < 0) return null
            b.append(ch.toChar())
            if (b.endsWith("\r\n\r\n")) return b.toString().removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readBody(input: InputStream, n: Int): String {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) { val r = input.read(buf, got, n - got); if (r < 0) break; got += r }
        return String(buf, 0, got, Charsets.UTF_8)
    }

    private fun reply(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    companion object {
        /** This device's address on the home network (Wi-Fi or Ethernet), if it has one. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { n -> if (n.name.startsWith("wlan") || n.name.startsWith("eth") || n.name.startsWith("en")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** The phone's page: one box, sent as you type, and a Go button. Plain, big, no scripts from elsewhere. */
        fun page(title: String, initial: String) = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Stremonino: ${esc(title)}</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:20px;background:#111;color:#eee}
h1{font-size:20px;font-weight:600;margin:0 0 14px}
input{width:100%;box-sizing:border-box;font-size:22px;padding:14px;border-radius:10px;border:1px solid #555;background:#222;color:#fff}
button{margin-top:14px;width:100%;font-size:20px;padding:14px;border:0;border-radius:10px;background:#3a7;color:#fff}
p{color:#999;font-size:14px}
</style></head><body>
<h1>${esc(title)}</h1>
<form id="f"><input id="t" value="${esc(initial)}" autofocus autocomplete="off" autocapitalize="off" spellcheck="false" enterkeyhint="go">
<button type="submit">Go</button></form>
<p id="s">Shows on the TV as you type.</p>
<script>
var k=location.search,t=document.getElementById('t'),s=document.getElementById('s'),w=null;
function send(p){return fetch(p+k,{method:'POST',headers:{'Content-Type':'text/plain'},body:t.value});}
t.addEventListener('input',function(){clearTimeout(w);w=setTimeout(function(){send('/t')},250);});
document.getElementById('f').addEventListener('submit',function(e){e.preventDefault();
send('/go').then(function(r){s.textContent=r.ok?'Sent to the TV. You can close this page.':'This link has expired. Scan the code again.';})
.catch(function(){s.textContent='Couldn\'t reach the TV. Is the phone on the same Wi-Fi?';});});
</script></body></html>"""
    }
}
