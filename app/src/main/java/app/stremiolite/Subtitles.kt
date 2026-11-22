package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/** Subtitle links from the account's subtitle add-ons, for the title being played. */
object Subtitles {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** One round of asking the subtitle add-ons, shared by everything that needs this title's subtitles. */
    class Fetch(type: String, id: String) {
        val started = System.currentTimeMillis()
        val found = mutableListOf<Sub>()
        var done = false
        private val listeners = mutableListOf<() -> Unit>()

        init {
            val addons = Home.allAddons().filter { it.supports("subtitles", type, id) }
            var answered = 0
            if (addons.isEmpty()) {
                // Report back after the caller has finished setting up, like the network answers do.
                Net.later(0, Runnable { done = true; tell() })
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
                    done = answered >= addons.size
                    tell()
                }
            }
        }

        private fun tell() = listeners.toList().forEach { it() }

        fun listen(l: () -> Unit): Fetch {
            listeners += l
            return this
        }
    }

    private val cache = LinkedHashMap<String, Fetch>()

    /** A recent fetch for this title (from fetching early), or a new one. */
    fun get(app: MainActivity, type: String, id: String): Fetch {
        val key = "$type/$id"
        cache[key]?.let { if (System.currentTimeMillis() - it.started < 10 * 60_000L) return it }
        return Fetch(type, id).also {
            cache[key] = it
            while (cache.size > 4) cache.remove(cache.keys.first())
        }
    }

    fun prefetch(app: MainActivity, type: String, id: String) {
        if (Settings.subtitleLanguage(app).codes.isNotEmpty()) get(app, type, id)
    }

    fun matches(sub: Sub, lang: Language) = Languages.matches(sub, lang)

    fun languageName(code: String): String = Languages.name(code)
}
