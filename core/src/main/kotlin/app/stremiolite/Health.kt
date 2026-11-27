package app.stremiolite

import org.json.JSONObject

/**
 * "Is it working?": checks each link in the chain, in order, so when something's wrong you can see
 * where: the internet, your Stremio sign-in, each add-on, a row of titles, streams for a test film
 * (and how many the stick can play), and the TMDB key if you've added one.
 */
object Health {
    /** A test film every stream add-on should have: The Shawshank Redemption. */
    const val TEST_ID = "tt0111161"
    const val TEST_NAME = "The Shawshank Redemption"
    private const val SLOW_MS = 4_000L

    class Outcome(val ok: Boolean, val text: String, val help: String = "")
    class Check(val name: String, val run: () -> Outcome)

    fun checks(authKey: String?, loaded: List<Addon>?, tmdbKey: String, describe: (Throwable) -> String, onSpeed: ((Double) -> Unit)? = null, keys: List<Check> = emptyList()): List<Check> {
        val addons = loaded ?: emptyList()
        fun offline(d: String) = listOf("address not found", "timed out", "refused", "unreachable", "network").any { it in d.lowercase() }
        fun safe(name: String, f: () -> Outcome) = Check(name) { try { f() } catch (e: Throwable) { Outcome(false, describe(e)) } }
        fun timed(f: () -> Unit): Long { val t = System.currentTimeMillis(); f(); return System.currentTimeMillis() - t }
        val list = mutableListOf<Check>()

        list += Check("Internet") {
            try {
                val ms = timed { Http.get("https://api.strem.io/", timeoutMs = 10_000, allowErrors = true) }
                Outcome(true, "OK  $ms ms")
            } catch (e: Throwable) {
                Outcome(false, describe(e), "Check the Wi-Fi, or restart the router and the device.")
            }
        }
        if (authKey != null) list += Check("Stremio account") {
            try {
                Outcome(true, "signed in as " + Stremio.userEmail(authKey))
            } catch (e: Throwable) {
                val d = describe(e)
                if ("session" in d.lowercase() || "auth" in d.lowercase()) Outcome(false, "signed out", "Sign in again: Home › Sign out of this profile, then sign in.")
                else Outcome(false, d, if (offline(d)) "Sort out the internet first." else "Stremio's servers may be down; try again later.")
            }
        }

        val online = addons.filter { !it.isLocal }
        for (a in online) list += safe(a.name) {
            val ms = timed { Http.get(a.transportUrl, timeoutMs = 10_000) }
            if (ms > SLOW_MS) Outcome(true, "slow: $ms ms", "It works, but rows and streams from it will be slow.")
            else Outcome(true, "OK  $ms ms")
        }
        if (loaded == null) list += Check("Add-ons") { Outcome(false, "not loaded", "They load with your rows: sort out the internet or sign-in, then Check again.") }
        else if (online.isEmpty()) list += Check("Add-ons") { Outcome(false, "none installed", "Settings › Your add-ons, or set up from your phone.") }

        // A row of titles from the first add-on with a row that needs nothing extra.
        online.firstNotNullOfOrNull { a -> a.catalogs.firstOrNull { it.required.isEmpty() }?.let { a to it } }?.let { (a, c) ->
            list += safe("A row of titles") {
                val url = a.base + "/catalog/" + c.type + "/" + c.id + ".json"
                val n = JSONObject(Http.get(url, timeoutMs = 20_000)).optJSONArray("metas")?.length() ?: 0
                if (n > 0) Outcome(true, "$n titles in \"${c.name}\"")
                else Outcome(false, "\"${c.name}\" came back empty", "Your metadata add-on may need its config saved again.")
            }
        }

        // Streams for the test film, one add-on at a time. Add-ons that say they answer streams but
        // aren't stream providers (TMDB Collections opens film series) can't fail this.
        val streamers = online.filter { it.supports("stream", "movie", TEST_ID) }
        for (a in streamers) list += safe("Streams: ${StreamParse.shortName(a.name)}") {
            val found = StreamParse.fetchAll(listOf(a), "movie", TEST_ID)
            val ready = found.count { it.cached == true }
            val fits = found.count { Best.score(it, 142.0) != null }
            when {
                found.isEmpty() && !isStreamProvider(a) -> Outcome(true, "not a stream add-on (fine)")
                found.isEmpty() -> Outcome(false, "no playable streams for the test film",
                    runCatching { whyEmpty(a) }.getOrDefault("it didn't answer") + ". If your debrid service is active, set up your streams again (Settings › Your streams).")
                fits == 0 -> Outcome(false, "${found.size} found, none this device plays well", "All 4K, cam or not ready yet.")
                else -> Outcome(true, "${found.size} found, $ready ready, $fits fit this device")
            }
        }
        if (streamers.isNotEmpty()) list += speedCheck(streamers, describe, onSpeed)
        if (online.isNotEmpty() && streamers.isEmpty()) list += Check("Streams") {
            Outcome(false, "no add-on gives streams", "Install a stream add-on such as AIOStreams.")
        }

        if (tmdbKey.isNotBlank()) list += Check("TMDB key (Where to watch)") {
            try { WhereToWatch.checkKey(tmdbKey); Outcome(true, "accepted") }
            catch (e: WhereToWatch.KeyRefused) { Outcome(false, "key not accepted", "Copy it again from themoviedb.org › Settings › API.") }
            catch (e: Throwable) { Outcome(false, describe(e)) }
        }
        // Your other keys (debrid, MDBList, Gemini), each with its own service.
        list += keys
        return list
    }

    /** What a stream add-on actually sent for the test film when nothing playable came back, in a line. */
    fun whyEmpty(a: Addon): String {
        val arr = JSONObject(Http.get(a.base + "/stream/movie/$TEST_ID.json", timeoutMs = 20_000)).optJSONArray("streams")
            ?: return "${StreamParse.shortName(a.name)} sent nothing back"
        if (arr.length() == 0) return "${StreamParse.shortName(a.name)} sent an empty list"
        var torrent = 0; var headers = 0; var noLink = 0
        var said: String? = null
        val trouble = Regex("(?i)error|invalid|fail|expired|unauthori|denied|decrypt|limit|key|config|password")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val link = o.optString("url")
            when {
                link.startsWith("http") -> {}
                o.has("infoHash") -> torrent++
                else -> noLink++
            }
            val text = listOf("name", "title", "description").joinToString(" ") { o.optString(it) }.replace(Regex("\\s+"), " ").trim()
            if (said == null && trouble.containsMatchIn(text)) said = text.take(140)
        }
        return listOfNotNull(
            said?.let { "${StreamParse.shortName(a.name)} says: \"$it\"" },
            if (torrent > 0) "$torrent torrent-only links (your debrid service isn't being used)" else null,
            if (headers > 0) "$headers need special headers" else null,
            if (noLink > 0 && said == null) "$noLink with no link" else null,
        ).joinToString("; ").ifEmpty { "nothing this app can play" }
    }

    /**
     * The quiet check before you sit down (blocking): null when streams work, or why not. Says nothing
     * when the internet itself is down, and asks twice so one slow answer isn't an alarm.
     */
    fun streamProblem(addons: List<Addon>): String? {
        val streamers = addons.filter { !it.isLocal && it.supports("stream", "movie", TEST_ID) && isStreamProvider(it) }
        if (streamers.isEmpty()) return null
        if (runCatching { Http.get("https://api.strem.io/", timeoutMs = 10_000, allowErrors = true) }.isFailure) return null
        val (main, backup) = StreamParse.tiers(streamers)
        fun works(list: List<Addon>) = StreamParse.hasPlayable(StreamParse.fetchAll(list, "movie", TEST_ID))
        if (works(main)) return null
        Thread.sleep(5_000)
        if (works(main)) return null
        val why = main.joinToString("; ") { a -> runCatching { whyEmpty(a) }.getOrDefault("${StreamParse.shortName(a.name)} didn't answer") }
        // AIOStreams is down but a fail-safe answers: you can still watch.
        return if (backup.isNotEmpty() && works(backup)) FAILSAFE_ON + why else why
    }

    /** Starts a [streamProblem] when a fail-safe is covering for your main streams. */
    const val FAILSAFE_ON = "fail-safe|"
    fun usingFailsafe(problem: String) = problem.startsWith(FAILSAFE_ON)
    fun problemText(problem: String) = problem.removePrefix(FAILSAFE_ON)

    /** Add-ons whose job is giving streams (AIOStreams, Torrentio, Comet…), as opposed to ones that only link out. */
    fun isStreamProvider(a: Addon): Boolean {
        val n = (a.name + " " + a.id).lowercase()
        return StreamSetup.isAioStreams(a) || listOf("torrentio", "comet", "mediafusion", "stremthru", "jackett", "debrid", "torbox", "peerflix", "knightcrawler", "easynews", "usenet")
            .any { it in n }
    }

    /** The speed test as a check (for Is everything working? and the Stream speed screen). */
    fun speedCheck(addons: List<Addon>, describe: (Throwable) -> String, onSpeed: ((Double) -> Unit)? = null): Check = Check("Speed: streams to this device") {
        try {
            val url = SpeedTest.testUrl(addons) ?: return@Check Outcome(false, "no stream to test with", "Is everything working? shows why.")
            val mbps = SpeedTest.measure(url)
            onSpeed?.invoke(mbps)
            if (mbps < 5) Outcome(false, SpeedTest.advice(mbps), "Slow for HD: try 5 GHz Wi-Fi, moving the router, or an Ethernet adapter.")
            else Outcome(true, SpeedTest.advice(mbps))
        } catch (e: Throwable) { Outcome(false, describe(e)) }
    }

    /** One line for the top of the screen. */
    fun summary(done: Int, total: Int, failed: Int): String = when {
        done < total -> "Checking… $done of $total"
        failed == 0 -> "Everything's working ($total checks)"
        failed == 1 -> "1 problem found: see below"
        else -> "$failed problems found: see below"
    }
}
