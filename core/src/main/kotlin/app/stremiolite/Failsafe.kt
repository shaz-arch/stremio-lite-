package app.stremiolite

/**
 * Fail-safe streams: Torrentio with your own debrid key, straight to your debrid service and not
 * through ElfHosted's AIOStreams, so it still answers if that's down. Stremonino only asks it when
 * AIOStreams gives nothing playable ([StreamParse.tiers]).
 */
object Failsafe {
    const val TORRENTIO = "https://torrentio.strem.fun"

    /** Torrentio's own names for the debrid services (the same as ours). */
    private val SUPPORTED = setOf("torbox", "realdebrid", "alldebrid", "premiumize", "debridlink")

    fun isTorrentio(a: Addon) = "torrentio" in (a.id + " " + a.name + " " + a.transportUrl).lowercase()

    /** Your first debrid service Torrentio can use, or null. */
    fun serviceFor(keys: List<Pair<String, String>>) = keys.firstOrNull { it.first in SUPPORTED && it.second.isNotBlank() }

    /** Torrentio's install link: no 4K, 3D, cams or screeners; best quality, then size; no download links or catalogue rows. */
    fun torrentioUrl(keys: List<Pair<String, String>>): String? {
        val (id, key) = serviceFor(keys) ?: return null
        return "$TORRENTIO/sort=qualitysize|qualityfilter=4k,threed,cam,scr|debridoptions=nodownloadlinks,nocatalog|$id=${key.trim()}/manifest.json"
    }
}
