package app.stremiolite

/** Settings › Stream speed: your usual speed, the last few tests, and a test now. */
object SpeedScreen {
    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        var result: Health.Outcome? = null
        var testing = false
        var run: () -> Unit = {}
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK)
        fun build() {
            val h = SpeedCheck.history(app)
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
            app.refresh(s)
        }
        run = {
            val addons = (Home.loadedAddons() ?: emptyList()).filter { !it.isLocal && it.supports("stream", "movie", Health.TEST_ID) }
            if (addons.isEmpty()) { result = Health.Outcome(false, "no stream add-on to test with"); build() }
            else {
                testing = true; result = null; build()
                val check = Health.speedCheck(addons, { Net.describe(it) }) { mbps -> app.runOnUiThread { SpeedCheck.record(app, mbps) } }
                Net.async({ check.run() }) { r -> testing = false; result = r.getOrNull(); build() }
            }
        }
        s = Screen("Stream speed", emptyList())
        s.onShow = { build() }
        s.position = 3
        return s
    }
}
