package app.stremiolite.linux

import app.stremiolite.Language
import app.stremiolite.LibraryItems
import app.stremiolite.Languages
import app.stremiolite.Pref
import app.stremiolite.Stream
import app.stremiolite.Sync
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Files in ~/.config/stremonino (moved from ~/.config/stremio-lean on first run): the sign-in, settings,
 * and caches of the account's data. The first profile uses the folder itself; others get profiles/<id>/.
 */
object Store {
    val root: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        val old = File(base, "stremio-lean")
        val now = File(base, "stremonino")
        if (!now.exists() && old.isDirectory) old.renameTo(now)
        now.apply { mkdirs() }
    }

    private var current: File? = null

    /** The current profile's folder. */
    val dir: File get() = current ?: root

    fun use(d: File) { d.mkdirs(); current = d }

    fun file(name: String) = File(dir, name)

    fun readJson(name: String): JSONObject = runCatching { JSONObject(file(name).readText()) }.getOrDefault(JSONObject())

    fun writeJson(name: String, o: JSONObject) {
        val f = file(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(o.toString(2))
        tmp.renameTo(f)
        if (name == "account.json") runCatching { f.setReadable(false, false); f.setReadable(true, true) }
    }
}

/** The signed-in Stremio account. */
object Account {
    private var cache: JSONObject? = null
    private fun data() = cache ?: Store.readJson("account.json").also { cache = it }

    val authKey: String? get() = data().optString("authKey").ifEmpty { null }
    val email: String get() = data().optString("email")

    fun save(authKey: String, email: String) {
        val o = JSONObject().put("authKey", authKey).put("email", email)
        cache = o
        Store.writeJson("account.json", o)
    }

    /** Forgets what's in memory (after switching profile). */
    fun reload() { cache = null }

    fun clear() {
        cache = JSONObject()
        Store.file("account.json").delete()
        Store.file("addons.json").delete()
        Store.file("library.json").delete()
    }
}

/** Settings on this computer. Theme, subtitle language and hidden rows are shared through the account. */
object Prefs {
    private var cache: JSONObject? = null
    private fun data() = cache ?: Store.readJson("settings.json").also { cache = it }
    private fun save() = Store.writeJson("settings.json", data())

    var theme: Int
        get() = data().optInt("theme", 0)
        set(v) { data().put("theme", v); save() }
    var subLang: Int
        get() = data().optInt("subLang", 1)
        set(v) { data().put("subLang", v); save() }
    var autoNext: Boolean
        get() = data().optBoolean("autoNext", true)
        set(v) { data().put("autoNext", v); save() }
    /** Follow a show for new episodes as soon as you start watching it (off: you choose which). */
    var autoFollow: Boolean
        get() = data().optBoolean("autoFollow", false)
        set(v) { data().put("autoFollow", v); save() }
    /** Play what's sent with "Send to TV" (this computer only; off: only the TV plays it). */
    var downloadCap: Int
        get() = data().optInt("downloadCap", 3)
        set(v) { data().put("downloadCap", v); save() }
    var downloadDeleteWatched: Boolean
        get() = data().optBoolean("downloadDeleteWatched", false)
        set(v) { data().put("downloadDeleteWatched", v); save() }
    var skipIntros: Boolean
        get() = data().optBoolean("skipIntros", true)
        set(v) { data().put("skipIntros", v); save() }
    var receiveSends: Boolean
        get() = data().optBoolean("receiveSends", false)
        set(v) { data().put("receiveSends", v); save() }
    /** My files: your own folders (this computer only). */
    /** Drives you've taken off Home on this computer ("gdrive", "myfiles", "phone"). */
    var drivesOffHome: Set<String>
        get() = data().optJSONArray("drivesOffHome")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        set(v) { data().put("drivesOffHome", org.json.JSONArray(v.toList())); save() }
    var myFolders: List<String>
        get() = data().optJSONArray("myFolders")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        set(v) { data().put("myFolders", org.json.JSONArray(v)); save() }
    /** Where you got to in each of your own files (ms), and the ones watched to the end. */
    fun fileResume(path: String): Long = data().optJSONObject("fileResume")?.optLong(path, 0L) ?: 0L
    fun fileWatched(path: String): Boolean = data().optJSONObject("fileWatched")?.optBoolean(path, false) ?: false
    fun setFileProgress(path: String, pos: Long, watched: Boolean) {
        val r = data().optJSONObject("fileResume") ?: org.json.JSONObject().also { data().put("fileResume", it) }
        val w = data().optJSONObject("fileWatched") ?: org.json.JSONObject().also { data().put("fileWatched", it) }
        if (watched || pos <= 0) r.remove(path) else r.put(path, pos)
        if (watched) w.put(path, true)
        save()
    }
    /** Play a link: recent links on this computer (link, title), newest first. */
    var recentLinks: List<Pair<String, String>>
        get() = data().optJSONArray("recentLinks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("url") to it.optString("title") } }.orEmpty()
        set(v) { data().put("recentLinks", org.json.JSONArray(v.map { org.json.JSONObject().put("url", it.first).put("title", it.second) })); save() }
    var castDlna: Boolean
        get() = data().optBoolean("castDlna", false)
        set(v) { data().put("castDlna", v); save() }
    var shareFiles: Boolean
        get() = data().optBoolean("shareFiles", false)
        set(v) { data().put("shareFiles", v); save() }
    var shareKey: String
        get() = data().optString("shareKey", "").ifEmpty { java.util.UUID.randomUUID().toString().replace("-", "").take(20).also { data().put("shareKey", it); save() } }
        set(v) { data().put("shareKey", v); save() }

    /** Google Drive sign-in on this computer (never synced). */
    var gdriveRefresh: String
        get() = data().optString("gdriveRefresh", "")
        set(v) { data().put("gdriveRefresh", v); save() }
    var forYou: String
        get() = data().optString("forYou", "")
        set(v) { data().put("forYou", v); save() }
    var sentHandled: Long
        get() = data().optLong("sentHandled", 0L)
        set(v) { data().put("sentHandled", v); save() }
    var clock: Int
        get() = data().optInt("clock", 0)
        set(v) { data().put("clock", v); save() }
    /** Ricing: lines, frame, banner and bars ([app.stremiolite.Rice.Config]), shared like the theme. */
    var rice: app.stremiolite.Rice.Config
        get() = app.stremiolite.Rice.read(data().optString("rice", "").ifEmpty { null })
        set(v) { data().put("rice", v.json()); save() }
    /** Your own colours (#rrggbb ×6), shared like the theme. */
    var customTheme: String
        get() = data().optString("customTheme", "")
        set(v) { data().put("customTheme", v); save() }
    /** Use the terminal's own colours (kitty's theme, its see-through background): this computer only. */
    var termColours: Boolean
        get() = data().optBoolean("termColours", false)
        set(v) { data().put("termColours", v); save() }
    var hideWatched: Boolean
        get() = data().optBoolean("hideWatched", false)
        set(v) { data().put("hideWatched", v); save() }
    var player: String
        get() = data().optString("player", "mpv").ifBlank { "mpv" }
        set(v) { data().put("player", v); save() }
    var playerArgs: String
        get() = data().optString("playerArgs", "")
        set(v) { data().put("playerArgs", v); save() }
    var syncedAt: Long
        get() = data().optLong("syncedAt", 0)
        set(v) { data().put("syncedAt", v); save() }
    /** Your arrangement of the Index (see Layout), shared with the other devices. */
    var layout: String
        get() = data().optString("layout", "")
        set(v) { data().put("layout", v); save() }

    /** Live TV on Home (on unless turned off). */
    /** Next watch's view: "status" (default) or "order". */
    var nextView: String
        get() = data().optString("nextView", "status")
        set(v) { data().put("nextView", v); save() }

    /** Set up Stremonino's progress on this computer. */
    var setupDone: Set<String>
        get() = data().optJSONArray("setupDone")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        set(v) { data().put("setupDone", org.json.JSONArray(v.toList())); save() }

    var liveTv: Boolean
        get() = data().optBoolean("liveTv", true)
        set(v) { data().put("liveTv", v); save() }
    /** Your M3U playlists for Live TV: follow the account, but never go out with "Share my setup". */
    var playlists: List<app.stremiolite.LiveTv.Playlist>
        get() = app.stremiolite.LiveTv.playlists(data().optString("playlists", ""))
        set(v) { data().put("playlists", app.stremiolite.LiveTv.playlistsJson(v)); save() }

    /** Your favourite Live TV channels, from any list (synced, never shared). */
    var favourites: List<app.stremiolite.LiveTv.Channel>
        get() = app.stremiolite.LiveTv.favourites(data().optString("liveFavs", ""))
        set(v) { data().put("liveFavs", app.stremiolite.LiveTv.favouritesJson(v)); save() }

    /** What's hidden in Live TV's built-in Free channels (synced to your devices). */
    var freeHidden: Set<String>
        // Kept as text, as on Android, so it syncs between them.
        get() = runCatching { JSONArray(data().optString("liveFreeHide", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        set(v) { data().put("liveFreeHide", JSONArray(v.toList()).toString()); save() }

    /** News and Coming soon on Home (on unless turned off). */
    var newsOn: Boolean
        get() = data().optBoolean("newsOn", true)
        set(v) { data().put("newsOn", v); save() }

    /** Lines taken off Home on this computer (Settings › Home › Show on Home). */
    var homeHidden: Set<String>
        get() = data().optJSONArray("homeHidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        set(v) { data().put("homeHidden", org.json.JSONArray(v.toList())); save() }
    fun onHome(key: String): Boolean = when (key) {
        "live" -> liveTv; "news" -> newsOn; "coming" -> comingOn; "sleep" -> sleepOnHome
        else -> key !in homeHidden
    }
    fun setOnHome(key: String, on: Boolean) {
        when (key) {
            "live" -> liveTv = on; "news" -> newsOn = on; "coming" -> comingOn = on; "sleep" -> sleepOnHome = on
            else -> homeHidden = if (on) homeHidden - key else homeHidden + key
        }
        syncOut()
    }
    var streamProblem: String
        get() = data().optString("streamProblem", "")
        set(v) { data().put("streamProblem", v); save() }
    var streamCheckAt: Long
        get() = data().optLong("streamCheckAt", 0)
        set(v) { data().put("streamCheckAt", v); save() }
    fun ratingsConfig() = app.stremiolite.Ratings.Config(
        app.stremiolite.Ratings.Config.orderOf(data().optString("ratingsOrder", "").ifEmpty { null }),
        data().optBoolean("ratingsVotes", false), data().optBoolean("ratingsPercent", false),
    )
    fun setRatings(order: List<String>? = null, votes: Boolean? = null, percent: Boolean? = null) {
        order?.let { data().put("ratingsOrder", it.joinToString(",")) }
        votes?.let { data().put("ratingsVotes", it) }
        percent?.let { data().put("ratingsPercent", it) }
        save(); syncOut()
    }
    var finderSort: Int
        get() = data().optInt("finderSort", 0)
        set(v) { data().put("finderSort", v); save() }
    var sleepOnHome: Boolean
        get() = data().optBoolean("sleepOnHome", false)
        set(v) { data().put("sleepOnHome", v); save() }
    var comingOn: Boolean
        get() = data().optBoolean("comingOn", true)
        set(v) { data().put("comingOn", v); save() }
    /** News feeds you've added (kept as text, as on Android, so they sync between them). */
    var newsFeeds: List<app.stremiolite.News.Feed>
        get() = app.stremiolite.News.feeds(data().optString("newsFeeds", ""))
        set(v) { data().put("newsFeeds", app.stremiolite.News.feedsJson(v)); save() }
    /** Starter feeds switched off (their links). */
    var newsHidden: Set<String>
        get() = runCatching { JSONArray(data().optString("newsHide", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        set(v) { data().put("newsHide", JSONArray(v.toList()).toString()); save() }

    /** The film list Coming soon reads ("" = automatic). */
    var comingFrom: String
        get() = data().optString("comingFrom", "")
        set(v) { data().put("comingFrom", v); save() }
    var comingFromShows: String
        get() = data().optString("comingFromShows", "")
        set(v) { data().put("comingFromShows", v); save() }

    /** Your TMDB key, for Where to watch (synced, never shared). */
    /** TorBox and MDBList keys, remembered on this computer for the next setup (not in SHARED: never synced). */
    /** Debrid keys in your order (a TorBox key from an earlier build counts too). */
    var debridKeys: List<Pair<String, String>>
        get() {
            val d = data()
            if (!d.has("debridKeys")) d.optString("torboxKey").takeIf { it.isNotBlank() }?.let { return listOf("torbox" to it) }
            return app.stremiolite.StreamSetup.parseKeys(d.optString("debridKeys"))
        }
        set(v) { data().put("debridKeys", app.stremiolite.StreamSetup.writeKeys(v)).remove("torboxKey"); save() }
    var torboxKey: String
        get() = debridKeys.firstOrNull { it.first == "torbox" }?.second ?: ""
        set(v) { debridKeys = app.stremiolite.StreamSetup.withKey(debridKeys, "torbox", v) }
    var mdblistKey: String
        get() = data().optString("mdblistKey", "")
        set(v) { data().put("mdblistKey", v); save() }
    var geminiKey: String
        get() = data().optString("geminiKey", "")
        set(v) { data().put("geminiKey", v); save(); syncOut() }

    var tmdbKey: String
        get() = data().optString("tmdbKey", "")
        set(v) { data().put("tmdbKey", v); save(); app.stremiolite.WhereToWatch.forget() }
    /** The country for Where to watch: "GB" or "US". */
    var watchRegion: String
        get() = data().optString("watchRegion", "GB")
        set(v) { data().put("watchRegion", v); save() }

    /** Game scores (this profile, this computer: not in SHARED, so never synced). */
    var games: String?
        get() = data().optString("games").ifEmpty { null }
        set(v) { data().put("games", v); save() }

    /** New episodes already seen, for the line on Home (this computer only, never synced). */
    var episodesSeen: String
        get() = data().optString("epSeen", "")
        set(v) { data().put("epSeen", v); save() }

    /** Episodes dismissed from Ready (this computer only). */
    var readyGone: String
        get() = data().optString("readyGone", "")
        set(v) { data().put("readyGone", v); save() }

    /** When you last looked at films that became ready (this computer only, never synced). */
    var readySeen: Long
        get() = data().optLong("readySeen", 0)
        set(v) { data().put("readySeen", v); save() }

    /** Channels played lately (this computer only: not in SHARED, so never synced). */
    var recent: List<app.stremiolite.LiveTv.Channel>
        get() = app.stremiolite.LiveTv.favourites(data().optString("liveRecent", ""))
        set(v) { data().put("liveRecent", app.stremiolite.LiveTv.favouritesJson(v)); save() }

    /** Your TV guide link (XMLTV) for Live TV, or "" (synced, never shared). */
    var liveGuide: String
        get() = data().optString("liveGuide", "")
        set(v) { data().put("liveGuide", v); save() }

    var hiddenRows: Set<String>
        get() = data().optJSONArray("hiddenRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        set(v) { data().put("hiddenRows", JSONArray(v.toList())); save() }

    /** This profile's Live TV (playlists, favourites, guide link), for copying. */
    fun live() = app.stremiolite.LiveTv.Bundle(playlists, favourites, liveGuide)

    /** Adds someone's Live TV to this profile's and sends it to the account. */
    fun addLive(theirs: app.stremiolite.LiveTv.Bundle) {
        val b = theirs.into(live())
        playlists = b.playlists; favourites = b.favourites; liveGuide = b.guide
        syncOut()
    }

    fun language(): Language = Languages.all.getOrElse(subLang) { Languages.all[1] }

    fun reload() { cache = null }

    /** The shared settings, as "Share my setup" sends them (same shape as the Android app's). */
    fun exportShared(): JSONObject {
        val d = data()
        val o = JSONObject()
        SHARED.forEach { k -> if (d.has(k) && k !in NOT_SHARED) o.put(k, d.opt(k)) }
        return o.put("hiddenRows", JSONArray(hiddenRows.toList()))
    }

    /** Takes someone's shared settings, plus their hidden rows and [alsoHide]. */
    fun importShared(o: JSONObject, alsoHide: Set<String>) {
        val d = data()
        SHARED.forEach { k -> if (o.has(k) && k !in NOT_SHARED) d.put(k, o.opt(k)) }
        val theirs = o.optJSONArray("hiddenRows")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        d.put("hiddenRows", JSONArray((hiddenRows + theirs + alsoHide).toList()))
        save()
        syncOut()
    }

    // ---- shared with the other devices (the "stremiolite:settings" record) ------------------

    private const val SYNC_ID = "stremiolite:settings"

    /** Shared settings this build knows; one this computer has never had is always taken from the account. */
    private val SHARED = listOf("theme", "subLang", "autoNext", "clock", "hideWatched", "layout", "autoFollow", "liveTv", "playlists", "liveFavs", "liveGuide", "liveFreeHide", "newsOn", "comingOn", "sleepOnHome", "homeHidden", "ratingsOrder", "ratingsVotes", "ratingsPercent", "newsFeeds", "newsHide", "tmdbKey", "watchRegion", "geminiKey", "rice", "customTheme")
    /** Synced with your own devices, but never sent to someone else with "Share my setup". */
    private val NOT_SHARED = setOf("playlists", "liveFavs", "liveGuide", "tmdbKey", "geminiKey")
    /** The look: shared by default, or kept to this computer ("lookLocal", which itself never syncs). */
    private val LOOK = setOf("theme", "clock", "rice", "customTheme")

    var lookLocal: Boolean
        get() = data().optBoolean("lookLocal", false)
        set(v) { data().put("lookLocal", v); save() }

    private fun shares(k: String) = k !in LOOK || !lookLocal

    /** Takes the account's shared settings if they're newer than this computer's. */
    fun syncIn(): Boolean {
        // Copied into this profile while another was in use: send it to the account first.
        if (data().optBoolean("pendingSync")) {
            data().remove("pendingSync"); save()
            syncOut()
            return false
        }
        val raw = Lib.raw(SYNC_ID) ?: return false
        val o = runCatching { JSONObject(raw.optString("name")) }.getOrNull() ?: return false
        val updated = o.optLong("updated")
        val d = data()
        val newer = updated > syncedAt
        var changed = false
        for (k in SHARED) {
            if (!shares(k) || !o.has(k) || (!newer && d.has(k))) continue
            if (d.opt(k) != o.opt(k)) { d.put(k, o.opt(k)); changed = true }
        }
        // The Gemini key, if this computer doesn't have one.
        if (d.optString("geminiKey").isBlank() && o.optString("geminiKey").isNotBlank()) { d.put("geminiKey", o.optString("geminiKey")); changed = true }
        if (newer) o.optJSONArray("hiddenRows")?.let { d.put("hiddenRows", it); changed = true }
        val accName = o.optString("profileName")
        // Take the account's name when it's newer, or always if this computer named the profile itself ("Profile 1").
        if (!app.stremiolite.ProfileName.isAuto(accName) && (newer || app.stremiolite.ProfileName.isAuto(Profiles.current()?.name ?: ""))) Profiles.takeName(accName)
        if (newer) d.put("syncedAt", updated)
        save()
        // Set up before names were synced: a computer that's synced before sends its profile name once.
        if (!newer && !o.has("profileName") && syncedAt > 0) syncOut()
        return changed
    }

    /** Sends theme, clock, subtitle language, auto-next, watched-films choice and hidden rows, keeping anything else the record holds. */
    fun syncOut() {
        val now = System.currentTimeMillis()
        val raw = Lib.raw(SYNC_ID)
        val o = raw?.let { runCatching { JSONObject(it.optString("name")) }.getOrNull() } ?: JSONObject()
        // Only settings chosen on this computer (or already taken from the account): never a default.
        val d = data()
        SHARED.forEach { k -> if (d.has(k) && shares(k) && !(k == "geminiKey" && d.optString(k).isBlank())) o.put(k, d.opt(k)) }
        // The profile's name, so every device signed in to this account shows the same one.
        Profiles.current()?.name?.takeIf { !app.stremiolite.ProfileName.isAuto(it) }?.let { o.put("profileName", it) }
        o.put("hiddenRows", JSONArray(hiddenRows.toList())).put("updated", now)
        syncedAt = now
        val item = raw ?: Sync.newItem(SYNC_ID, "other", "{}")
        item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(now))
        Lib.put(item)
    }
}

object Look {
    /** The shared choices: the six, then "Your own" (index 6). */
    val themeCount get() = themes.size + 1
    const val OWN = 6

    fun colours(): app.stremiolite.Rice.Colours = app.stremiolite.Rice.readColours(Prefs.customTheme) ?: app.stremiolite.Rice.DEFAULT

    fun theme(): Theme = when {
        Prefs.termColours -> terminalTheme
        Prefs.theme == OWN -> ownTheme(colours())
        else -> themes.getOrElse(Prefs.theme) { themes[0] }
    }

    fun themeName() = when { Prefs.termColours -> "Terminal colours"; Prefs.theme == OWN -> "Your own"; else -> theme().name }

    private fun c(hex: String) = app.stremiolite.Rice.rgb(hex).let { (r, g, b) -> com.googlecode.lanterna.TextColor.RGB(r, g, b) }
    private fun ownTheme(k: app.stremiolite.Rice.Colours) = Theme("Your own", c(k.bg), c(k.text), c(k.dim), c(k.title), c(k.bar), c(k.barText))

    /** kitty's own palette: its background (see-through if kitty is), text, color8, color3 and color4; the cursor line reversed. */
    private val terminalTheme = Theme("Terminal colours", com.googlecode.lanterna.TextColor.ANSI.DEFAULT, com.googlecode.lanterna.TextColor.ANSI.DEFAULT,
        com.googlecode.lanterna.TextColor.ANSI.BLACK_BRIGHT, com.googlecode.lanterna.TextColor.ANSI.YELLOW, com.googlecode.lanterna.TextColor.ANSI.BLUE,
        com.googlecode.lanterna.TextColor.ANSI.DEFAULT, reverse = true)

    // ---- the theme file (~/.config/stremonino/theme.conf) ----------------------------------------

    val themeFile: java.io.File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        java.io.File(base, "stremonino/theme.conf")
    }
    private var seen = 0L
    private var checkedAt = 0L

    /** Sets your own colours (and writes the file). */
    fun setColours(k: app.stremiolite.Rice.Colours) {
        Prefs.customTheme = k.joined(); Prefs.theme = OWN; Prefs.syncOut()
        runCatching { themeFile.parentFile.mkdirs(); themeFile.writeText(k.conf()); seen = themeFile.lastModified() }
    }

    /** The file changed (edited by hand): take its colours. Called from the main loop; true if it did. */
    fun checkFile(): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 2_000) return false
        checkedAt = now
        val f = themeFile
        if (!f.exists()) {
            // First time with your own colours: write them out so there's a file to edit.
            if (Prefs.theme == OWN) runCatching { f.parentFile.mkdirs(); f.writeText(colours().conf()); seen = f.lastModified() }
            return false
        }
        val m = f.lastModified()
        if (seen == 0L) { seen = m; if (Prefs.customTheme.isNotBlank()) return false }
        if (m == seen) return false
        seen = m
        val k = app.stremiolite.Rice.parseConf(runCatching { f.readText() }.getOrDefault(""), colours())
        if (k.joined() == Prefs.customTheme && Prefs.theme == OWN) return false
        Prefs.customTheme = k.joined(); Prefs.theme = OWN; Prefs.syncOut()
        return true
    }

    /** kitty's colours, from ~/.config/kitty/kitty.conf and what it includes (like current-theme.conf). */
    fun kittyColours(): app.stremiolite.Rice.Colours? {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config")
        val dir = java.io.File(base, "kitty")
        val conf = java.io.File(dir, "kitty.conf").takeIf { it.exists() } ?: return null
        return app.stremiolite.Rice.fromKitty(conf.readText(), { p ->
            val f = java.io.File(p.replaceFirst("~", System.getProperty("user.home"))).let { if (it.isAbsolute) it else java.io.File(dir, p) }
            runCatching { f.readText() }.getOrNull()
        })
    }

    /** The same clock choices as the phone and stick (shared through the account); empty pattern = off. */
    class Clock(val name: String, val pattern: String)

    val clocks = listOf(
        Clock("Day/month, 24 h", "dd/MM HH:mm"),
        Clock("Weekday, day/month, 24 h", "EEE dd/MM HH:mm"),
        Clock("Time only, 24 h", "HH:mm"),
        Clock("Day/month, 12 h", "dd/MM h:mm a"),
        Clock("Off", ""),
        // With seconds (added after "Off" so existing choices keep their place in the shared settings).
        Clock("Day/month, 24 h, seconds", "dd/MM HH:mm:ss"),
        Clock("Weekday, day/month, 24 h, seconds", "EEE dd/MM HH:mm:ss"),
        Clock("Time only, 24 h, seconds", "HH:mm:ss"),
        Clock("Day/month, 12 h, seconds", "dd/MM h:mm:ss a"),
    )

    fun clock(): Clock = clocks.getOrElse(Prefs.clock) { clocks[0] }
}

/** The release last played per show, so the next episode keeps the same look and timing. */
object Binge {
    fun lastFor(showId: String): Pref? {
        val v = Store.readJson("binge.json").optString(showId).ifEmpty { return null }
        val p = v.split('|')
        return if (p.size >= 3) Pref(p[0], p[1], p[2]) else null
    }

    fun remember(showId: String, s: Stream) {
        val o = Store.readJson("binge.json")
        o.put(showId, "${s.group}|${s.quality}|${s.source}")
        val keys = o.keys().asSequence().toList()
        if (keys.size > 40) keys.take(keys.size - 40).forEach { o.remove(it) }
        Store.writeJson("binge.json", o)
    }
}

/** What is being played, so the result can be filed against it. */
class Watch(
    val id: String,
    val type: String,
    val name: String,
    val videoId: String,
    val label: String,
    val runtime: String,
    val videoIds: List<String> = emptyList(),
    val nextVideoId: String? = null,
    val nextLabel: String? = null,
)

/**
 * The account's library records: Continue watching, Library, watched marks and resume points,
 * plus the hidden records (shared settings, waiting list). Cached on disk, synced with the account.
 */
object Lib {
    private val records = LinkedHashMap<String, JSONObject>()
    private val unsent = LinkedHashSet<String>()
    private var loaded = false
    private var pulling = false
    var lastPull = 0L
        private set

    private fun ensure() {
        if (loaded) return
        loaded = true
        val o = Store.readJson("library.json")
        o.optJSONArray("records")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { records[it.optString("_id")] = it } }
        o.optJSONArray("unsent")?.let { a -> for (i in 0 until a.length()) unsent += a.optString(i) }
    }

    private fun save() {
        val o = JSONObject().put("records", JSONArray(records.values.toList())).put("unsent", JSONArray(unsent.toList()))
        runCatching { Store.writeJson("library.json", o) }
    }

    fun reset() {
        records.clear(); unsent.clear(); loaded = true; lastPull = 0; fullDone = false
    }

    /** Forgets what's in memory so the next use reads the (new) profile's file. */
    fun unload() {
        records.clear(); unsent.clear(); loaded = false; lastPull = 0; fullDone = false
    }

    fun raw(id: String): JSONObject? { ensure(); return records[id] }

    fun put(item: JSONObject) {
        ensure()
        val id = item.optString("_id")
        records[id] = item
        unsent += id
        save()
        send()
    }

    fun infos(): List<LibraryItems.Info> { ensure(); return records.values.map { LibraryItems.read(it) } }

    fun continueWatching() = infos().filter { it.continuing }.sortedByDescending { it.updated }
    fun library() = infos().filter { it.inLibrary }.sortedByDescending { it.updated }
    fun watchedFilms() = infos().filter { it.filmWatched }.sortedByDescending { it.updated }

    fun info(id: String) = raw(id)?.let { LibraryItems.read(it) }

    fun inLibrary(id: String) = info(id)?.inLibrary == true

    /** Where to resume [videoId], in ms (0 = from the start). */
    fun resumeAt(id: String, videoId: String): Long {
        val i = info(id) ?: return 0
        return if (i.videoId == videoId && i.offset > 60_000 && !i.upNext &&
            !(i.duration > 0 && LibraryItems.finished(i.offset, i.duration))) i.offset else 0
    }

    fun filmWatched(id: String) = info(id)?.filmWatched == true

    /** Episodes the account marks as watched, given the show's episodes in Stremio's order. */
    fun watchedEpisodes(id: String, order: List<String>): Set<String> =
        info(id)?.let { Sync.decodeWatched(it.watchedField, order) } ?: emptySet()

    fun record(w: Watch, pos: Long, dur: Long, positionKnown: Boolean, done: Boolean) {
        ensure()
        if (done) Watched.add(w)
        val item = LibraryItems.update(
            existing = records[w.id], id = w.id, type = w.type, name = w.name, videoId = w.videoId,
            pos = pos, dur = dur, done = done, positionKnown = positionKnown,
            videoIds = w.videoIds, alsoDone = emptySet(), nextVideoId = w.nextVideoId,
        )
        put(item)
    }

    /** Takes a film or episode off the watched list ([videoIds]: the show's episodes in Stremio's order). */
    fun unwatch(id: String, type: String, videoId: String, videoIds: List<String>) {
        ensure()
        val item = records[id] ?: return
        put(LibraryItems.unwatch(item, videoId, if (type == "movie") emptyList() else videoIds))
    }

    fun toggleLibrary(id: String, type: String, name: String): Boolean {
        ensure()
        val had = inLibrary(id)
        val item = records[id] ?: Sync.newItem(id, type, name)
        item.put("removed", had).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
        put(item)
        return !had
    }

    /** Stores several changed records and sends them in one go. */
    fun putAll(items: List<JSONObject>) {
        ensure()
        items.forEach { val id = it.optString("_id"); records[id] = it; unsent += id }
        save()
        send()
    }

    /** Empties Continue watching (resume points); watched marks stay. */
    fun clearContinue() {
        val now = Sync.iso(System.currentTimeMillis())
        putAll(continueWatching().mapNotNull { i -> records[i.id]?.also { it.optJSONObject("state")?.put("timeOffset", 0); it.put("_mtime", now) } })
    }

    /** Empties the Library; history and watched marks stay. */
    fun clearLibrary() {
        val now = Sync.iso(System.currentTimeMillis())
        putAll(library().mapNotNull { i -> records[i.id]?.also { it.put("removed", true).put("temp", false).put("_mtime", now) } })
    }

    fun removeFromContinue(id: String) {
        ensure()
        val item = records[id] ?: return
        item.optJSONObject("state")?.put("timeOffset", 0)
        item.put("_mtime", Sync.iso(System.currentTimeMillis()))
        put(item)
    }

    private fun send() {
        val key = Account.authKey ?: return
        val ids = unsent.toList()
        if (ids.isEmpty()) return
        val items = ids.mapNotNull { records[it] }.map { JSONObject(it.toString()) }
        App.async({ Sync.push(key, items) }) { r ->
            if (r.isSuccess) {
                unsent.removeAll(ids.toSet())
                save()
            }
        }
    }

    /** Fetches the account's library; [done] runs on the main loop afterwards. */
    /** False until a full pull has happened since the app started (or the profile changed). */
    private var fullDone = false

    /**
     * Brings in changes from the account. Normally only records that changed are fetched (the
     * account lists ids and change times first); the first pull, and [full] ones (Reload), fetch everything.
     */
    fun pull(force: Boolean = false, full: Boolean = false, done: (() -> Unit)? = null) {
        ensure()
        val key = Account.authKey ?: return
        if (pulling || (!force && !full && System.currentTimeMillis() - lastPull < 60_000)) return
        pulling = true
        send()
        val whole = full || !fullDone
        val keep = unsent.toSet()
        val known = if (whole) emptyMap() else records.mapValues { Sync.parseTime(it.value.optString("_mtime")) }
        App.async({
            if (whole) Sync.pull(key) to true
            else {
                // Any trouble with the change list: just do a full pull, as before.
                val meta = runCatching { Sync.meta(key) }.getOrNull()
                if (meta == null) Sync.pull(key) to true
                else {
                    val want = Sync.changed(meta, known, keep)
                    when {
                        want.isEmpty() -> null
                        want.size > 150 -> Sync.pull(key) to true
                        else -> Sync.pullIds(key, want) to false   // only changed records; nothing is dropped
                    }
                }
            }
        }) { r ->
            pulling = false
            r.onSuccess { got ->
                lastPull = System.currentTimeMillis()
                if (whole) fullDone = true
                if (got == null) { done?.invoke(); return@onSuccess }
                val (arr, everything) = got
                if (everything) {
                    val fresh = LinkedHashMap<String, JSONObject>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("_id")
                        if (id.isEmpty()) continue
                        fresh[id] = if (id in keep) records[id] ?: o else o
                    }
                    keep.forEach { id -> records[id]?.let { fresh.putIfAbsent(id, it) } }
                    records.clear()
                    records.putAll(fresh)
                } else {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("_id")
                        if (id.isEmpty() || id in unsent) continue
                        records[id] = o
                    }
                }
                save()
                if (Prefs.syncIn()) App.refresh()
                done?.invoke()
            }.onFailure { e -> App.toast("Couldn't sync your history: ${Net.describe(e)}") }
        }
    }
}


/**
 * Films and episodes finished on this computer (newest first), like the phone's Watched list,
 * plus films the account marks as watched from other devices.
 */
object Watched {
    class Entry(val id: String, val type: String, val name: String, val videoId: String, val label: String, val time: Long)

    private fun read(): List<Entry> {
        val a = Store.readJson("watched.json").optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Entry(it.optString("id"), it.optString("type"), it.optString("name"), it.optString("videoId"), it.optString("label"), it.optLong("time")) }
        }
    }

    private fun write(list: List<Entry>) {
        val a = JSONArray()
        list.take(300).forEach { a.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("videoId", it.videoId).put("label", it.label).put("time", it.time)) }
        Store.writeJson("watched.json", JSONObject().put("list", a))
    }

    fun add(w: Watch) {
        val e = Entry(w.id, w.type, w.name, w.videoId, w.label, System.currentTimeMillis())
        write(listOf(e) + read().filter { !(it.id == e.id && it.videoId == e.videoId) })
    }

    fun remove(id: String, videoId: String) = write(read().filter { !(it.id == id && (it.videoId == videoId || videoId.isEmpty())) })

    fun list(): List<Entry> {
        val mine = read()
        val films = Lib.watchedFilms().filter { f -> mine.none { it.id == f.id } }
            .map { Entry(it.id, it.type, it.name, it.id, "", it.updated) }
        return (mine.filter { it.type != "movie" || Lib.filmWatched(it.id) } + films).sortedByDescending { it.time }
    }
}
