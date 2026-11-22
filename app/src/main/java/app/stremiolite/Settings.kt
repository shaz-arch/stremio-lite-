package app.stremiolite

import android.content.Context

/** The two choices people actually need: which player, and which subtitle language. */
object Settings {
    private const val PREFS = "settings"

    class Language(val name: String, val codes: List<String>)

    /** Off first, then the most likely choices. Codes cover both 3-letter and 2-letter forms. */
    val languages = listOf(
        Language("Off", emptyList()),
        Language("English", listOf("eng", "en")),
        Language("Urdu", listOf("urd", "ur")),
        Language("Hindi", listOf("hin", "hi")),
        Language("Punjabi", listOf("pan", "pa")),
        Language("Arabic", listOf("ara", "ar")),
        Language("French", listOf("fre", "fra", "fr")),
        Language("Spanish", listOf("spa", "es")),
        Language("German", listOf("ger", "deu", "de")),
        Language("Italian", listOf("ita", "it")),
        Language("Portuguese", listOf("por", "pt", "pob")),
        Language("Polish", listOf("pol", "pl")),
        Language("Turkish", listOf("tur", "tr")),
        Language("Dutch", listOf("dut", "nld", "nl")),
    )

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

    fun rowKey(a: Addon, c: Addon.Catalog) = "${a.name}|${c.type}|${c.id}"

    fun hiddenRows(c: Context): Set<String> = prefs(c).getStringSet("hiddenRows", emptySet()) ?: emptySet()

    private fun setHidden(c: Context, key: String, hide: Boolean) {
        val now = hiddenRows(c).toMutableSet()
        if (hide) now += key else now -= key
        prefs(c).edit().putStringSet("hiddenRows", now).apply()
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
                Row("Show all rows", action = { prefs(app).edit().remove("hiddenRows").apply(); screen.rows = build(); app.refresh(screen) }),
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

        fun show() {
            themeRow.detail = "◄ ${Look.theme(app).name} ►"
            sizeRow.detail = "◄ ${Look.size(app).name} ►"
            clockRow.detail = "◄ ${Look.clock(app).name} ►"
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
        sizeRow = Row("Text size", action = { Look.nextSize(app); app.applyLook(); show() })
        clockRow = Row("Clock", action = { Look.nextClock(app); app.updateClock(); show() })
        nextRow = Row("Auto-play next episode", action = {
            prefs(app).edit().putBoolean("autoNext", !autoNext(app)).apply()
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
            show()
        })
        screen = Screen(
            "Settings",
            listOf(
                Row("Press OK on a line to change it"), Row("────────────────────"), playerRow, langRow, nextRow,
                Row("────────────────────"),
                themeRow, sizeRow, clockRow,
                Row("Hide rows", "►", opens = { rowsScreen(app) }),
                Row("────────────────────"),
                Row(info("App version", BuildConfig.VERSION_NAME)),
                updateRow,
            ),
        )
        screen.position = 2
        screen.onShow = { show() }
        return screen
    }
}
