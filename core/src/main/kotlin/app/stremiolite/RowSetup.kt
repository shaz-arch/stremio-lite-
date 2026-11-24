package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Set up rows": a full, ready-sorted catalogue of rows. You pick a region, your streaming
 * services and the categories you want; the app creates an AIOMetadata install with just those
 * switched on (or switches them on and off in the one you have).
 *
 * Rows are grouped by their names: "Top 10 > Movies: Netflix" is category "Top 10", service
 * "Netflix"; "Genres > Movies: Horror" is category "Genres"; a name with no prefix is a front-page
 * row and is always on.
 */
object RowSetup {
    /** ElfHosted's free public AIOMetadata server (it supplies a TMDB key, so none is needed). */
    const val SERVER = "https://aiometadata.elfhosted.com"

    enum class Region(val label: String, val code: String, val flix: String, val language: String, val timezone: String) {
        UK("UK", "GB", "gb", "en-GB", "Europe/London"),
        US("US", "US", "us", "en-US", "America/New_York"),
    }

    /** Services offered for a region (UK TV is the BBC, ITV, Channel 4, Channel 5 and Sky rows). */
    fun services(r: Region): List<String> = when (r) {
        Region.UK -> listOf("Netflix", "Prime Video", "Apple TV+", "Disney+", "HBO Max", "Paramount+", "UK TV")
        Region.US -> listOf("Netflix", "Prime Video", "Apple TV+", "Disney+", "HBO Max", "Paramount+", "Hulu", "Peacock", "UK TV")
    }

    /** Services ticked to start with. */
    fun defaultServices(r: Region): Set<String> = when (r) {
        Region.UK -> setOf("Netflix", "Prime Video", "Apple TV+", "Disney+", "UK TV")
        Region.US -> setOf("Netflix", "Prime Video", "Apple TV+", "Disney+", "Hulu")
    }

    /** Categories in the catalogue, in the order shown; the first ones are ticked to start with. */
    val categories = listOf(
        "Top 10", "Streaming", "Genres", "Kids & Family", "Charts", "Decades", "Documentaries", "Collections",
        "Themes", "Sitcoms", "Studios", "Directors", "Anime", "Indian", "Pakistani",
    )
    val defaultCategories = setOf("Top 10", "Streaming", "Genres", "Kids & Family", "Charts", "Decades", "Documentaries", "Collections")

    /** How many rows to start from: Minimal trims Genres to the core ones; Standard is the full layout. */
    enum class Size(val label: String, val categories: Set<String>) {
        MINIMAL("Minimal", setOf("Top 10", "Streaming", "Genres", "Kids & Family")),
        STANDARD("Standard", setOf("Top 10", "Streaming", "Genres", "Kids & Family", "Charts", "Decades", "Documentaries", "Collections",
            "Themes", "Sitcoms", "Studios", "Directors")),
    }

    /** The genres Minimal keeps (for Movies and Shows). */
    val coreGenres = setOf("Action", "Comedy", "Drama", "Horror", "Thriller", "Sci-Fi", "Crime", "Romance", "Animation", "Mystery")

    class Picks(var region: Region, val services: MutableSet<String>, val categories: MutableSet<String>, var size: Size = Size.STANDARD) {
        /** Optional keys: your own TMDB key, and an MDBList key (which switches on the MDBList rows). */
        var tmdbKey: String = ""
        var mdblistKey: String = ""
    }

    /** Where to get each key (shown as a link on phones and Linux, a QR code on the stick). */
    const val TMDB_KEY_PAGE = "https://www.themoviedb.org/settings/api"
    const val MDBLIST_KEY_PAGE = "https://mdblist.com/preferences/"

    fun defaults(r: Region = Region.UK) = Picks(r, defaultServices(r).toMutableSet(), Size.STANDARD.categories.toMutableSet(), Size.STANDARD)

    /** Switches to [size]: its categories are ticked, the rest unticked. */
    fun resize(p: Picks, size: Size) {
        p.size = size
        p.categories.clear(); p.categories += size.categories
    }

    /** Rows that would be on with these picks (front page included, merged parts not counted). */
    fun count(p: Picks): Int = lite(p.region).count {
        !it.part && (it.id !in needsMdblist || p.mdblistKey.isNotBlank()) && wanted(it.name, p)
    }

    /** id, name and whether it's part of a merged row, for each row of a region (cached: counting runs on every tick). */
    private class Lite(val id: String, val name: String, val part: Boolean)
    private val liteCache = HashMap<Region, List<Lite>>()
    private fun lite(r: Region): List<Lite> = synchronized(liteCache) {
        liteCache.getOrPut(r) {
            val all = forRegion(r)
            (0 until all.length()).map { all.getJSONObject(it) }.map { Lite(it.optString("id"), it.optString("name"), it.optString("mergedInto").isNotBlank()) }
        }
    }

    /** Rows a category adds with these picks (as if it were ticked). */
    fun countIn(p: Picks, category: String): Int {
        val q = Picks(p.region, p.services, (p.categories + category).toMutableSet(), p.size).also { it.mdblistKey = p.mdblistKey }
        return lite(p.region).count {
            !it.part && group(it.name).first == category && (it.id !in needsMdblist || p.mdblistKey.isNotBlank()) && wanted(it.name, q)
        }
    }

    private val prefix = Regex("""^([^:>]{1,24}?)\s*(?:>|:)""")

    /** A row's category ("" = front page) and service (or null). */
    fun group(name: String): Pair<String, String?> {
        val cat = prefix.find(name)?.groupValues?.get(1)?.trim() ?: return "" to null
        if (cat != "Top 10" && cat != "Streaming") return cat to null
        val tail = name.substringAfterLast(':').trim()
        val service = when {
            name.contains("UK TV") -> "UK TV"
            tail.startsWith("Hulu") -> "Hulu"
            tail.startsWith("Peacock") -> "Peacock"
            else -> tail
        }
        return cat to service
    }

    /** Whether a row is on with these picks (rows folded into a merged row keep their own setting). */
    fun wanted(name: String, p: Picks, ignoreKey: Boolean = false): Boolean {
        val (cat, service) = group(name)
        if (cat.isEmpty()) return true
        if (cat !in p.categories) return false
        if (cat == "Genres" && p.size == Size.MINIMAL) return name.substringAfterLast(':').trim() in coreGenres
        if (service == null) return true
        return service in p.services && service in services(p.region)
    }

    private val file: JSONObject by lazy {
        JSONObject(RowSetup::class.java.getResourceAsStream("/stremonino-catalogue.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    fun catalogue(): JSONArray = JSONArray(file.getJSONArray("catalogs").toString())

    /** Rows that need an MDBList key (lists from MDBList, or merged rows using them). */
    val needsMdblist: Set<String> by lazy {
        file.optJSONArray("needsMdblist")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    }

    /** How many rows an MDBList key adds for these picks. */
    fun mdblistCount(p: Picks): Int = lite(p.region).count { !it.part && it.id in needsMdblist && wanted(it.name, p, ignoreKey = true) }

    /** The catalogue for a region: Top 10 rows use its country, streaming rows its watch region. */
    fun forRegion(r: Region): JSONArray {
        val src = catalogue()
        if (r == Region.UK) {
            // Hulu and Peacock aren't in the UK.
            val out = JSONArray()
            for (i in 0 until src.length()) src.getJSONObject(i).takeUnless { val s = group(it.optString("name")).second; s == "Hulu" || s == "Peacock" }?.let { out.put(it) }
            return out
        }
        val text = src.toString()
            .replace(Regex("""flixpatrol\.([a-z0-9-]+)\.gb\."""), "flixpatrol.$1.us.")
            .replace("\"watch_region\":\"GB\"", "\"watch_region\":\"US\"")
            .replace("\"watchRegion\":\"GB\"", "\"watchRegion\":\"US\"")
            .replace("\"region\":\"GB\"", "\"region\":\"US\"")
            .replace("\"certification_country\":\"GB\"", "\"certification_country\":\"US\"")
            .replace("\"certificationCountry\":\"GB\"", "\"certificationCountry\":\"US\"")
            .replace("watch_region=GB", "watch_region=US")
            .replace("region=GB", "region=US")
            .replace(" (US)", "")
        return JSONArray(text)
    }

    /** Switches each row on or off for [p] (rows that aren't part of the catalogue are left alone). */
    fun apply(catalogs: JSONArray, p: Picks) {
        for (i in 0 until catalogs.length()) {
            val o = catalogs.optJSONObject(i) ?: continue
            if (o.optString("mergedInto").isNotBlank()) continue
            val name = o.optString("name")
            val (cat, _) = group(name)
            if (cat.isNotEmpty() && cat !in categories) continue
            val keyOk = o.optString("id") !in needsMdblist || p.mdblistKey.isNotBlank()
            o.put("enabled", keyOk && wanted(name, p))
            if (cat.isNotEmpty()) o.put("showInHome", false)
        }
    }

    /** What an existing install has on, with its keys, as picks (for "Categories"). */
    fun picksFrom(config: JSONObject): Picks {
        val p = picksFrom(config.optJSONArray("catalogs") ?: JSONArray())
        config.optJSONObject("apiKeys")?.let { k -> p.tmdbKey = k.optString("tmdb"); p.mdblistKey = k.optString("mdblist") }
        return p
    }

    /** Puts the picks' keys into a configuration (blank keys are left blank). */
    fun putKeys(config: JSONObject, p: Picks) {
        val k = config.optJSONObject("apiKeys") ?: JSONObject().also { config.put("apiKeys", it) }
        k.put("tmdb", p.tmdbKey.trim()); k.put("mdblist", p.mdblistKey.trim())
    }

    fun picksFrom(catalogs: JSONArray): Picks {
        val cats = mutableSetOf<String>()
        val svcs = mutableSetOf<String>()
        var us = false
        for (i in 0 until catalogs.length()) {
            val o = catalogs.optJSONObject(i) ?: continue
            if (o.optString("id").contains(".us.") && o.optString("id").startsWith("flixpatrol.") && !o.optString("id").let { it.contains("hulu") || it.contains("peacock") || it.contains("starz") || it.contains("crunchyroll") }) us = true
            if (!o.optBoolean("enabled") || o.optString("mergedInto").isNotBlank()) continue
            val (cat, svc) = group(o.optString("name"))
            if (cat in categories) cats += cat
            svc?.let { svcs += it }
        }
        // Genres on, but only core ones: it was made Minimal.
        var extraGenre = false
        for (i in 0 until catalogs.length()) {
            val o = catalogs.optJSONObject(i) ?: continue
            val n = o.optString("name")
            if (o.optBoolean("enabled") && group(n).first == "Genres" && n.substringAfterLast(':').trim() !in coreGenres) extraGenre = true
        }
        val size = if ("Genres" in cats && !extraGenre) Size.MINIMAL else Size.STANDARD
        return Picks(if (us) Region.US else Region.UK, svcs, cats, size)
    }

    /** A full AIOMetadata configuration with AIOMetadata's usual defaults and these rows. */
    fun newConfig(p: Picks, tmdbKey: String = p.tmdbKey): JSONObject {
        val rows = forRegion(p.region)
        apply(rows, p)
        val engine = JSONObject()
        listOf("tmdb.search", "tvdb.search", "tvmaze.search", "trakt.search", "mdblist.search", "imdb.suggestions.search", "lumiere.search",
            "simkl.search", "simkl.search.movie", "simkl.search.series", "mal.search.movie", "mal.search.series").forEach { engine.put(it, true) }
        listOf("tvdb.collections.search", "people_search_movie", "people_search_series").forEach { engine.put(it, false) }
        return JSONObject()
            .put("language", p.region.language).put("addonName", "AIOMetadata").put("includeAdult", false).put("blurThumbs", false)
            .put("showPrefix", false).put("showMetaProviderAttribution", false).put("castCount", 10).put("displayAgeRating", false)
            .put("showDisabledCatalogs", false).put("sfw", false)
            .put("providers", JSONObject().put("movie", "tmdb").put("series", "tvdb").put("anime", "mal").put("anime_id_provider", "imdb").put("forceAnimeForDetectedImdb", false))
            .put("artProviders", JSONObject()
                .put("movie", JSONObject().put("poster", "meta").put("background", "meta").put("logo", "meta"))
                .put("series", JSONObject().put("poster", "meta").put("background", "meta").put("logo", "meta"))
                .put("anime", JSONObject().put("poster", "meta").put("background", "imdb").put("logo", "imdb"))
                .put("englishArtOnly", false).put("originalLangFallback", false))
            .put("tvdbSeasonType", "default").put("animeEpisodeDates", "provider").put("tvdbNormalizeYearSeasons", true)
            .put("mal", JSONObject().put("skipFiller", false).put("skipRecap", false).put("allowEpisodeMarking", false).put("skipMixedFiller", false).put("useImdbIdForCatalogAndSearch", false))
            .put("tmdb", JSONObject().put("scrapeImdb", false).put("forceLatinCastNames", false))
            .put("apiKeys", JSONObject().put("gemini", "").put("tmdb", tmdbKey).put("tvdb", "").put("fanart", "").put("rpdb", "")
                .put("topPoster", "").put("mdblist", p.mdblistKey.trim()).put("openrouter", "").put("publicmetadb", ""))
            .put("posterRatingProvider", "none").put("usePosterProxy", true)
            .put("enableRatingPostersForLibrary", true).put("showRateMeButton", false).put("ageRating", "None").put("allowUnratedContent", true)
            .put("searchEnabled", true).put("sessionId", "").put("catalogSetupComplete", true).put("timezone", p.region.timezone)
            .put("catalogs", rows)
            .put("search", JSONObject().put("enabled", true).put("ai_enabled", false).put("providers", JSONObject()
                .put("movie", "tmdb.search").put("series", "tvdb.search").put("anime_movie", "mal.search.movie").put("anime_series", "mal.search.series")
                .put("people_search_movie", "tmdb.people.search").put("people_search_series", "tmdb.people.search"))
                .put("engineEnabled", engine).put("searchNames", JSONObject()))
            .put("streaming", JSONArray())
    }

    class Created(val installUrl: String)

    /** Creates a new AIOMetadata install on [server] (blocking). Throws [AioRows.Failed] with a plain reason. */
    fun create(p: Picks, password: String, tmdbKey: String = p.tmdbKey, server: String = SERVER): Created {
        val body = JSONObject().put("config", newConfig(p, tmdbKey)).put("password", password)
        val (code, text) = Http.sendJson("$server/api/config/save", "POST", body.toString(), timeoutMs = 60_000)
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (code >= 400 || o == null) {
            val msg = o?.optString("error").orEmpty()
            throw AioRows.Failed(when {
                "mdblist" in msg.lowercase() -> "the MDBList key wasn't accepted ($msg)"
                "tmdb" in msg.lowercase() -> "this server wants your own TMDB key"
                msg.isNotBlank() -> msg
                else -> "the server answered $code"
            })
        }
        val url = o.optString("installUrl").takeIf { it.startsWith("http") } ?: throw AioRows.Failed("the server didn't send an install link")
        return Created(url)
    }
}
