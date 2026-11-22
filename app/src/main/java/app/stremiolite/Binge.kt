package app.stremiolite

/** "Next episode in 10 seconds" after one finishes; Back cancels. */
object Binge {
    private const val SECONDS = 10

    /** The episode after [w], built from what was saved when [w] started. */
    private fun nextWatch(w: History.Watch): History.Watch? {
        val next = w.nextVideoId ?: return null
        // Regular episodes only ("id:season:episode" with season above 0), in Stremio's order.
        val regular = w.videoIds.filter { vid -> vid.split(':').let { p -> p.size >= 3 && (p[p.size - 2].toIntOrNull() ?: 0) > 0 } }
        val after = regular.getOrNull(regular.indexOf(next).let { if (it < 0) -1 else it + 1 })
        return History.Watch(
            w.id, w.type, w.name, w.year, next, w.nextLabel ?: "", w.runtime,
            after, after?.let { label(it) }, w.videoIds,
        )
    }

    private fun label(videoId: String): String {
        val p = videoId.split(':')
        val s = p.getOrNull(p.size - 2)?.toIntOrNull() ?: return ""
        val e = p.lastOrNull()?.toIntOrNull() ?: return ""
        return "S${s.toString().padStart(2, '0')}E${e.toString().padStart(2, '0')}"
    }

    fun upNext(app: MainActivity, w: History.Watch): Screen {
        val nw = nextWatch(w)
        val heading = Row("Next: ${w.nextLabel ?: ""}")
        val countdown = Row("")
        lateinit var screen: Screen
        var left = SECONDS
        var active = false

        fun go() {
            if (nw == null) return
            active = false
            val title = "${nw.name} ${nw.label.substringBefore(' ')}"
            app.replaceTop(Streams.playBest(app, nw.type, nw.videoId, title, nw.runtime, nw, 0))
        }

        val tick = object : Runnable {
            override fun run() {
                if (!active) return
                left--
                if (left <= 0) go() else {
                    countdown.label = "Starting in $left…   (Back to stop)"
                    app.refresh(screen)
                    Net.later(1_000, this)
                }
            }
        }

        screen = Screen(
            w.name,
            listOf(
                heading,
                countdown,
                Row("────────────────────"),
                Row("Play now", action = { go() }),
                Row("Stop here", action = { active = false; Net.cancel(tick); app.back() }),
            ),
        )
        screen.position = 3
        screen.onShow = {
            if (nw == null) {
                countdown.label = "That was the last episode."
                app.refresh(screen)
            } else if (!active) {
                active = true
                Streams.prefetch(app, nw.type, nw.videoId)   // links ready by the time the countdown ends
                left = SECONDS
                countdown.label = "Starting in $left…   (Back to stop)"
                app.refresh(screen)
                Net.cancel(tick)
                Net.later(1_000, tick)
            }
        }
        screen.onHide = {
            active = false
            Net.cancel(tick)
        }
        return screen
    }
}
