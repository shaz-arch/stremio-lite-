package app.stremiolite

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Casting to a smart TV (or any DLNA "media renderer") on the home network: find it, hand it a link, play. */
object Dlna {
    class Renderer(val name: String, val control: String)

    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Blocking, about [ms]: the TVs and boxes that answer. */
    fun discover(ms: Int = 3_000): List<Renderer> {
        val found = LinkedHashMap<String, Renderer>()
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { s ->
            s.soTimeout = 600
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $AVT\r\n\r\n".toByteArray()
            val to = InetAddress.getByName("239.255.255.250")
            repeat(2) { s.send(DatagramPacket(msg, msg.size, to, 1900)) }
            val until = System.currentTimeMillis() + ms
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < until) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                val text = String(p.data, 0, p.length)
                Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
            }
        }
        locations.forEach { loc -> runCatching { describe(loc) }.getOrNull()?.let { found[it.control] = it } }
        return found.values.toList()
    }

    private fun tag(xml: String, name: String) = Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

    private fun describe(location: String): Renderer? {
        val xml = Http.get(location, timeoutMs = 4_000)
        val name = tag(xml, "friendlyName") ?: "TV"
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .firstOrNull { "AVTransport" in (tag(it, "serviceType") ?: "") } ?: return null
        val ctl = tag(service, "controlURL") ?: return null
        val base = tag(xml, "URLBase")?.takeIf { it.startsWith("http") } ?: location
        return Renderer(name.replace("&amp;", "&"), URL(URL(base), ctl).toString())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(r: Renderer, action: String, args: String) {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AVT\"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"
        val c = URL(r.control).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5_000; c.readTimeout = 10_000
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"$AVT#$action\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code >= 400) throw Exception("the TV refused it ($code)")
        } finally { c.disconnect() }
    }

    /** Blocking: plays [url] on [r]. */
    fun play(r: Renderer, url: String, title: String) {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:video/*:*\">${esc(url)}</res></item></DIDL-Lite>"
        runCatching { soap(r, "Stop", "") }
        soap(r, "SetAVTransportURI", "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>")
        soap(r, "Play", "<Speed>1</Speed>")
    }

    fun pause(r: Renderer) = soap(r, "Pause", "")
    fun resume(r: Renderer) = soap(r, "Play", "<Speed>1</Speed>")
    fun stop(r: Renderer) = soap(r, "Stop", "")
}
