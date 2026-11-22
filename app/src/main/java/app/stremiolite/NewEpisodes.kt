package app.stremiolite

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Episodes out in the last 7 days, for shows in the Library or Continue watching. */
object NewEpisodes {
    private const val DAYS = 7
    private const val KEEP_MS = 60 * 60_000L

    private class Found(val show: Full, val ep: Video)

    private var found: List<Found> = emptyList()
    private var foundAt = 0L

    private fun day(offsetDays: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() - offsetDays * 86_400_000L))

    fun screen(app: MainActivity): Screen {
        val shows = (History.library(app).filter { it.type == "series" }.map { it.id to it.name } +
            History.continueWatching(app).filter { it.type == "series" }.map { it.id to it.name }).distinctBy { it.first }
        val status = Row("")
        lateinit var screen: Screen
        val pretty = SimpleDateFormat("EEE d MMM", Locale.UK)
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.UK)

        fun build(list: List<Found>): List<Row> {
            val rows = mutableListOf(status)
            list.forEach { f ->
                val date = runCatching { pretty.format(iso.parse(f.ep.released)!!) }.getOrDefault(f.ep.released)
                val watched = if (History.progress(app, f.ep.id)?.done == true) "✓  " else ""
                rows += Row(
                    "${f.show.name}  ${Details.epLabel(f.ep)}  ${f.ep.title}".trim(), watched + date,
                    opens = { Details.episodeScreen(app, f.show, f.ep) },
                    play = { app.push(Details.playEpisode(app, f.show, f.ep)) },
                )
            }
            return rows
        }

        fun check() {
            if (shows.isEmpty()) {
                status.label = "Add shows to your Library (or start watching them) to see their new episodes here."
                screen.rows = build(emptyList()); app.refresh(screen)
                return
            }
            if (System.currentTimeMillis() - foundAt < KEEP_MS) {
                status.label = if (found.isEmpty()) "No new episodes in the last $DAYS days." else "Out in the last $DAYS days · ▶ plays"
                screen.rows = build(found); app.refresh(screen)
                return
            }
            val from = day(DAYS)
            val today = day(0)
            val results = mutableListOf<Found>()
            var answered = 0
            status.label = "Checking ${shows.size} shows…"
            screen.rows = build(emptyList()); app.refresh(screen)
            shows.forEach { (id, _) ->
                Net.async({ Details.loadFull("series", id) }) { r ->
                    answered++
                    r.onSuccess { show ->
                        show.videos.filter { it.season > 0 && it.released.length == 10 && it.released >= from && it.released <= today }
                            .forEach { results += Found(show, it) }
                    }
                    val sorted = results.sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
                    status.label = if (answered < shows.size) "Checking ${shows.size} shows… $answered done"
                    else if (sorted.isEmpty()) "No new episodes in the last $DAYS days." else "Out in the last $DAYS days · ▶ plays"
                    if (answered >= shows.size) { found = sorted; foundAt = System.currentTimeMillis() }
                    screen.rows = build(sorted)
                    app.refresh(screen)
                }
            }
        }

        screen = Screen("New episodes", listOf(status))
        var started = false
        screen.onShow = {
            if (!started) { started = true; check() } else { screen.rows = build(found); app.refresh(screen) }
        }
        return screen
    }
}
