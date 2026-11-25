package app.stremiolite.linux

import app.stremiolite.Best
import app.stremiolite.SpeedTest
import java.io.File

/** Linux side of [SpeedTest]: this computer's speed history, and a quiet re-check now and then. */
object SpeedCheck {
    private fun file() = File(Store.root, "speed.txt")
    private var running = false

    fun history() = SpeedTest.parse(runCatching { file().readText() }.getOrNull())

    fun apply() { Best.linkMbps = SpeedTest.usable(SpeedTest.typical(history())) }

    fun record(mbps: Double) {
        runCatching { file().writeText(SpeedTest.write(SpeedTest.added(history(), SpeedTest.Result(System.currentTimeMillis(), mbps)))) }
        apply()
    }

    fun maybe() {
        if (running || Player.playing || !SpeedTest.due(history())) return
        val addons = Home.loaded() ?: return
        running = true
        App.async({ SpeedTest.testUrl(addons)?.let { SpeedTest.measure(it) } }) { r ->
            running = false
            r.getOrNull()?.let { record(it) }
        }
    }

    fun summary(): String {
        val h = history()
        val t = SpeedTest.typical(h) ?: return "not measured"
        val last = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.UK).format(java.util.Date(h.last().at))
        return "%.0f Mbps usually (last %s)".format(java.util.Locale.UK, t, last)
    }
}
