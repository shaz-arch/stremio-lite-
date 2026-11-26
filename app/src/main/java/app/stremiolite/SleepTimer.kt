package app.stremiolite

/**
 * Android side of the sleep timer ([Sleep]): the Home row and its menu, and closing the player when
 * the time is up. The player (VLC or another app) was started for a result, so we can close it with
 * finishActivity; if it doesn't go, the app comes back to the front, which pauses it.
 */
object SleepTimer {
    private var tick: Runnable? = null

    fun row(app: MainActivity): Row = Row("Sleep timer", Sleep.summary() + "  ►", opens = { screen(app) })

    fun screen(app: MainActivity): Screen {
        val rows = mutableListOf(
            Row("Now: ${Sleep.summary()}"),
            Row("────────────────────"),
        )
        for (m in Sleep.CHOICES + Sleep.END_OF_THIS) rows += Row(Sleep.label(m), action = {
            Sleep.set(m); arm(app); app.toast("Sleep timer: ${Sleep.label(m).replaceFirstChar { it.lowercase() }}"); app.back()
        })
        if (Sleep.on) rows += Row("Turn it off", action = { Sleep.off(); arm(app); app.toast("Sleep timer off"); app.back() })
        return Screen("Sleep timer", rows).also { it.position = 3 }
    }

    /** (Re)starts the countdown to match [Sleep]. */
    fun arm(app: MainActivity) {
        tick?.let { Net.cancel(it) }
        tick = null
        val left = Sleep.left() ?: return
        val r = Runnable { fire(app) }
        tick = r
        Net.later(left + 500, r)
    }

    private fun fire(app: MainActivity) {
        tick = null
        if (!Sleep.timeUp()) { arm(app); return }
        if (!app.playerOpen) { app.toast("Sleep timer: time's up"); app.refreshTop(); return }
        runCatching { app.finishActivity(Player.REQUEST_PLAY) }
        // If the player didn't close, come back to the front: players pause when they lose the screen.
        Net.later(3_000, Runnable {
            if (app.playerOpen) runCatching {
                app.startActivity(android.content.Intent(app, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
    }
}
