package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

class Stream(
    val url: String,
    val addon: String,
    val quality: String,
    val tags: String,
    val size: String,
    val sizeMb: Double,
    val provider: String,
    val cached: Boolean?,
    val rank: Int,
    val order: Int,
    /** Everything the add-on said about it, upper-cased, for scoring. */
    val text: String,
) {
    val summary: String
        get() = listOf(quality.padEnd(5), tags, size, provider).filter { it.isNotBlank() }.joinToString("  ")

    /** The release group (e.g. "NTB" in "Show.S01E02.1080p.WEB-DL-NTB.mkv"), if one can be seen. */
    val group: String by lazy {
        val file = Regex("""-([A-Z0-9]{2,15})\.(MKV|MP4|AVI|M4V|TS)\b""").findAll(text).lastOrNull()?.groupValues?.get(1)
        file ?: Regex("""-([A-Z0-9]{2,15})(?=\s|$|\])""").findAll(text).lastOrNull()?.groupValues?.get(1) ?: ""
    }

    /** What a later episode should try to match. */
    val source: String get() = tags.split(' ').firstOrNull { it in setOf("REMUX", "BluRay", "WEB-DL", "WEBRip", "HDTV") } ?: ""
}

/** The release last played for each show, so the next episode keeps the same look and timing. */
class Pref(val group: String, val quality: String, val source: String)


/** Reads an add-on's stream entries into [Stream]s, and asks add-ons for streams directly. */
object StreamParse {
    private const val TIMEOUT_MS = 10_000

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun shortName(name: String) = name.substringBefore('|').trim().ifEmpty { name }.take(16)

    private val sizeRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB|GiB|MiB)""", RegexOption.IGNORE_CASE)

    fun parse(o: JSONObject, url: String, addon: String, order: Int): Stream {
        val name = o.optString("name")
        val hints = o.optJSONObject("behaviorHints")
        val text = name + " " + o.optString("title") + " " + o.optString("description") + " " + (hints?.optString("filename") ?: "")
        val up = text.uppercase()

        val (quality, rank) = when {
            "2160" in up || "4K" in up || "UHD" in up -> "4K" to 4
            "1080" in up -> "1080p" to 3
            "720" in up -> "720p" to 2
            "480" in up || "SD" in up.split(Regex("[^A-Z0-9]")) -> "480p" to 1
            else -> "?" to 0
        }
        val tags = buildList {
            if (Regex("""\bDV\b|DOLBY ?VISION|\bDOVI\b""").containsMatchIn(up)) add("DV")
            if ("HDR" in up) add("HDR")
            if ("HEVC" in up || "X265" in up || "H265" in up || "H.265" in up) add("HEVC")
            when {
                "REMUX" in up -> add("REMUX")
                "BLURAY" in up || "BLU-RAY" in up || "BDRIP" in up || "BRRIP" in up -> add("BluRay")
                "WEB-DL" in up || "WEBDL" in up -> add("WEB-DL")
                "WEBRIP" in up -> add("WEBRip")
                "HDTV" in up -> add("HDTV")
                Best.isCam(up) -> add("CAM")
            }
        }.joinToString(" ")

        val bytes = hints?.optLong("videoSize", 0L) ?: 0L
        val sizeMb = when {
            bytes > 0 -> bytes / 1_048_576.0
            else -> sizeRegex.find(text)?.let { m ->
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                if (m.groupValues[2].uppercase().startsWith("G")) v * 1024 else v
            } ?: 0.0
        }
        val size = when {
            sizeMb >= 1024 -> "%.1f GB".format(sizeMb / 1024)
            sizeMb > 0 -> "%.0f MB".format(sizeMb)
            else -> ""
        }

        // Debrid tag such as [RD+] / [TB+] (cached) or [TB download] (not cached yet).
        val bracket = Regex("""\[([A-Za-z]{2,4})(\+| download)?[^\]]*]""").find(name)
        val provider = bracket?.let { "[" + it.groupValues[1].uppercase() + (if (it.groupValues[2] == "+") "+" else "") + "]" } ?: ""
        val lowerName = name.lowercase()
        val cached = when {
            bracket?.groupValues?.get(2) == "+" || "⚡" in name -> true
            bracket?.groupValues?.get(2) == " download" || "⏳" in name || "uncached" in lowerName -> false
            else -> null
        }
        return Stream(url, addon, quality, tags, size, sizeMb, provider, cached, rank, order, up)
    }
    /**
     * Asks the stream add-ons directly (blocking, for background checks) whether a stream that
     * Play best would choose exists right now.
     */
    fun availableNow(addons: List<Addon>, type: String, id: String): Boolean {
        val found = mutableListOf<Stream>()
        for (a in addons.filter { it.supports("stream", type, id) }) {
            val url = a.base + "/stream/" + enc(type) + "/" + enc(id) + ".json"
            val arr = runCatching { JSONObject(Http.get(url, timeoutMs = TIMEOUT_MS)).optJSONArray("streams") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = o.optString("url")
                if (link.startsWith("http") && o.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders") == null) {
                    found.add(parse(o, link, shortName(a.name), found.size))
                }
            }
        }
        return Best.pick(found, if (type == "movie") 110.0 else 45.0) != null
    }

}

/**
 * Scores streams for a 1080p stick playing through VLC over Wi-Fi, after Stremio Origin's approach:
 * read each stream's own text, leave out what won't play, prefer the best picture the device shows,
 * a good source, English (or multi-language) audio, and a bitrate Wi-Fi carries comfortably.
 */
object Best {
    // Bitrate for an old 1080p stick on Wi-Fi: 6-15 Mbps looks as good as it gets on its screen and
    // streams comfortably; above 15 is marked down progressively, above 25 heavily.
    private const val SWEET_LOW = 6.0
    private const val SWEET_HIGH = 15.0
    private const val HEAVY = 25.0

    private val camWords = Regex("""\b(CAM|CAMRIP|HDCAM|TS|HDTS|TELESYNC|TC|TELECINE|SCR|SCREENER|DVDSCR)\b""")
    fun isCam(up: String) = camWords.containsMatchIn(up)

    private val englishTags = Regex("""\b(ENG|ENGLISH)\b|🇬🇧|🇺🇸""")
    private val multiTags = Regex("""\b(MULTI|DUAL|DUAL[ -]AUDIO)\b""")
    private val otherTags = Regex(
        """\b(ITA|ITALIAN|FRE|FRENCH|VFF|VFQ|VF|TRUEFRENCH|SPA|SPANISH|ESP|CASTELLANO|LATINO|GER|GERMAN|DEU|RUS|RUSSIAN|""" +
            """POR|PORTUGUESE|PT-BR|POL|POLISH|TUR|TURKISH|HIN|HINDI|TAM|TAMIL|TEL|TELUGU|KOR|KOREAN|JPN|JAPANESE|""" +
            """CHI|CHINESE|ARA|ARABIC|DUT|DUTCH|NLD)\b|🇮🇹|🇫🇷|🇪🇸|🇲🇽|🇩🇪|🇷🇺|🇵🇹|🇧🇷|🇵🇱|🇹🇷|🇮🇳|🇰🇷|🇯🇵|🇨🇳|🇳🇱"""
    )

    /** "2h37min", "1h 52m", "45min", "110" → minutes. */
    fun minutes(runtime: String): Double? {
        val t = runtime.lowercase()
        val h = Regex("""(\d+)\s*h""").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val m = Regex("""(\d+)\s*m""").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: if (h == 0.0) t.trim().toDoubleOrNull() else null
        val total = h * 60 + (m ?: 0.0)
        return if (total in 5.0..600.0) total else null
    }

    /** Why [score] leaves a stream out (for the "Why this stream?" screen). */
    fun whyOut(s: Stream): String = when {
        isCam(s.text) -> "cam copy"
        s.cached == false -> "not cached yet"
        s.rank >= 4 -> "4K: above this device"
        Regex("""\bHI10P?\b""").containsMatchIn(s.text) -> "10-bit H.264"
        else -> "left out"
    }

    /** A score, or null when the stream shouldn't be auto-played at all. */
    /**
     * A nudge towards the release the last episode used: enough to choose it over a similar
     * stream, never enough to pick a worse resolution or anything score() rules out.
     */
    fun match(s: Stream, prefer: Pref?): Double {
        if (prefer == null) return 0.0
        var b = 0.0
        if (prefer.group.isNotEmpty() && s.group == prefer.group) b += 40.0
        if (s.quality == prefer.quality) b += 15.0
        if (prefer.source.isNotEmpty() && s.source == prefer.source) b += 10.0
        return b
    }

    fun score(s: Stream, minutes: Double): Double? {
        val up = s.text
        if (isCam(up)) return null
        if (s.cached == false) return null                            // debrid file not ready yet
        if (s.rank >= 4) return null                                  // 4K: above a 1080p stick
        if (Regex("""\bHI10P?\b""").containsMatchIn(up)) return null  // 10-bit H.264: no hardware decoder

        var sc = when (s.rank) { 3 -> 300.0; 2 -> 200.0; 1 -> 80.0; else -> 160.0 }
        sc += when {
            "REMUX" in s.tags -> 10.0          // best source, but huge files for no visible gain on this stick
            "BluRay" in s.tags -> 24.0
            "WEB-DL" in s.tags || "WEBRip" in s.tags -> 20.0
            "HDTV" in s.tags -> 4.0
            else -> 0.0
        }
        if ("DV" in s.tags) sc -= if ("HDR" in s.tags) 20.0 else 60.0   // Dolby Vision-only files show wrong colours
        else if ("HDR" in s.tags) sc -= 10.0                             // tone-mapped on an SDR screen
        if (Regex("""\bAV1\b""").containsMatchIn(up)) sc -= 200.0
        sc += when {
            "TRUEHD" in up || Regex("""\bDTS""").containsMatchIn(up) -> -20.0
            "EAC3" in up || "DDP" in up || "DD+" in up -> 14.0
            "AC3" in up || Regex("""\bDD\b""").containsMatchIn(up) -> 12.0
            "AAC" in up -> 8.0
            else -> 0.0
        }

        val eng = englishTags.containsMatchIn(up)
        val multi = multiTags.containsMatchIn(up)
        val other = otherTags.containsMatchIn(up)
        sc += when {
            eng -> 30.0
            multi -> -10.0
            other -> -160.0       // only another language named: last resort
            else -> 0.0           // nothing named: usually the original
        }

        if (s.cached == true) sc += 10.0
        if (s.sizeMb > 0) {
            val mbps = s.sizeMb * 8 / (minutes * 60)
            sc += when {
                mbps < SWEET_LOW -> mbps / SWEET_LOW * 14            // small files: a little softer
                mbps <= SWEET_HIGH -> 14.0 + (mbps - SWEET_LOW) / (SWEET_HIGH - SWEET_LOW) * 6  // the sweet spot
                mbps <= HEAVY -> 20.0 - (mbps - SWEET_HIGH) * 3      // 15-25: fading out
                else -> -10.0 - (mbps - HEAVY) * 6                    // over 25: likely to buffer
            }
        }
        return sc
    }

    fun pick(streams: List<Stream>, minutes: Double, prefer: Pref? = null): Stream? =
        streams.mapNotNull { s -> score(s, minutes)?.let { s to it + match(s, prefer) } }
            .maxWithOrNull(compareBy<Pair<Stream, Double>>({ it.second }, { -it.first.order }))
            ?.first
}
