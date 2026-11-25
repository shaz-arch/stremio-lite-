package app.stremiolite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The two choices people actually need: which player, and which subtitle language. */
object Settings {
    private const val PREFS = "settings"

    val languages = Languages.all

    class PlayerChoice(val name: String, val pkg: String?)

    val players = listOf(
        PlayerChoice("VLC", Player.VLC),
        PlayerChoice("Just Player", Player.JUST_PLAYER),
        PlayerChoice("Ask each time", null),
    )

    /** Each profile has its own settings. */
    private fun prefs(c: Context) = Profiles.prefsFor(c, PREFS)

    fun player(c: Context): PlayerChoice =
        players.getOrElse(prefs(c).getInt("player", 0)) { players[0] }

    fun subtitleLanguage(c: Context): Language =
        languages.getOrElse(prefs(c).getInt("subLang", 1)) { languages[1] }

    fun autoNext(c: Context): Boolean = prefs(c).getBoolean("autoNext", true)

    // ---- Home rows hidden on this device ----

    // ---- settings that follow the account ------------------------------------------------
    // Kept as one hidden record on the Stremio account (like the waiting list), so the phone and the
    // stick share them. The player and text size stay per device: a phone and a TV want different ones.

    private const val SYNC_ID = "stremiolite:settings"
    private val INT_KEYS = listOf("subLang", "theme", "clock", "textSize")
    private val BOOL_KEYS = listOf("autoNext", "hideWatched", "autoFollow", "liveTv", "newsOn", "comingOn")
    /** The look: shared by default, or kept to this device ("lookLocal", which itself never syncs). */
    private val LOOK_KEYS = setOf("theme", "clock", "textSize")

    fun lookLocal(c: Context) = prefs(c).getBoolean("lookLocal", false)

    private fun shares(c: Context, k: String) = k !in LOOK_KEYS || !lookLocal(c)

    /** The shared settings as JSON (also what "Share my setup" sends). */
    fun exportShared(c: Context): JSONObject {
        val p = prefs(c)
        val o = JSONObject()
        INT_KEYS.forEach { k -> if (p.contains(k) && shares(c, k)) o.put(k, p.getInt(k, 0)) }
        BOOL_KEYS.forEach { k -> if (p.contains(k)) o.put(k, p.getBoolean(k, false)) }
        o.put("hiddenRows", JSONArray(hiddenRows(c).toList()))
        p.getString("layout", null)?.let { o.put("layout", it) }
        return o
    }

    /** Takes shared settings from someone's setup: their options, plus their hidden rows and [alsoHide]. */
    fun importShared(c: Context, o: JSONObject, alsoHide: Set<String>) {
        val e = prefs(c).edit()
        INT_KEYS.forEach { k -> if (o.has(k)) e.putInt(k, o.optInt(k)) }
        BOOL_KEYS.forEach { k -> if (o.has(k)) e.putBoolean(k, o.optBoolean(k)) }
        val theirs = o.optJSONArray("hiddenRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        e.putStringSet("hiddenRows", hiddenRows(c) + theirs + alsoHide)
        if (o.has("layout")) e.putString("layout", o.optString("layout"))
        e.apply()
        syncOut(c)
    }

    /** Sends this device's shared settings to the account. */
    fun syncOut(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val item = History.rawRemote(c, SYNC_ID)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(SYNC_ID, "other", "{}")
        // Start from what's on the account, so settings this device keeps to itself (or doesn't have)
        // stay as the other devices left them.
        val o = runCatching { JSONObject(item.optString("name")) }.getOrNull() ?: JSONObject()
        val mine = exportShared(c)
        mine.keys().forEach { k -> o.put(k, mine.get(k)) }
        // Your playlists follow the account, but never go out with "Share my setup".
        p.getString("playlists", null)?.let { o.put("playlists", it) }
        p.getString("liveFavs", null)?.let { o.put("liveFavs", it) }
        // Your TMDB key follows your account (never "Share my setup").
        p.getString("tmdbKey", null)?.let { o.put("tmdbKey", it) }
        p.getString("watchRegion", null)?.let { o.put("watchRegion", it) }
        p.getString("liveGuide", null)?.let { o.put("liveGuide", it) }
        p.getString("liveFreeHide", null)?.let { o.put("liveFreeHide", it) }
        p.getString("newsFeeds", null)?.let { o.put("newsFeeds", it) }
        p.getString("newsHide", null)?.let { o.put("newsHide", it) }
        o.put("updated", now)
        p.edit().putLong("syncedAt", now).apply()
        item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(now))
        History.putRemote(c, item)
    }

    /** Takes the account's settings if they're newer than this device's. Returns true if anything changed. */
    fun syncIn(c: Context): Boolean {
        val p = prefs(c)
        // Copied into this profile while another was in use: send it to the account first.
        if (p.getBoolean("pendingSync", false)) {
            p.edit().remove("pendingSync").apply()
            syncOut(c)
            return false
        }
        val raw = History.rawRemote(c, SYNC_ID)
        if (raw == null) {
            // Nothing on the account yet: this device's settings become the shared ones.
            if (p.getLong("syncedAt", 0) == 0L) syncOut(c)
            return false
        }
        val o = runCatching { JSONObject(JSONObject(raw).optString("name")) }.getOrNull() ?: return false
        val updated = o.optLong("updated")
        if (updated <= p.getLong("syncedAt", 0)) return false
        // The first time a device joins, keep the rows it had hidden as well as the account's.
        val first = p.getLong("syncedAt", 0) == 0L
        val remoteHidden = o.optJSONArray("hiddenRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val hidden = if (first) remoteHidden + hiddenRows(c) else remoteHidden
        val e = p.edit()
        INT_KEYS.forEach { k -> if (o.has(k) && shares(c, k)) e.putInt(k, o.optInt(k)) }
        BOOL_KEYS.forEach { k -> if (o.has(k)) e.putBoolean(k, o.optBoolean(k)) }
        if (o.has("layout")) e.putString("layout", o.optString("layout"))
        if (o.has("playlists")) e.putString("playlists", o.optString("playlists"))
        if (o.has("liveFavs")) e.putString("liveFavs", o.optString("liveFavs"))
        if (o.has("tmdbKey")) e.putString("tmdbKey", o.optString("tmdbKey"))
        if (o.has("watchRegion")) e.putString("watchRegion", o.optString("watchRegion"))
        if (o.has("liveGuide")) e.putString("liveGuide", o.optString("liveGuide"))
        if (o.has("liveFreeHide")) e.putString("liveFreeHide", o.optString("liveFreeHide"))
        if (o.has("newsFeeds")) e.putString("newsFeeds", o.optString("newsFeeds"))
        if (o.has("newsHide")) e.putString("newsHide", o.optString("newsHide"))
        e.putStringSet("hiddenRows", hidden)
        e.putLong("syncedAt", updated).apply()
        if (hidden != remoteHidden) syncOut(c)
        return true
    }

    /** Lists show watched films with a ✓, or leave them out. */
    fun hideWatched(c: Context) = prefs(c).getBoolean("hideWatched", false)

    /** Follow a show for new episodes as soon as you start watching it (off: you choose which). */
    fun autoFollow(c: Context) = prefs(c).getBoolean("autoFollow", false)

    fun rowKey(a: Addon, c: Addon.Catalog) = Folders.rowKey(a, c)

    /** Live TV on Home (on unless turned off). */
    fun liveTv(c: Context) = prefs(c).getBoolean("liveTv", true)

    /** Your M3U playlists for Live TV, shared with your other devices through the account. */
    fun playlists(c: Context): List<LiveTv.Playlist> = LiveTv.playlists(prefs(c).getString("playlists", null))

    /** Your favourite Live TV channels, from any list; shared with your other devices, never with "Share". */
    fun favourites(c: Context): List<LiveTv.Channel> = LiveTv.favourites(prefs(c).getString("liveFavs", null))

    fun setFavourites(c: Context, list: List<LiveTv.Channel>) {
        prefs(c).edit().putString("liveFavs", LiveTv.favouritesJson(list)).apply()
        syncOut(c)
    }

    /** What's hidden in Live TV's built-in Free channels (synced to your devices). */
    fun builtinHidden(c: Context): Set<String> = runCatching {
        val a = org.json.JSONArray(prefs(c).getString("liveFreeHide", "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun setBuiltinHidden(c: Context, h: Set<String>) {
        prefs(c).edit().putString("liveFreeHide", org.json.JSONArray(h.toList()).toString()).apply()
        syncOut(c)
    }

    /** News and Coming soon on Home (on unless turned off). */
    fun newsOn(c: Context) = prefs(c).getBoolean("newsOn", true)
    fun comingOn(c: Context) = prefs(c).getBoolean("comingOn", true)

    /** News feeds you've added (synced). */
    fun newsFeeds(c: Context): List<News.Feed> = News.feeds(prefs(c).getString("newsFeeds", null))

    fun setNewsFeeds(c: Context, list: List<News.Feed>) { prefs(c).edit().putString("newsFeeds", News.feedsJson(list)).apply(); syncOut(c) }

    /** Starter feeds switched off (their links; synced). */
    fun newsHidden(c: Context): Set<String> = runCatching {
        val a = org.json.JSONArray(prefs(c).getString("newsHide", "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun setNewsHidden(c: Context, h: Set<String>) { prefs(c).edit().putString("newsHide", org.json.JSONArray(h.toList()).toString()).apply(); syncOut(c) }

    /** The film list Coming soon reads ("" = automatic). */
    fun comingFrom(c: Context, type: String = "movie"): String = prefs(c).getString(if (type == "movie") "comingFrom" else "comingFromShows", "") ?: ""

    fun setComingFrom(c: Context, type: String, key: String) =
        prefs(c).edit().putString(if (type == "movie") "comingFrom" else "comingFromShows", key).apply()

    /** Game scores (this profile, this device). */
    fun gameStats(c: Context): String? = prefs(c).getString("games", null)

    fun setGameStats(c: Context, json: String) = prefs(c).edit().putString("games", json).apply()

    /** Your TMDB key, for Where to watch on details pages ("" if none). */
    fun tmdbKey(c: Context): String = prefs(c).getString("tmdbKey", "") ?: ""

    fun setTmdbKey(c: Context, k: String) { prefs(c).edit().putString("tmdbKey", k).apply(); WhereToWatch.forget(); syncOut(c) }

    /** The country for Where to watch: "GB" or "US". */
    fun watchRegion(c: Context): String = prefs(c).getString("watchRegion", "GB") ?: "GB"

    fun setWatchRegion(c: Context, r: String) { prefs(c).edit().putString("watchRegion", r).apply(); syncOut(c) }

    /** Settings → Where to watch: your TMDB key and the country. */
    fun whereToWatch(app: MainActivity): Screen {
        lateinit var keys: Screen
        val region = Row("Country", "◄ ${WhereToWatch.regionName(watchRegion(app))} ►")
        region.action = {
            setWatchRegion(app, if (watchRegion(app) == "GB") "US" else "GB")
            region.detail = "◄ ${WhereToWatch.regionName(watchRegion(app))} ►"
            app.refresh(keys)
        }
        keys = RowsSetup.keyScreen(app, "TMDB key", WhereToWatch.KEY_PAGE, listOf(
            "Shows how a title was first released (cinemas, Netflix,",
            "TV…) and where to watch it now, on its details page.",
            "Free TMDB account, then Settings › API: copy the API key",
            "(or the longer read access token). Follows your account.",
        ), { tmdbKey(app) }, { setTmdbKey(app, it) }, top = listOf(region, Row("")))
        return keys
    }

    /** Channels played lately (this device only). */
    fun recent(c: Context): List<LiveTv.Channel> = LiveTv.favourites(prefs(c).getString("liveRecent", null))

    fun setRecent(c: Context, list: List<LiveTv.Channel>) = prefs(c).edit().putString("liveRecent", LiveTv.favouritesJson(list)).apply()

    /** Your TV guide link (XMLTV) for Live TV, or "". */
    fun liveGuide(c: Context): String = prefs(c).getString("liveGuide", "") ?: ""

    fun setLiveGuide(c: Context, url: String) {
        prefs(c).edit().putString("liveGuide", url).apply()
        syncOut(c)
    }

    /** This profile's Live TV (playlists, favourites, guide link), for copying. */
    fun live(c: Context): LiveTv.Bundle = liveOf(prefs(c))

    fun liveOf(p: android.content.SharedPreferences) =
        LiveTv.Bundle(LiveTv.playlists(p.getString("playlists", null)), LiveTv.favourites(p.getString("liveFavs", null)), p.getString("liveGuide", "") ?: "")

    /** Writes [b] into a profile's settings; [send] syncs it now (the current profile), otherwise on its next start. */
    fun putLive(p: android.content.SharedPreferences, b: LiveTv.Bundle, c: Context?, send: Boolean) {
        p.edit().putString("playlists", LiveTv.playlistsJson(b.playlists)).putString("liveFavs", LiveTv.favouritesJson(b.favourites))
            .putString("liveGuide", b.guide).putBoolean("pendingSync", !send).apply()
        if (send && c != null) syncOut(c)
    }

    /** Adds someone's Live TV to this profile's. */
    fun addLive(c: Context, theirs: LiveTv.Bundle) = putLive(prefs(c), theirs.into(live(c)), c, send = true)

    fun setPlaylists(c: Context, list: List<LiveTv.Playlist>) {
        prefs(c).edit().putString("playlists", LiveTv.playlistsJson(list)).apply()
        syncOut(c)
    }

    /** Your arrangement of the Index (see Layout), shared with the other devices. */
    fun layout(c: Context): Layout.Data = Layout.parse(prefs(c).getString("layout", null))

    fun saveLayout(c: Context, d: Layout.Data) {
        prefs(c).edit().putString("layout", Layout.json(d)).apply()
        syncOut(c)
    }

    fun hiddenRows(c: Context): Set<String> = prefs(c).getStringSet("hiddenRows", emptySet()) ?: emptySet()

    fun setHidden(c: Context, key: String, hide: Boolean) {
        val now = hiddenRows(c).toMutableSet()
        if (hide) now += key else now -= key
        prefs(c).edit().putStringSet("hiddenRows", now).apply()
        syncOut(c)
    }

    /** Every catalogue row from the add-ons, each switchable between shown and hidden on Home. */
    private fun rowsScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val pairs = Home.rowAddons().filter { !it.isLocal }.flatMap { a -> a.catalogs.filter { Index.openable(it) }.map { a to it } }
            if (pairs.isEmpty()) return listOf(Row("Your rows are still loading. Go back and try again in a moment."))
            val hidden = hiddenRows(app)
            val rows = mutableListOf(
                Row("OK switches a row between shown and hidden"),
                Row("Show all rows", action = { prefs(app).edit().remove("hiddenRows").apply(); syncOut(app); screen.rows = build(); app.refresh(screen) }),
                Row("────────────────────"),
            )
            pairs.forEach { (a, c) ->
                val key = rowKey(a, c)
                lateinit var r: Row
                r = Row("${c.name}  ·  ${Catalogs.typeLabel(c.type)}", if (key in hidden) "hidden" else "shown", action = {
                    val hide = key !in hiddenRows(app)
                    setHidden(app, key, hide)
                    r.detail = if (hide) "hidden" else "shown"
                    app.refresh(screen)
                })
                rows += r
            }
            return rows
        }
        screen = Screen("Hide rows", build())
        screen.position = 3
        return screen
    }

    fun screen(app: MainActivity): Screen {
        lateinit var screen: Screen
        lateinit var playerRow: Row
        lateinit var langRow: Row
        lateinit var nextRow: Row
        lateinit var followRow: Row
        lateinit var themeRow: Row
        lateinit var sizeRow: Row
        lateinit var clockRow: Row
        lateinit var watchedRow: Row
        lateinit var lookRow: Row
        lateinit var liveRow: Row
        lateinit var newsRow: Row
        lateinit var comingRow: Row

        fun show() {
            lookRow.detail = "◄ ${if (lookLocal(app)) "This device only" else "Same on all devices"} ►"
            themeRow.detail = "◄ ${Look.theme(app).name} ►"
            sizeRow.detail = "◄ ${Look.size(app).name} ►"
            clockRow.detail = "◄ ${Look.clock(app).name} ►"
            watchedRow.detail = "◄ ${if (hideWatched(app)) "Hide them" else "Show with ●"} ►"
            playerRow.detail = "◄ ${player(app).name} ►"
            langRow.detail = "◄ ${subtitleLanguage(app).name} ►"
            nextRow.detail = "◄ ${if (autoNext(app)) "On" else "Off"} ►"
            followRow.detail = "◄ ${if (autoFollow(app)) "On" else "Off: I choose"} ►"
            liveRow.detail = "◄ ${if (liveTv(app)) "Shown" else "Hidden"} ►"
            newsRow.detail = "◄ ${if (newsOn(app)) "Shown" else "Hidden"} ►"
            comingRow.detail = "◄ ${if (comingOn(app)) "Shown" else "Hidden"} ►"
            app.refresh(screen)
        }
        lateinit var updateRow: Row
        updateRow = Row("Check for updates", action = {
            Updates.checkNow(app) {
                updateRow.label = "Install build ${Updates.available}"
                updateRow.detail = "►"
                app.refresh(screen)
                Updates.install(app)
            }
        })
        themeRow = Row("Theme", action = { Look.nextTheme(app); app.applyLook(); show() })
        sizeRow = Row("Text size", action = { Look.nextSize(app); app.applyLook(); syncOut(app); show() })
        lookRow = Row("Theme, text size and clock", action = {
            val local = !lookLocal(app)
            prefs(app).edit().putBoolean("lookLocal", local).apply()
            // Back to shared: this device's look becomes everyone's.
            if (!local) syncOut(app)
            app.toast(if (local) "This device keeps its own look" else "Your look is now shared with your other devices")
            show()
        })
        clockRow = Row("Clock", action = { Look.nextClock(app); app.updateClock(); show() })
        watchedRow = Row("Watched films in lists", action = { prefs(app).edit().putBoolean("hideWatched", !hideWatched(app)).apply(); syncOut(app); show() })
        followRow = Row("Follow shows I start watching", action = {
            prefs(app).edit().putBoolean("autoFollow", !autoFollow(app)).apply()
            syncOut(app)
            show()
        })
        liveRow = Row("Live TV on Home", action = {
            prefs(app).edit().putBoolean("liveTv", !liveTv(app)).apply()
            syncOut(app)
            Home.redraw()
            show()
        })
        newsRow = Row("News on Home", action = {
            prefs(app).edit().putBoolean("newsOn", !newsOn(app)).apply()
            syncOut(app)
            Home.redraw()
            show()
        })
        comingRow = Row("Coming soon in Browse", action = {
            prefs(app).edit().putBoolean("comingOn", !comingOn(app)).apply()
            syncOut(app)
            Home.redraw()
            show()
        })
        nextRow = Row("Auto-play next episode", action = {
            prefs(app).edit().putBoolean("autoNext", !autoNext(app)).apply()
            syncOut(app)
            show()
        })

        playerRow = Row("Player", action = {
            val next = (players.indexOf(player(app)) + 1) % players.size
            prefs(app).edit().putInt("player", next).apply()
            show()
        })
        langRow = Row("Subtitle language", action = {
            val next = (languages.indexOf(subtitleLanguage(app)) + 1) % languages.size
            prefs(app).edit().putInt("subLang", next).apply()
            syncOut(app)
            show()
        })
        screen = Screen(
            "Settings",
            listOf(
                Row("──── Playback ────"), playerRow, langRow, nextRow, followRow,
                Row("──── Look ────"),
                lookRow, themeRow, sizeRow, clockRow, watchedRow, liveRow, newsRow, comingRow,
                Row("──── Index ────"),
                Row("Arrange your Index", "►", opens = { Arrange.screen(app) }),
                Row("Hide rows", "►", opens = { rowsScreen(app) }),
                Row("──── Rows & streams ────"),
                Row("Your rows (AIOMetadata)", "►", opens = { RowsSetup.screen(app) }),
                Row("Your streams (AIOStreams)", "►", opens = { StreamsSetup.screen(app) }),
                Row(info("Stream speed", SpeedCheck.summary(app)), "►", opens = { HealthScreen.screen(app) }),
                Row("Your add-ons", (Home.addonCount()?.let { "$it  " } ?: "") + "►", opens = { Home.addonsScreen(app) }),
                Row("Where to watch (TMDB key)", (if (tmdbKey(app).isBlank()) "not set" else "set ✓ · ${WhereToWatch.regionName(watchRegion(app))}") + "  ►", opens = { whereToWatch(app) }),
                Row("──── Share ────"),
                Row("Export my setup to another account", "►", opens = { Share.screen(app) }),
                Row("Import a setup from another account", "►", opens = { Share.importScreen(app) }),
                Row("──── About ────"),
                Row(info("App version", BuildConfig.VERSION_NAME)),
                updateRow,
                Row("Is everything working?", "check it all  ►", opens = { HealthScreen.screen(app) }),
                Row("Device check", "►", opens = { DemoMenus.deviceCheck(app) }),
                Row("About Stremonino", "►", opens = { DemoMenus.about() }),
            ),
        )
        screen.position = 1
        screen.onShow = { show() }
        return screen
    }
}
