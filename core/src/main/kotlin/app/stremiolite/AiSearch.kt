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
    /** Google's always-current Flash model; older AIOMetadata versions default to retired ones. */
    const val MODEL = "gemini-flash-latest"
    val RETIRED = setOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-2.5-flash-lite-preview-09-2025", "gemini-3-flash", "gemini-3.1-pro", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")
    private const val NOTICE = "aiom.error."

    /** AIOMetadata's stand-in title carrying an error ("aiom.error.{json}"): its reason, or null. */
    fun notice(m: Meta): String? {
        if (!m.id.startsWith(NOTICE)) return null
        val o = runCatching { JSONObject(java.net.URLDecoder.decode(m.id.removePrefix(NOTICE), "UTF-8")) }.getOrNull()
        return listOfNotNull(o?.optString("title")?.takeIf { it.isNotBlank() }, o?.optString("description")?.takeIf { it.isNotBlank() })
            .joinToString(": ").ifEmpty { m.name }
    }

    fun isAiCatalog(c: Addon.Catalog) = c.id == CATALOG

    /** The AIOMetadata installs to ask (each different install once). */
    fun installs(addons: List<Addon>): List<Addon> = addons.filter { !it.isLocal && AioRows.isAio(it) }.distinctBy { it.base }

    /** Asks each install (blocking); titles from the first that answers, in AI's order. Throws if none could. */
    fun ask(addons: List<Addon>, description: String): List<Pair<Addon, Meta>> {
        val q = URLEncoder.encode("$TRIGGER ${description.trim()}", "UTF-8").replace("+", "%20")
        var lastError: String? = null
        for (a in installs(addons)) {
            val type = a.catalogs.firstOrNull { isAiCatalog(it) }?.type ?: "other"
            val got = runCatching { Http.get("${a.base}/catalog/$type/$CATALOG/search=$q.json", timeoutMs = 60_000, allowErrors = true) }
            val text = got.getOrNull()
            if (text == null) { lastError = got.exceptionOrNull()?.message; continue }
            val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
            o.optString("error").takeIf { it.isNotBlank() }?.let { lastError = it }
            val metas = MetaParse.parse(text)
            val reason = metas.firstNotNullOfOrNull { notice(it) }
            if (reason != null) { lastError = reason; continue }
            if (metas.isNotEmpty()) return metas.map { a to it }
        }
        if (installs(addons).isEmpty()) throw IllegalStateException("no AIOMetadata rows on this account")
        throw IllegalStateException(lastError?.let { "AI search didn't answer: $it" } ?: "no answer from AI Search. Use 'Check my AI setup' below to find out why.")
    }

    // ---- checking the setup -----------------------------------------------------------------

    /** Asks Google directly whether [key] works with [model] (blocking): null if it does, else Google's reason. */
    fun testKey(key: String, model: String = MODEL): String? {
        val body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "Reply with OK")))))
        val (code, text) = Http.sendJson("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", "POST", body.toString(), 30_000,
            mapOf("x-goog-api-key" to key.trim()))
        if (code in 200..299) return null
        val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
        return (msg?.takeIf { it.isNotBlank() } ?: "Google answered $code").take(200)
    }

    /** What a rows config says about AI search, line by line, and whether it's ready. */
    fun describe(config: JSONObject, myKey: String): Pair<Boolean, List<String>> {
        val key = config.optJSONObject("apiKeys")?.optString("gemini").orEmpty()
        val s = config.optJSONObject("search")
        val on = s?.optBoolean("ai_enabled") == true
        val trigger = s?.optString("ai_trigger_keyword").orEmpty()
        val provider = s?.optString("ai_provider").orEmpty().ifBlank { "gemini" }
        val model = s?.optString("ai_model").orEmpty()
        val lines = listOf(
            "Gemini key: " + when { key.isBlank() -> "missing"; key.trim() == myKey.trim() -> "yours (…${key.takeLast(4)})"; else -> "a different one (…${key.takeLast(4)})" },
            "AI search: " + if (on) "on" else "off",
            "Trigger word: " + trigger.ifBlank { "none" } + if (trigger.isNotBlank() && !trigger.equals(TRIGGER, true)) " (Ask AI needs \"$TRIGGER\")" else "",
            "Provider: $provider",
            "Model: " + model.ifBlank { "server's default" } + if (model in RETIRED) " (retired by Google)" else "",
        )
        val ready = key.isNotBlank() && on && (trigger.isBlank() || trigger.equals(TRIGGER, true)) && provider == "gemini" && model !in RETIRED && model.isNotBlank()
        return ready to lines
    }

}
