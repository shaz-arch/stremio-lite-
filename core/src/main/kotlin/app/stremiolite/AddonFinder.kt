package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Finding new add-ons: the add-on lists that your add-ons offer (Stremio's own Community list comes
 * with Cinemeta; installing the stremio-addons.net add-on adds theirs), each entry with what it is and
 * whether it needs setting up on its own page first.
 */
object AddonFinder {
    class Source(val addon: Addon, val catalog: Addon.Catalog) {
        val label get() = "${StreamParse.shortName(addon.name)}: ${catalog.name}" + if (catalog.type != "all" && catalog.type.isNotEmpty()) " (${catalog.type})" else ""
    }

    class Entry(
        val transportUrl: String, val id: String, val name: String, val version: String, val description: String,
        val types: List<String>, val resources: List<String>, val configurable: Boolean, val configurationRequired: Boolean,
    ) {
        /** Where it's set up: its own configure page. */
        val configureUrl: String get() = transportUrl.removeSuffix("/manifest.json").trimEnd('/') + "/configure"
        /** "Streams, rows · films, shows". */
        val what: String get() {
            val r = resources.mapNotNull { when (it) { "stream" -> "streams"; "catalog" -> "rows"; "meta" -> "details"; "subtitles" -> "subtitles"; "addon_catalog" -> "add-on lists"; else -> null } }.distinct()
            val t = types.mapNotNull { when (it) { "movie" -> "films"; "series" -> "shows"; "tv" -> "live TV"; "channel" -> "channels"; "anime" -> "anime"; "other" -> null; else -> it } }.distinct()
            return listOf(r.joinToString(", "), t.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    fun sources(addons: List<Addon>): List<Source> =
        addons.flatMap { a -> a.addonCatalogs.map { Source(a, it) } }.distinctBy { it.addon.transportUrl + it.catalog.type + it.catalog.id }

    /** Blocking. */
    fun list(s: Source): List<Entry> {
        fun enc(x: String) = URLEncoder.encode(x, "UTF-8").replace("+", "%20")
        val o = JSONObject(Http.get(s.addon.base + "/addon_catalog/" + enc(s.catalog.type) + "/" + enc(s.catalog.id) + ".json", timeoutMs = 20_000))
        val arr = o.optJSONArray("addons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { e ->
            val m = e.optJSONObject("manifest") ?: return@mapNotNull null
            val url = e.optString("transportUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            fun strs(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).map { i -> a.opt(i).let { v -> if (v is JSONObject) v.optString("name") else v.toString() } } }.orEmpty()
            val hints = m.optJSONObject("behaviorHints")
            Entry(url, m.optString("id"), m.optString("name").ifEmpty { m.optString("id") }, m.optString("version"),
                m.optString("description").replace(Regex("\\s+"), " ").trim(), strs("types"), strs("resources"),
                hints?.optBoolean("configurable") == true, hints?.optBoolean("configurationRequired") == true)
        }.distinctBy { it.transportUrl }
    }

    fun matches(e: Entry, q: String) = q.isBlank() || q.lowercase().split(' ').filter { it.isNotEmpty() }.all { w -> w in (e.name + " " + e.description).lowercase() }
}
