package app.stremiolite

import android.content.Context

/**
 * Ready: what has come good, for you to pick up: a film whose stream turned up, a better version
 * found, a new episode or season of a show you follow. From here you play it, put it in Next watch,
 * or dismiss it. Films leave Waiting once they're ready; shows stay there for their next episode.
 */
object Ready {
    /** Episodes stay in Ready this long after they come out, unless you watch, line up or dismiss them. */
    private const val EPISODE_DAYS = 30

    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")
    /** Dismissed episodes: on the account (every device), plus any dismissed here before that. */
    private fun gone(c: Context) = ReadyGone.parse(History.rawRemote(c, ReadyGone.ID)) + ReadyNotice.readSeen(prefs(c).getString("readyGone", null))
    private fun dismiss(c: Context, id: String) = History.putRemote(c, ReadyGone.add(History.rawRemote(c, ReadyGone.ID), id))

    fun films(c: Context): List<Waiting.Entry> {
        val next = NextList.ids(c)
        return Waiting.current(c).filter { it.ready > 0 && it.id !in next }.sortedByDescending { it.ready }
    }

    class Episode(val show: Full, val ep: Video)

    /** New episodes of shows you follow, not watched, not lined up and not dismissed, newest first. */
    fun episodes(c: Context): List<Episode> {
        val next = NextList.ids(c)
        val gone = gone(c)
        val news = NewEpisodes.isNews(c)
        return NewEpisodes.shows()
            .flatMap { sh -> Releases.recent(sh, Releases.day(EPISODE_DAYS)).map { Episode(sh, it) } }
            .filter { it.ep.id !in gone && it.ep.id !in next && History.progress(c, it.ep.id) == null && news(it.show, it.ep) }
            .sortedWith(compareByDescending<Episode> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.season }.thenBy { it.ep.episode })
    }

    fun summary(c: Context): String {
        val n = films(c).size + episodes(c).size
        return if (n == 0) "nothing yet" else "$n  ►"
    }

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun redraw() { if (app.inStack(s)) { s.rows = build(app) { redraw() }; app.refresh(s) } }
        s = Screen("Ready", build(app) { redraw() })
        var loaded = false
        s.onShow = {
            Waiting.markSeen(app); NewEpisodes.markSeen(app)
            redraw()
            History.pull(app) { redraw() }
            if (!loaded) {
                loaded = true
                val missing = (Waiting.following(app).map { it.first } + History.continueWatching(app).filter { it.type == "series" }.map { it.id })
                    .any { id -> NewEpisodes.shows().none { it.id == id } }
                NewEpisodes.load(app, force = missing) { redraw() }
            }
        }
        return s
    }

    private fun build(app: MainActivity, changed: () -> Unit): List<Row> {
        val films = films(app)
        val eps = episodes(app)
        val rows = mutableListOf<Row>()
        if (films.isEmpty() && eps.isEmpty()) rows += Row("Empty.")
        if (films.isNotEmpty()) {
            rows += Row("──── Films ────")
            films.forEach { e ->
                rows += Row(Marks.title(Marks.READY, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else "",
                    (if (e.better) "better: ${e.found}" else "stream found") + "  ►",
                    opens = { Details.screenFor(app, e.id, e.type, e.name, e.year) ?: Screen(e.name, listOf(Row("Your add-ons are still loading."))) },
                    play = { Details.screenFor(app, e.id, e.type, e.name, e.year, autoPlay = true)?.let { app.push(it) } })
            }
        }
        if (eps.isNotEmpty()) {
            rows += Row("──── Episodes ────")
            eps.forEach { x ->
                val season = if (Releases.opensSeason(x.ep)) "new season · " else ""
                rows += Row(Marks.title(Marks.READY, x.show.name), season + Details.epLabel(x.ep) + "  ►",
                    opens = { Details.screenFor(app, x.show.id, "series", x.show.name, "") ?: Screen(x.show.name, listOf(Row("Your add-ons are still loading."))) },
                    play = { app.push(Details.playEpisode(app, x.show, x.ep)) })
            }
        }
        return rows
    }

    /** On a title's page, when something of it is in Ready: play it, line it up, or dismiss it. */
    fun rowsFor(app: MainActivity, f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        films(app).firstOrNull { it.id == f.id }?.let { e ->
            rows += Row("◆ Ready: " + if (e.better) "better version found (${e.found})" else "a stream is available now")
            lateinit var add: Row
            add = Row("Add to Next watch", action = {
                NextList.add(app, NextWatch.Entry(e.id, e.type, e.name, e.year)); Waiting.remove(app, e.id)
                add.label = "In Next watch ✓"; app.toast("${e.name} is in Next watch"); app.refreshTop()
            })
            lateinit var gone: Row
            gone = Row("Dismiss from Ready", action = { Waiting.remove(app, e.id); gone.label = "Dismissed ✓"; app.refreshTop() })
            rows += add; rows += gone
        }
        episodes(app).filter { it.show.id == f.id }.forEach { x ->
            val label = Details.epLabel(x.ep)
            rows += Row("◆ Ready: $label" + if (x.ep.title.isNotBlank()) " · ${x.ep.title}" else "", "play  ►", action = { app.push(Details.playEpisode(app, x.show, x.ep)) })
            lateinit var add: Row
            add = Row("Add $label to Next watch", action = {
                NextList.add(app, NextWatch.Entry(x.ep.id, "series", "${x.show.name} · $label", "", x.show.id))
                add.label = "$label is in Next watch ✓"; app.refreshTop()
            })
            lateinit var gone: Row
            gone = Row("Dismiss $label from Ready", action = { dismiss(app, x.ep.id); gone.label = "Dismissed ✓"; app.refreshTop() })
            rows += add; rows += gone
        }
        if (rows.isNotEmpty()) rows += Row("────────────────────")
        return rows
    }

}
