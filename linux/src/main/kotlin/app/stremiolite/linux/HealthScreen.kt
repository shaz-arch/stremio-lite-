package app.stremiolite.linux

import app.stremiolite.Health

/** "Is everything working?": runs [Health]'s checks and shows each result, with what to do about a failure. */
object HealthScreen {
    fun screen(): Screen {
        val summary = Row("")
        lateinit var screen: Screen
        var results = mutableMapOf<Int, Health.Outcome>()
        var checks: List<Health.Check> = emptyList()
        var round = 0
        var again: () -> Unit = {}

        fun build() {
            val rows = mutableListOf(summary, Row("────────────────────"))
            checks.forEachIndexed { i, c ->
                val r = results[i]
                rows += Row(c.name, when { r == null -> "checking…"; r.ok -> "✓ " + r.text; else -> "✗ " + r.text })
                if (r != null && !r.ok && r.help.isNotEmpty()) rows += Row("   " + r.help)
            }
            rows += Row("────────────────────")
            rows += Row("Check again", action = { again() })
            rows += Row("(Streams are checked with ${Health.TEST_NAME}.)")
            summary.label = Health.summary(results.size, checks.size, results.values.count { !it.ok })
            screen.rows = rows
            App.refresh()
        }

        fun run() {
            val g = ++round
            results = mutableMapOf()
            checks = Health.checks(Account.authKey, Home.loaded(), Prefs.tmdbKey) { Net.describe(it) }
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

        screen = Screen("Is everything working?", listOf(summary))
        screen.onShow = { if (checks.isEmpty()) run() }
        return screen
    }
}
