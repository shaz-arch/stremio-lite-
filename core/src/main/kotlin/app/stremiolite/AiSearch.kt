package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/**
 * "Ask AI": AIOMetadata's AI Search (Gemini, with your own key in your rows' config) turns a
 * description ("90s heist films with a twist") into real titles. Asked only on purpose: the
 * description goes to Google through your AIOMetadata server.
 */
object AiSearch {
    const val CATALOG = "gemini.search"
    /** The word that tells AIOMetadata a search is for AI (set with the key). */
    const val TRIGGER = "ai"

    fun isAiCatalog(c: Addon.Catalog) = c.id == CATALOG

    /** The AIOMetadata installs to ask (each different install once). */
    fun installs(addons: List<Addon>): List<Addon> = addons.filter { !it.isLocal && AioRows.isAio(it) }.distinctBy { it.base }

    /** Asks each install (blocking); titles from the first that answers, in AI's order. Throws if none could. */
    fun ask(addons: List<Addon>, description: String): List<Pair<Addon, Meta>> {
        val q = URLEncoder.encode("$TRIGGER ${description.trim()}", "UTF-8").replace("+", "%20")
        var lastError: String? = null
        for (a in installs(addons)) {
            val type = a.catalogs.firstOrNull { isAiCatalog(it) }?.type ?: "other"
            val text = runCatching { Http.get("${a.base}/catalog/$type/$CATALOG/search=$q.json", timeoutMs = 60_000, allowErrors = true) }
                .getOrElse { lastError = it.message; continue }
            val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
            o.optString("error").takeIf { it.isNotBlank() }?.let { lastError = it }
            val metas = MetaParse.parse(text)
            if (metas.isNotEmpty()) return metas.map { a to it }
        }
        if (installs(addons).isEmpty()) throw IllegalStateException("no AIOMetadata rows on this account")
        throw IllegalStateException(lastError?.let { "AI search didn't answer: $it" } ?: "nothing found: is your Gemini key in your rows? (Settings › Your keys)")
    }
}
