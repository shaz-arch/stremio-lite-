package app.stremiolite.linux

import app.stremiolite.Health
import app.stremiolite.KeyCheck

/** Settings › Your keys › Check all my keys: each key with its own service. */
object KeysCheck {
    fun checks(withTmdb: Boolean = true) = KeyCheck.checks(
        if (withTmdb) Prefs.tmdbKey else "", Prefs.debridKeys, Prefs.mdblistKey, Prefs.geminiKey,
    ) { Net.describe(it) }

    fun screen(): Screen {
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
            App.refresh()
        }
        fun run() {
            val g = ++round
            results = mutableMapOf()
            checks = checks()
            build()
            checks.forEachIndexed { i, c ->
                App.async({ c.run() }) { r ->
                    if (g != round) return@async
                    results[i] = r.getOrElse { Health.Outcome(false, Net.describe(it)) }
                    build()
                }
            }
        }
        again = { run() }
        s = Screen("Check my keys", emptyList())
        s.onShow = { if (round == 0) run() }
        return s
    }
}
