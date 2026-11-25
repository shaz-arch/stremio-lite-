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
    /** Most rows ElfHosted's free AIOMetadata server lets one install switch on. */
    const val SERVER_LIMIT = 200

    /** Rows the server counts against its limit (everything not switched off). */
    fun serverCount(catalogs: JSONArray): Int = (0 until catalogs.length()).count { catalogs.optJSONObject(it)?.optBoolean("enabled", true) == true }

    /** Exactly what the server will count for these picks: a new install, or changes to [existing]. */
    fun serverCountFor(p: Picks, existing: JSONArray? = null): Int {
        val rows = JSONArray((existing ?: forRegion(p.region)).toString())
        apply(rows, p)
        return serverCount(rows)
    }

    /**
     * The TMDB Collections add-on: the Collections rows come through AIOMetadata, but opening a
     * film series needs this add-on on the account. Its settings match the ones the rows use.
     */
    val collectionsAddon: String by lazy {
        val all = catalogue()
        (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("id").startsWith("custom.org_stremio_tmdbcollections") }
            ?.optString("sourceUrl")?.substringBefore("/catalog/")?.let { "$it/manifest.json" }
            ?: "https://61ab9c85a149-tmdb-collections.baby-beamup.club/manifest.json"
    }

    /** TMDB Collections' own settings page (to make an install link with other settings). */
    val collectionsPage: String get() = Regex("""^(https?://[^/]+)""").find(collectionsAddon)?.value?.let { "$it/configure" } ?: collectionsAddon

    fun collectionsAddons(list: List<Addon>) = list.filter { "tmdb-collections" in it.transportUrl || "tmdbcollections" in it.id.lowercase() }

    fun hasCollectionsAddon(list: List<Addon>) = list.any { "tmdb-collections" in it.transportUrl || "tmdbcollections" in it.id.lowercase() }

    /** The biggest ticked categories, to suggest unticking when over the limit. */
    fun biggest(p: Picks, n: Int = 4, existing: JSONArray? = null): List<Pair<String, Int>> =
        (p.categories.map { it to countIn(p, it) } +
            (if (existing != null && !p.othersOff) listOf("Other rows in your config" to others(existing).size) else emptyList()))
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(n)

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
        "Themes", "Sitcoms", "Studios", "Directors", "Releases", "New & Latest",
        "World: European", "World: East Asian", "World: South Asian", "World: Middle Eastern",
        "Channels", "UK services", "Free services", "Film & specialist", "Indian services", "Asian services", "Other countries",
        "Franchises", "Actors", "Anime", "Seasonal", "Runtime", "Reality", "Indian", "Pakistani",
    )
    val defaultCategories = setOf("Top 10", "Streaming", "Genres", "Kids & Family", "Charts", "Decades", "Documentaries", "Collections")

    /** How many rows to start from: Minimal trims Genres to the core ones; Standard is the full layout. */
    enum class Size(val label: String, val categories: Set<String>) {
        MINIMAL("Minimal", setOf("Top 10", "Streaming", "Genres", "Kids & Family")),
        STANDARD("Standard", setOf("Top 10", "Streaming", "Genres", "Kids & Family", "Charts", "Decades", "Documentaries", "Collections",
            "Themes", "Sitcoms", "Studios", "Directors", "Releases")),
        FULL("Full", STANDARD.categories + setOf("New & Latest", "World: European", "World: East Asian", "World: South Asian",
            "World: Middle Eastern", "Channels", "UK services", "Free services", "Film & specialist", "Franchises", "Actors",
            "Anime", "Seasonal", "Runtime", "Reality")),
    }

    /** The genres Minimal keeps (for Movies and Shows). */
    val coreGenres = setOf("Action", "Comedy", "Drama", "Horror", "Thriller", "Sci-Fi", "Crime", "Romance", "Animation", "Mystery")

    class Picks(var region: Region, val services: MutableSet<String>, val categories: MutableSet<String>, var size: Size = Size.STANDARD) {
        /** Optional keys: your own TMDB key, and an MDBList key (which switches on the MDBList rows). */
        var tmdbKey: String = ""
        var mdblistKey: String = ""
        /** Editing: switch off rows in the install that aren't in any category here (see [others]). */
        var othersOff: Boolean = false
    }

    /** Today's name for each catalogue row ("id|type" → name): older installs may have earlier names. */
    private val currentNames: Map<String, String> by lazy {
        val all = catalogue()
        (0 until all.length()).map { all.getJSONObject(it) }.associate { (it.optString("id") + "|" + it.optString("type")) to it.optString("name") }
    }

    /**
     * Rows whose settings changed since installs were made (e.g. Upcoming now asks for UK cinema
     * releases in the next 90 days, most popular first, rather than every film worldwide from today).
     * Saving your rows brings these up to date.
     */
    private val refresh: Set<String> by lazy {
        file.optJSONArray("refresh")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    }

    /** A row's name as the catalogue has it today (older installs were made before some groups were split). */
    private fun nameOf(o: JSONObject): String = currentNames[o.optString("id") + "|" + o.optString("type")] ?: o.optString("name")

    /** A row that isn't in any category this checklist can tick (an older or hand-made row, your own lists). */
    private fun isOther(o: JSONObject): Boolean {
        val known = currentNames.containsKey(o.optString("id") + "|" + o.optString("type"))
        val (cat, _) = group(nameOf(o))
        return (cat.isNotEmpty() && cat !in categories) || (!known && cat.isEmpty())
    }

    /**
     * Adds to an install the catalogue rows it doesn't have (switched off), so ticking a category
     * always has rows to switch on. Installs made on AIOMetadata's own page, or from an older
     * catalogue, can be missing whole categories. Returns how many were added.
     */
    fun complete(existing: JSONArray, r: Region): Int {
        val have = HashSet<String>()
        for (i in 0 until existing.length()) existing.optJSONObject(i)?.let { have += it.optString("id") + "|" + it.optString("type") }
        val all = forRegion(r)
        var added = 0
        for (i in 0 until all.length()) {
            val o = all.getJSONObject(i)
            if (!have.add(o.optString("id") + "|" + o.optString("type"))) continue
            o.put("enabled", false)
            existing.put(o)
            added++
        }
        return added
    }

    /** Names of the rows in [existing] that are on and not in any category here. */
    fun others(existing: JSONArray): List<String> = (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }
        .filter { it.optBoolean("enabled", true) && it.optString("mergedInto").isBlank() && isOther(it) }
        .map { nameOf(it) }

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
        !it.part && (it.id !in needsMdblist || p.mdblistKey.isNotBlank()) && sizeOk(it.id, it.type, p) && wanted(it.name, p)
    }

    /** id, name and whether it's part of a merged row, for each row of a region (cached: counting runs on every tick). */
    private class Lite(val id: String, val type: String, val name: String, val part: Boolean)
    private val liteCache = HashMap<Region, List<Lite>>()
    private fun lite(r: Region): List<Lite> = synchronized(liteCache) {
        liteCache.getOrPut(r) {
            val all = forRegion(r)
            (0 until all.length()).map { all.getJSONObject(it) }.map { Lite(it.optString("id"), it.optString("type"), it.optString("name"), it.optString("mergedInto").isNotBlank()) }
        }
    }

    /** Rows a category adds with these picks (as if it were ticked). */
    fun countIn(p: Picks, category: String): Int {
        val q = Picks(p.region, p.services, (p.categories + category).toMutableSet(), p.size).also { it.mdblistKey = p.mdblistKey }
        return lite(p.region).count {
            !it.part && group(it.name).first == category && (it.id !in needsMdblist || p.mdblistKey.isNotBlank()) &&
                sizeOk(it.id, it.type, q) && wanted(it.name, q)
        }
    }

    private val prefix = Regex("""^([^:>]{1,24}?)\s*(?:>|:)""")

    /** A row's category ("" = front page) and service (or null). */
    fun group(name: String): Pair<String, String?> {
        val cat = prefix.find(name)?.groupValues?.get(1)?.trim() ?: return "" to null
        // World and More services are split one level down: "World > European > French: Popular".
        if (cat == "World" || cat == "More services") {
            val second = name.substringBefore(": ").split(" > ").getOrNull(1)?.trim()
            if (second != null) return (if (cat == "World") "World: $second" else second) to null
        }
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

    /** Rows only in the Full size (extra directors, studios, themes and so on inside the usual folders), as "id|type". */
    val fullOnly: Set<String> by lazy {
        file.optJSONArray("fullOnly")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    }

    private fun sizeOk(id: String, type: String, p: Picks) = p.size == Size.FULL || "$id|$type" !in fullOnly

    /** A region's catalogue rows by "id|type". */
    private val regionCache = HashMap<Region, Map<String, JSONObject>>()
    private fun regionRows(r: Region): Map<String, JSONObject> = synchronized(regionCache) {
        regionCache.getOrPut(r) {
            val a = forRegion(r)
            (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.optString("id") + "|" + it.optString("type") }
        }
    }

    /** Rows that need an MDBList key (lists from MDBList, or merged rows using them). */
    val needsMdblist: Set<String> by lazy {
        file.optJSONArray("needsMdblist")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    }

    /** How many rows an MDBList key adds for these picks. */
    fun mdblistCount(p: Picks): Int = lite(p.region).count { !it.part && it.id in needsMdblist && sizeOk(it.id, it.type, p) && wanted(it.name, p, ignoreKey = true) }

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
            // Parts of a merged row stay off (the merged row fetches them); a row with no setting counts as on.
            if (o.optString("mergedInto").isNotBlank()) { o.put("enabled", false); continue }
            // Rows from an older install get today's name, so they land in today's categories.
            val name = nameOf(o)
            if (name != o.optString("name")) o.put("name", name)
            val key = o.optString("id") + "|" + o.optString("type")
            if (key in refresh) regionRows(p.region)[key]?.optJSONObject("metadata")?.let { o.put("metadata", JSONObject(it.toString())) }
            if (isOther(o)) { if (p.othersOff) o.put("enabled", false); continue }
            val (cat, _) = group(name)
            val keyOk = o.optString("id") !in needsMdblist || p.mdblistKey.isNotBlank()
            o.put("enabled", keyOk && sizeOk(o.optString("id"), o.optString("type"), p) && wanted(name, p))
            if (cat.isNotEmpty()) o.put("showInHome", false)
        }
    }

    /** What an existing install has on, with its keys, as picks (for "Categories"). */
    fun picksFrom(config: JSONObject): Picks {
        val p = picksFrom(config.optJSONArray("catalogs") ?: JSONArray())
        config.optJSONObject("apiKeys")?.let { k -> p.tmdbKey = k.optString("tmdb"); p.mdblistKey = k.optString("mdblist") }
        return p
    }

    /** Changes only the MDBList key in a configuration (rows, and personal lists like the Watchlist, use it). */
    fun setMdblistKey(config: JSONObject, key: String) {
        val k = config.optJSONObject("apiKeys") ?: JSONObject().also { config.put("apiKeys", it) }
        k.put("mdblist", key.trim())
    }

    /** Where to get a free Gemini key (Google AI Studio). */
    const val GEMINI_KEY_PAGE = "https://aistudio.google.com/app/apikey"

    /**
     * Puts a Gemini key into a configuration and switches AIOMetadata's AI Search on (off when blank).
     * AI only runs for searches starting with the trigger word "ai", so ordinary
     * searches, in Stremonino or Stremio's own apps, don't use your Gemini allowance.
     */
    fun setAiKey(config: JSONObject, key: String) {
        val k = config.optJSONObject("apiKeys") ?: JSONObject().also { config.put("apiKeys", it) }
        k.put("gemini", key.trim())
        val search = config.optJSONObject("search") ?: JSONObject().also { config.put("search", it) }
        search.put("ai_enabled", key.isNotBlank())
        search.put("ai_trigger_keyword", AiSearch.TRIGGER)   // Ask AI puts this word first, so the two must agree
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
            val (cat, svc) = group(nameOf(o))
            if (cat in categories) cats += cat
            svc?.let { svcs += it }
        }
        // Genres on, but only core ones: it was made Minimal.
        var extraGenre = false
        for (i in 0 until catalogs.length()) {
            val o = catalogs.optJSONObject(i) ?: continue
            val n = nameOf(o)
            if (o.optBoolean("enabled") && group(n).first == "Genres" && n.substringAfterLast(':').trim() !in coreGenres) extraGenre = true
        }
        var full = false
        for (i in 0 until catalogs.length()) {
            val o = catalogs.optJSONObject(i) ?: continue
            if (o.optBoolean("enabled") && "${o.optString("id")}|${o.optString("type")}" in fullOnly) full = true
        }
        val size = if (full) Size.FULL else if ("Genres" in cats && !extraGenre) Size.MINIMAL else Size.STANDARD
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
