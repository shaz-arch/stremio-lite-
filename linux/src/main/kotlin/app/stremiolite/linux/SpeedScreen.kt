package app.stremiolite.linux

import app.stremiolite.Health
import app.stremiolite.SpeedTest

/** Settings › Stream speed: your usual speed, the last few tests, and a test now. */
object SpeedScreen {
    fun screen(): Screen {
        lateinit var s: Screen
        var result: Health.Outcome? = null
        var testing = false
        var run: () -> Unit = {}
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK)
        fun build() {
            val h = SpeedCheck.history()
            val usual = SpeedTest.typical(h)
            val rows = mutableListOf(
                Row(if (usual == null) "Not measured yet." else SpeedTest.advice(usual)),
                Row("────────────────────"),
                Row(if (testing) "Testing… (a few seconds)" else "Test now", action = { if (!testing) run() }),
            )
            result?.let { r -> rows += Row((if (r.ok) "✓ " else "✗ ") + r.text); if (!r.ok && r.help.isNotEmpty()) rows += Row("   " + r.help) }
            if (h.isNotEmpty()) {
                rows += Row("──── Recent tests ────")
                h.reversed().forEach { t -> rows += Row(info(fmt.format(java.util.Date(t.at)), "%.0f Mbps".format(java.util.Locale.UK, t.mbps))) }
            }
            s.rows = rows
            App.refresh()
        }
        run = {
            val addons = (Home.loaded() ?: emptyList()).filter { !it.isLocal && it.supports("stream", "movie", Health.TEST_ID) }
            if (addons.isEmpty()) { result = Health.Outcome(false, "no stream add-on to test with"); build() }
            else {
                testing = true; result = null; build()
                val check = Health.speedCheck(addons, { Net.describe(it) }) { mbps -> App.post { SpeedCheck.record(mbps) } }
                App.async({ check.run() }) { r -> testing = false; result = r.getOrNull(); build() }
            }
        }
        s = Screen("Stream speed", emptyList())
        s.onShow = { build() }
        s.pos = 3
        return s
    }
}
