package app.stremiolite

import android.content.Context

/** Checks quietly that your streams still work, so a broken stream add-on shows on Home before you sit down. */
object StreamAlert {
    private const val EVERY_MS = 3 * 3_600_000L
    private const val WHILE_BROKEN_MS = 10 * 60_000L
    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")
    private var running = false

    fun problem(c: Context): String = prefs(c).getString("streamProblem", "") ?: ""

    /** Every 3 hours (every 10 minutes while something's wrong), or now if [force]. */
    fun maybe(app: MainActivity, force: Boolean = false, changed: () -> Unit) {
        val p = prefs(app)
        val wait = if (problem(app).isEmpty()) EVERY_MS else WHILE_BROKEN_MS
        if (running || (!force && System.currentTimeMillis() - p.getLong("streamCheckAt", 0) < wait)) return
        val addons = Home.allAddons()
        if (addons.isEmpty()) return
        running = true
        Net.async({ Health.streamProblem(addons) }) { r ->
            running = false
            val was = problem(app)
            val now = r.getOrNull() ?: ""
            p.edit().putLong("streamCheckAt", System.currentTimeMillis()).putString("streamProblem", now).apply()
            if (now.isNotEmpty() && was.isEmpty()) Ntfy.send(app, "Streams aren't working: $now")
            if (now != was) changed()
        }
    }

    fun homeRow(app: MainActivity): Row? {
        val p = problem(app).ifEmpty { return null }
        return Row("⚠ Streams aren't working. Press OK to see why", "►", opens = {
            Screen("Streams aren't working", listOf(
                Row(p),
                Row(""),
                Row("Check everything", "►", opens = { HealthScreen.screen(app) }),
                Row("Set up your streams again", "►", opens = { StreamsSetup.screen(app) }),
                Row("Check again now", action = { app.toast("Checking your streams…"); maybe(app, force = true) { Home.redraw(); app.toast(if (problem(app).isEmpty()) "Streams are working again" else "Still not working") } }),
            ))
        })
    }
}
