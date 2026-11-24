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
    private val BOOL_KEYS = listOf("autoNext", "hideWatched")
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
        o.put("updated", now)
        p.edit().putLong("syncedAt", now).apply()
        item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(now))
        History.putRemote(c, item)
    }

    /** Takes the account's settings if they're newer than this device's. Returns true if anything changed. */
    fun syncIn(c: Context): Boolean {
        val p = prefs(c)
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
        e.putStringSet("hiddenRows", hidden)
        e.putLong("syncedAt", updated).apply()
        if (hidden != remoteHidden) syncOut(c)
        return true
    }

    /** Lists show watched films with a ✓, or leave them out. */
    fun hideWatched(c: Context) = prefs(c).getBoolean("hideWatched", false)

    fun rowKey(a: Addon, c: Addon.Catalog) = Folders.rowKey(a, c)

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
        lateinit var themeRow: Row
        lateinit var sizeRow: Row
        lateinit var clockRow: Row
        lateinit var watchedRow: Row
        lateinit var lookRow: Row

        fun show() {
            lookRow.detail = "◄ ${if (lookLocal(app)) "This device only" else "Same on all devices"} ►"
            themeRow.detail = "◄ ${Look.theme(app).name} ►"
            sizeRow.detail = "◄ ${Look.size(app).name} ►"
            clockRow.detail = "◄ ${Look.clock(app).name} ►"
            watchedRow.detail = "◄ ${if (hideWatched(app)) "Hide them" else "Show with ✓"} ►"
            playerRow.detail = "◄ ${player(app).name} ►"
            langRow.detail = "◄ ${subtitleLanguage(app).name} ►"
            nextRow.detail = "◄ ${if (autoNext(app)) "On" else "Off"} ►"
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
                Row("──── Playback ────"), playerRow, langRow, nextRow,
                Row("──── Look ────"),
                lookRow, themeRow, sizeRow, clockRow, watchedRow,
                Row("Arrange your Index", "►", opens = { Arrange.screen(app) }),
                Row("Hide rows", "►", opens = { rowsScreen(app) }),
                Row("──── Setup ────"),
                Row("Your add-ons", (Home.addonCount()?.let { "$it  " } ?: "") + "►", opens = { Home.addonsScreen(app) }),
                Row("Your rows: categories and setup", "►", opens = { RowsSetup.screen(app) }),
                Row("Your streams: set up AIOStreams", "►", opens = { StreamsSetup.screen(app) }),
                Row("Export my setup to another account", "►", opens = { Share.screen(app) }),
                Row("Import a setup from another account", "►", opens = { Share.importScreen(app) }),
                Row("Device check", "►", opens = { DemoMenus.deviceCheck(app) }),
                Row("──── About ────"),
                Row(info("App version", BuildConfig.VERSION_NAME)),
                updateRow,
                Row("About Stremonino", "►", opens = { DemoMenus.about() }),
            ),
        )
        screen.position = 1
        screen.onShow = { show() }
        return screen
    }
}
