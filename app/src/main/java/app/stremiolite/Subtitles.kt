package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/** Subtitle links from the account's subtitle add-ons, for the title being played. */
object Subtitles {
    class Sub(val url: String, val lang: String, val addon: String)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Asks every subtitle add-on at once; [update] runs each time one answers, [done] when all have. */
    fun fetch(type: String, id: String, update: (List<Sub>, Boolean) -> Unit) {
        val addons = Home.allAddons().filter { it.supports("subtitles", type, id) }
        val found = mutableListOf<Sub>()
        var answered = 0
        if (addons.isEmpty()) {
            update(found, true)
            return
        }
        addons.forEach { a ->
            val url = a.base + "/subtitles/" + enc(type) + "/" + enc(id) + ".json"
            Net.async({ JSONObject(Net.get(url, timeoutMs = 8_000)) }) { r ->
                answered++
                r.onSuccess { json ->
                    val arr = json.optJSONArray("subtitles")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val link = o.optString("url")
                        if (link.startsWith("http")) found += Sub(link, o.optString("lang"), a.name.substringBefore('|').trim().take(16))
                    }
                }
                update(found, answered >= addons.size)
            }
        }
    }

    fun matches(sub: Sub, lang: Settings.Language) =
        lang.codes.any { sub.lang.equals(it, ignoreCase = true) } || sub.lang.equals(lang.name, ignoreCase = true)

    fun languageName(code: String): String =
        Settings.languages.firstOrNull { l -> l.codes.any { it.equals(code, true) } }?.name ?: code.ifEmpty { "?" }
}
