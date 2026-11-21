package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Asks every stream add-on that handles this title at once (about 10 seconds each),
 * lists playable links as they arrive, best quality first, and plays the chosen one in VLC.
 *
 * Shown: streams with a web link (what debrid services return).
 * Hidden: plain torrents (no streaming server here) and links that need special
 * headers (an external player can't send them).
 */
object Streams {
    private const val TIMEOUT_MS = 10_000

    class Stream(
        val url: String,
        val addon: String,
        val quality: String,
        val tags: String,
        val size: String,
        val provider: String,
        val cached: Boolean?,
        val rank: Int,
        val order: Int,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun screen(app: MainActivity, type: String, id: String, title: String): Screen {
        val addons = Home.allAddons().filter { it.supports("stream", type, id) }
        val found = mutableListOf<Stream>()
        val notes = mutableListOf<String>()
        var answered = 0
        var hiddenTorrent = 0
        var hiddenHeaders = 0
        var started = false
        val summary = Row("")
        lateinit var screen: Screen

        // Subtitles load alongside the streams; the first in the chosen language is picked.
        val lang = Settings.subtitleLanguage(app)
        var subs: List<Subtitles.Sub> = emptyList()
        var subsDone = false
        var chosenSub: Subtitles.Sub? = null
        var userPicked = false
        lateinit var rebuildRef: () -> Unit
        val subRow = Row("Subtitles", opens = {
            val pick = mutableListOf(Row("Off", action = { chosenSub = null; userPicked = true; app.back(); rebuildRef() }))
            val ordered = subs.sortedBy { if (Subtitles.matches(it, lang)) 0 else 1 }
            ordered.forEach { sub ->
                pick += Row(Subtitles.languageName(sub.lang), sub.addon + if (sub === chosenSub) "  ✓" else "", action = {
                    chosenSub = sub; userPicked = true; app.back(); rebuildRef()
                })
            }
            Screen("Subtitles", pick)
        })

        fun rebuild() {
            val done = answered >= addons.size
            summary.label = when {
                addons.isEmpty() -> "None of your add-ons offers streams for this title."
                !done -> "Asking ${addons.size} add-ons… $answered answered, ${found.size} streams so far"
                found.isEmpty() -> "No playable streams found."
                else -> "${found.size} streams from ${addons.size} add-ons"
            }
            val sorted = found.sortedWith(
                compareBy<Stream>({ if (it.cached == false) 1 else 0 }, { -it.rank }, { it.order })
            )
            subRow.detail = when {
                lang.codes.isEmpty() -> "off (change in Settings)"
                !subsDone && subs.isEmpty() -> "looking…"
                chosenSub != null -> Subtitles.languageName(chosenSub!!.lang) + " · " + chosenSub!!.addon + "  (${subs.size} found)  ►"
                subs.isEmpty() -> "none found"
                else -> "off  (${subs.size} found)  ►"
            }
            val rows = mutableListOf(summary, subRow)
            val hidden = mutableListOf<String>()
            if (hiddenTorrent > 0) hidden += "$hiddenTorrent torrent-only"
            if (hiddenHeaders > 0) hidden += "$hiddenHeaders need special headers"
            if (hidden.isNotEmpty()) rows += Row("Hidden: " + hidden.joinToString(", "))
            notes.forEach { rows += Row(it) }
            rows += Row("────────────────────")
            sorted.forEach { s ->
                val label = listOf(s.quality.padEnd(5), s.tags, s.size, s.provider)
                    .filter { it.isNotBlank() }.joinToString("  ")
                val detail = (if (s.cached == false) "not cached · " else "") + s.addon
                rows += Row(label, detail, action = { Player.play(app, s.url, title, listOfNotNull(chosenSub)) })
            }
            val firstStream = rows.size - sorted.size
            val wasOnHeader = screen.position < firstStream
            screen.rows = rows
            // Keep the cursor on the first stream while results are still arriving.
            if (wasOnHeader && sorted.isNotEmpty()) screen.position = firstStream
            app.refresh(screen)
        }

        rebuildRef = { rebuild() }
        screen = Screen(title, listOf(summary))
        screen.onShow = {
            if (!started) {
                started = true
                rebuild()
                if (lang.codes.isEmpty()) {
                    subsDone = true
                } else {
                    Subtitles.fetch(type, id) { list, done ->
                        subs = list.toList()
                        subsDone = done
                        if (!userPicked) chosenSub = subs.firstOrNull { Subtitles.matches(it, lang) }
                        rebuild()
                    }
                }
                addons.forEach { a ->
                    val url = a.base + "/stream/" + enc(type) + "/" + enc(id) + ".json"
                    Net.async({ JSONObject(Net.get(url, timeoutMs = TIMEOUT_MS)) }) { r ->
                        answered++
                        r.onSuccess { json ->
                            val arr = json.optJSONArray("streams")
                            if (arr != null) for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val hints = o.optJSONObject("behaviorHints")
                                val link = o.optString("url")
                                when {
                                    link.isBlank() || !link.startsWith("http") ->
                                        if (o.has("infoHash")) hiddenTorrent++
                                    hints?.optJSONObject("proxyHeaders") != null -> hiddenHeaders++
                                    else -> found.add(parse(o, link, shortName(a.name), found.size))
                                }
                            }
                        }.onFailure { e ->
                            notes += "${shortName(a.name)}: ${Net.describe(e)}"
                        }
                        rebuild()
                    }
                }
            }
        }
        return screen
    }

    private fun shortName(name: String) = name.substringBefore('|').trim().ifEmpty { name }.take(16)

    private val sizeRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB|GiB|MiB)""", RegexOption.IGNORE_CASE)

    private fun parse(o: JSONObject, url: String, addon: String, order: Int): Stream {
        val name = o.optString("name")
        val text = name + " " + o.optString("title") + " " + o.optString("description")
        val up = text.uppercase()

        val (quality, rank) = when {
            "2160" in up || "4K" in up || "UHD" in up -> "4K" to 4
            "1080" in up -> "1080p" to 3
            "720" in up -> "720p" to 2
            "480" in up || "SD" in up.split(Regex("[^A-Z0-9]")) -> "480p" to 1
            else -> "?" to 0
        }
        val tags = buildList {
            if (Regex("""\bDV\b|DOLBY ?VISION""").containsMatchIn(up)) add("DV")
            if ("HDR" in up) add("HDR")
            if ("HEVC" in up || "X265" in up || "H265" in up) add("HEVC")
            when {
                "REMUX" in up -> add("REMUX")
                "BLURAY" in up || "BLU-RAY" in up || "BDRIP" in up -> add("BluRay")
                "WEB-DL" in up || "WEBDL" in up -> add("WEB-DL")
                "WEBRIP" in up -> add("WEBRip")
                "HDTV" in up -> add("HDTV")
                Regex("""\b(CAM|HDCAM|TS|TELESYNC)\b""").containsMatchIn(up) -> add("CAM")
            }
        }.joinToString(" ")

        val bytes = o.optJSONObject("behaviorHints")?.optLong("videoSize", 0L) ?: 0L
        val size = when {
            bytes > 0 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            else -> sizeRegex.find(text)?.let { m -> "${m.groupValues[1].replace(',', '.')} ${m.groupValues[2].uppercase().replace("I", "")}" } ?: ""
        }

        // Debrid tag such as [RD+] / [TB+] (cached) or [TB download] (not cached yet).
        val bracket = Regex("""\[([A-Za-z]{2,4})(\+| download)?[^\]]*]""").find(name)
        val provider = bracket?.let { "[" + it.groupValues[1].uppercase() + (if (it.groupValues[2] == "+") "+" else "") + "]" } ?: ""
        val cached = when {
            bracket?.groupValues?.get(2) == "+" || "⚡" in name -> true
            bracket?.groupValues?.get(2) == " download" || "⏳" in name -> false
            else -> null
        }
        return Stream(url, addon, quality, tags, size, provider, cached, rank, order)
    }
}
