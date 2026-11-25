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

    fun checks(authKey: String?, addons: List<Addon>, tmdbKey: String, describe: (Throwable) -> String): List<Check> {
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
                else Outcome(false, d, "Stremio's servers may be down; try again later.")
            }
        }

        val online = addons.filter { !it.isLocal }
        for (a in online) list += safe(a.name) {
            val ms = timed { Http.get(a.transportUrl, timeoutMs = 10_000) }
            if (ms > SLOW_MS) Outcome(true, "slow: $ms ms", "It works, but rows and streams from it will be slow.")
            else Outcome(true, "OK  $ms ms")
        }
        if (online.isEmpty()) list += Check("Add-ons") { Outcome(false, "none installed", "Settings › Your add-ons, or set up from your phone.") }

        // A row of titles from the first add-on with a row that needs nothing extra.
        online.firstNotNullOfOrNull { a -> a.catalogs.firstOrNull { it.required.isEmpty() }?.let { a to it } }?.let { (a, c) ->
            list += safe("A row of titles") {
                val url = a.base + "/catalog/" + c.type + "/" + c.id + ".json"
                val n = JSONObject(Http.get(url, timeoutMs = 20_000)).optJSONArray("metas")?.length() ?: 0
                if (n > 0) Outcome(true, "$n titles in \"${c.name}\"")
                else Outcome(false, "\"${c.name}\" came back empty", "Your metadata add-on may need its config saved again.")
            }
        }

        // Streams for the test film, one add-on at a time.
        val streamers = online.filter { it.supports("stream", "movie", TEST_ID) }
        for (a in streamers) list += safe("Streams: ${StreamParse.shortName(a.name)}") {
            val found = StreamParse.fetchAll(listOf(a), "movie", TEST_ID)
            val ready = found.count { it.cached == true }
            val fits = found.count { Best.score(it, 142.0) != null }
            when {
                found.isEmpty() -> Outcome(false, "no streams for the test film",
                    "Check your debrid (e.g. TorBox) is still active and its key is right in the add-on's config.")
                fits == 0 -> Outcome(false, "${found.size} found, none this device plays well", "All 4K, cam or not ready yet.")
                else -> Outcome(true, "${found.size} found, $ready ready, $fits fit this device")
            }
        }
        if (online.isNotEmpty() && streamers.isEmpty()) list += Check("Streams") {
            Outcome(false, "no add-on gives streams", "Install a stream add-on such as AIOStreams.")
        }

        if (tmdbKey.isNotBlank()) list += Check("TMDB key (Where to watch)") {
            try { WhereToWatch.checkKey(tmdbKey); Outcome(true, "accepted") }
            catch (e: WhereToWatch.KeyRefused) { Outcome(false, "key not accepted", "Copy it again from themoviedb.org › Settings › API.") }
            catch (e: Throwable) { Outcome(false, describe(e)) }
        }
        return list
    }

    /** One line for the top of the screen. */
    fun summary(done: Int, total: Int, failed: Int): String = when {
        done < total -> "Checking… $done of $total"
        failed == 0 -> "Everything's working ($total checks)"
        failed == 1 -> "1 problem found: see below"
        else -> "$failed problems found: see below"
    }
}
