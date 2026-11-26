package app.stremiolite.linux

import app.stremiolite.Sleep

/** Linux side of the sleep timer ([Sleep]): the Home row and menu, and closing mpv when the time is up. */
object SleepTimer {
    private var generation = 0

    fun row(): Row = Row("Sleep timer", Sleep.summary() + "  ►", opens = { screen() })

    fun screen(): Screen {
        val rows = mutableListOf(
            Row("Now: ${Sleep.summary()}"),
            Row("────────────────────"),
        )
        for (m in Sleep.CHOICES + Sleep.END_OF_THIS) rows += Row(Sleep.label(m), action = {
            Sleep.set(m); arm(); App.toast("Sleep timer: ${Sleep.label(m).replaceFirstChar { it.lowercase() }}"); App.back(); Home.redraw()
        })
        if (Sleep.on) rows += Row("Turn it off", action = { Sleep.off(); arm(); App.toast("Sleep timer off"); App.back(); Home.redraw() })
        return Screen("Sleep timer", rows).also { it.pos = 3 }
    }

    /** (Re)starts the countdown to match [Sleep]. */
    fun arm() {
        val g = ++generation
        val left = Sleep.left() ?: return
        App.later(left + 500) { if (g == generation) fire() }
    }

    private fun fire() {
        if (!Sleep.timeUp()) { arm(); return }
        if (!Player.stop()) App.toast("Sleep timer: time's up")
        Home.redraw()
    }
}
