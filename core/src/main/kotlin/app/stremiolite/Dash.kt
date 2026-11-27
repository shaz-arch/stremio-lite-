package app.stremiolite

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Rewrites a DASH manifest (.mpd) so every address in it goes through [HeaderBridge]: each BaseURL is
 * made absolute (following its parents) and sent through us, an MPD-level BaseURL is added if there
 * isn't one, and absolute segment, initialisation, index and refresh addresses go through us too.
 * Templates ($Number$, $Time$…) are left for the player to fill in.
 */
object Dash {
    private val URL_ATTRS = mapOf(
        "SegmentTemplate" to listOf("media", "initialization", "index"),
        "SegmentURL" to listOf("media", "index"),
        "Initialization" to listOf("sourceURL"),
        "RepresentationIndex" to listOf("sourceURL"),
    )

    fun rewrite(xml: String, manifestUrl: String, via: (String) -> String): String {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))
        val root = doc.documentElement
        val ns = root.namespaceURI

        fun kids(e: Element, name: String): List<Element> {
            val out = mutableListOf<Element>()
            var n: Node? = e.firstChild
            while (n != null) { if (n is Element && n.localName == name) out += n; n = n.nextSibling }
            return out
        }
        fun abs(base: String, u: String) = runCatching { URL(URL(base), u.trim()).toString() }.getOrDefault(u)

        // An MPD-level base, so relative addresses resolve to us rather than to the manifest's own link.
        if (kids(root, "BaseURL").isEmpty()) {
            val b = if (ns != null) doc.createElementNS(ns, "BaseURL") else doc.createElement("BaseURL")
            b.textContent = manifestUrl.substringBefore('?').substringBeforeLast('/') + "/"
            root.insertBefore(b, root.firstChild)
        }

        fun walk(e: Element, base: String) {
            // This level's base: its own BaseURL (resolved against the parent's), else the parent's.
            val own = kids(e, "BaseURL")
            var here = base
            own.forEachIndexed { i, b ->
                val a = abs(base, b.textContent)
                if (i == 0) here = a
                b.textContent = via(a)
            }
            URL_ATTRS[e.localName]?.forEach { attr ->
                val v = e.getAttribute(attr)
                if (v.isNotEmpty() && (v.startsWith("http://") || v.startsWith("https://"))) e.setAttribute(attr, via(v))
            }
            if (e.localName == "Location") e.textContent = via(abs(base, e.textContent))
            var n: Node? = e.firstChild
            while (n != null) { if (n is Element && n.localName != "BaseURL") walk(n, here); n = n.nextSibling }
        }
        walk(root, manifestUrl)

        val w = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
            .transform(DOMSource(doc), StreamResult(w))
        return w.toString()
    }
}
