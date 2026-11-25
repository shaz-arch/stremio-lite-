package app.stremiolite

/** Settings › Your keys › Check all my keys: each key with its own service. */
object KeysCheck {
    /** The key checks for this profile ([withTmdb] false when Is everything working? already checks TMDB). */
    fun checks(app: MainActivity, withTmdb: Boolean = true) = KeyCheck.checks(
        if (withTmdb) Settings.tmdbKey(app) else "", Settings.debridKeys(app), Settings.mdblistKey(app), Settings.geminiKey(app),
    ) { Net.describe(it) }

    /** Checks one key straight after it's entered and says how it went. */
    fun quick(app: MainActivity, name: String, check: () -> Health.Outcome) {
        Net.async({ check() }) { r ->
            val o = r.getOrElse { Health.Outcome(false, Net.describe(it)) }
            app.toast("$name: " + (if (o.ok) "✓ " else "✗ ") + o.text)
        }
    }

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        var checks: List<Health.Check> = emptyList()
        var results = mutableMapOf<Int, Health.Outcome>()
        var round = 0
        var again: () -> Unit = {}
        fun build() {
            val rows = mutableListOf<Row>()
            if (checks.isEmpty()) rows += Row("No keys set yet (Your keys).")
            checks.forEachIndexed { i, c ->
                val r = results[i]
                rows += Row(c.name, when { r == null -> "checking…"; r.ok -> "✓ " + r.text; else -> "✗ " + r.text })
                if (r != null && r.help.isNotEmpty()) rows += Row("   " + r.help)
            }
            rows += Row("────────────────────")
            rows += Row("Check again", action = { again() })
            rows += Row("Nothing is changed: each service is only asked about your account.")
            s.rows = rows
            app.refresh(s)
        }
        fun run() {
            val g = ++round
            results = mutableMapOf()
            checks = checks(app)
            build()
            checks.forEachIndexed { i, c ->
                Net.async({ c.run() }) { r ->
                    if (g != round) return@async
                    results[i] = r.getOrElse { Health.Outcome(false, Net.describe(it)) }
                    build()
                }
            }
        }
        again = { run() }
        s = Screen("Check my keys", emptyList())
        s.onShow = { if (checks.isEmpty() && round == 0) run() }
        return s
    }
}
