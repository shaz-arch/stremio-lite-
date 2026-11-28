package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder

/**
 * One round of asking a title's stream add-ons, shared by every front-end. Every add-on that
 * handles the title is asked at once (about 10 seconds each); [listen]ers run on the screen's
 * thread after each answer.
 *
 * With an AIOStreams on the account it's asked first, and the fail-safes (Torrentio and the like)
 * only when it gives nothing playable ([StreamParse.tiers]). If it gave nothing a few minutes ago,
 * the fail-safes are asked straight away ([StreamParse.mainDown]).
 *
 * Kept: streams with a web link (what debrid services return). Counted but hidden: plain torrents
 * (there's no streaming server here). Web pages ("open in browser", ratings pages) go to [links].
 */
class StreamFetch(
    private val type: String,
    private val id: String,
    available: List<Addon>,
    /** A YouTube video id as a link this front-end's player can open, or null to leave it out. */
    private val youTube: (String) -> String? = { YouTube.watch(it) },
) {
    val started = System.currentTimeMillis()
    private val listeners = mutableListOf<() -> Unit>()
    private val tiers = StreamParse.tiers(available.filter { it.supports("stream", type, id) })
    private val skipMain = tiers.second.isNotEmpty() && StreamParse.mainDown()

    /** The add-ons asked so far: AIOStreams, plus the fail-safes once they're needed. */
    val addons: MutableList<Addon> = (if (skipMain) tiers.second else tiers.first).toMutableList()
    val found = mutableListOf<Stream>()
    /** Add-ons that failed to answer, and why. */
    val notes = mutableListOf<String>()
    /** (label, link) for web pages rather than videos: shown as links, never played. */
    val links = mutableListOf<Pair<String, String>>()
    var answered = 0
        private set
    var hiddenTorrent = 0
        private set
    /** AIOStreams gave nothing playable, so the fail-safes were asked. */
    var usingFailsafe = skipMain
        private set
    val done: Boolean get() = answered >= addons.size
    private var timed = false

    fun listen(l: () -> Unit): StreamFetch {
        listeners += l
        return this
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** "AIOStreams gave nothing: showing your fail-safes (Torrentio)", or null when they weren't needed. */
    fun failsafeLine(): String? = if (!usingFailsafe) null
        else tiers.first.joinToString(", ") { StreamParse.shortName(it.name) } +
            (if (skipMain) " gave nothing a few minutes ago" else " gave nothing") +
            ": showing your fail-safes (" + tiers.second.joinToString(", ") { StreamParse.shortName(it.name) } + ")"

    fun hiddenLine(): String? = if (hiddenTorrent > 0) "Hidden: $hiddenTorrent torrent-only" else null

    private fun ask(list: List<Addon>) {
        list.forEach { a ->
            val url = a.base + "/stream/" + enc(type) + "/" + enc(id) + ".json"
            Platform.async.background({ JSONObject(Http.get(url, timeoutMs = TIMEOUT_MS)) }) { r ->
                answered++
                r.onSuccess { json -> take(json, a) }
                    .onFailure { e -> notes += "${StreamParse.shortName(a.name)}: ${Http.describe(e)}" }
                // AIOStreams has answered with nothing playable: ask the fail-safes now.
                if (done && !usingFailsafe && tiers.second.isNotEmpty()) {
                    StreamParse.noteMain(StreamParse.hasPlayable(found))
                    if (!StreamParse.hasPlayable(found)) {
                        usingFailsafe = true
                        addons += tiers.second
                        ask(tiers.second)
                    }
                }
                if (done && !timed) {
                    timed = true
                    Timings.record("Finding streams", System.currentTimeMillis() - started)
                }
                changed()
            }
        }
    }

    private fun take(json: JSONObject, a: Addon) {
        val arr = json.optJSONArray("streams") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val link = o.optString("url")
            val web = StreamParse.webLink(o, a)
            val yt = o.optString("ytId")
            when {
                web != null -> if (links.none { it.second == web.second }) links += web
                // A YouTube video by its id: played however this front-end can (or left out).
                link.isBlank() && yt.isNotBlank() -> youTube(yt)?.let {
                    StreamParse.addUnique(found, StreamParse.parse(o, it, StreamParse.shortName(a.name), found.size))
                }
                !link.startsWith("http") -> if (o.has("infoHash")) hiddenTorrent++
                else -> StreamParse.addUnique(found, StreamParse.parse(o, link, StreamParse.shortName(a.name), found.size))
            }
        }
    }

    init {
        ask(addons)
        if (addons.isEmpty()) Platform.async.later(0) { changed() }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
