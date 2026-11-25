package app.stremiolite

import android.content.Context

/** Android side of [SpeedTest]: the device's speed history, and a quiet re-check now and then. */
object SpeedCheck {
    private fun prefs(c: Context) = c.getSharedPreferences("speed", Context.MODE_PRIVATE)
    private var running = false

    fun history(c: Context) = SpeedTest.parse(prefs(c).getString("tests", null))

    /** Tells Play best what this connection keeps up with. */
    fun apply(c: Context) { Best.linkMbps = SpeedTest.usable(SpeedTest.typical(history(c))) }

    fun record(c: Context, mbps: Double) {
        prefs(c).edit().putString("tests", SpeedTest.write(SpeedTest.added(history(c), SpeedTest.Result(System.currentTimeMillis(), mbps)))).apply()
        apply(c)
    }

    /** From Home: a few seconds' test if the last is over 6 hours old. Never while something plays. */
    fun maybe(app: MainActivity) {
        if (running || app.playerOpen || !SpeedTest.due(history(app))) return
        val addons = Home.loadedAddons() ?: return
        running = true
        Net.async({ SpeedTest.testUrl(addons)?.let { SpeedTest.measure(it) } }) { r ->
            running = false
            r.getOrNull()?.let { record(app, it) }
        }
    }

    /** For Settings: "18 Mbps usually (checked 14:20)". */
    fun summary(c: Context): String {
        val h = history(c)
        val t = SpeedTest.typical(h) ?: return "not measured"
        val last = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK).format(java.util.Date(h.last().at))
        return "%.0f Mbps usually (last %s)".format(java.util.Locale.UK, t, last)
    }
}
