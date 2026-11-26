package app.stremiolite.linux

import app.stremiolite.Basket
import app.stremiolite.Full
import app.stremiolite.Marks
import app.stremiolite.MetaParse
import app.stremiolite.NextWatch
import app.stremiolite.ReadyNotice
import app.stremiolite.Releases
import app.stremiolite.Video

/** The basket: titles put aside while browsing; open one to choose, and it leaves once you do. */
object BasketList {
    private fun raw() = Lib.raw(Basket.ID)?.toString()
    fun all(): List<NextWatch.Entry> = Basket.parse(raw())
    fun has(id: String) = all().any { it.id == id }
    private fun save(list: List<NextWatch.Entry>) = Lib.put(Basket.record(raw(), list))
    fun add(e: NextWatch.Entry) = save(NextWatch.added(all(), e))
    fun done(id: String) { if (has(id)) save(NextWatch.removed(all(), id)) }
    fun toggle(id: String, type: String, name: String, year: String) {
        if (has(id)) { done(id); App.toast("Taken out of the basket: $name") } else { add(NextWatch.Entry(id, type, name, year)); App.toast("In the basket: $name") }
        App.top()?.rows?.firstOrNull { it.label.endsWith(" $name") }?.let { r -> r.label = Marks.title(Catalogs.mark(id, type), name) }
        App.refresh()
    }

    fun summary(): String = all().size.let { if (it == 0) "empty" else "$it  ►" }

    fun row(f: Full): Row {
        lateinit var r: Row
        fun label() = if (has(f.id)) "In your basket ✓ (remove)" else "Add to basket"
        r = Row(label(), action = {
            if (has(f.id)) { done(f.id); App.toast("Taken out of the basket") }
            else { add(NextWatch.Entry(f.id, f.type, f.name, f.year)); App.toast("In the basket: decide later") }
            r.label = label(); App.refresh()
        })
        return r
    }

    fun screen(): Screen {
        val list = all()
        val rows = mutableListOf<Row>()
        if (list.isEmpty()) rows += Row("Empty.")
        list.forEach { e ->
            rows += Row(Marks.title(Catalogs.mark(e.id, e.type), e.name) + if (e.year.isNotEmpty()) " (${e.year})" else "",
                MetaParse.typeLabel(e.type) + "  ►", opens = { Details.screenFor(e.id, e.type, e.name) },
                play = { App.push(Details.screenFor(e.id, e.type, e.name, autoPlay = true)) })
        }
        if (list.isNotEmpty()) {
            rows += Row("────────────────────")
            rows += Row("Clear the basket", "►", opens = {
                confirmScreen("Clear the basket?", "Takes all ${list.size} out, on every device. Whatever you chose for them stays.", "Yes, clear it") {
                    save(emptyList()); App.toast("Basket cleared"); App.back(); App.replaceTop(screen())
                }
            })
        }
        val s = Screen("Basket", rows)
        s.onShow = { if (System.currentTimeMillis() - Lib.lastPull > 15_000) Lib.pull(force = true) { if (App.top()?.title == "Basket") App.replaceTop(screen()) } }
        return s
    }
}

/** Ready: films whose stream (or better version) turned up, and new episodes of shows you follow. */
object Ready {
    private const val EPISODE_DAYS = 30
    private var askedShows = emptySet<String>()

    private fun gone() = app.stremiolite.ReadyGone.parse(Lib.raw(app.stremiolite.ReadyGone.ID)?.toString()) + ReadyNotice.readSeen(Prefs.readyGone)
    private fun dismiss(id: String) { Lib.put(app.stremiolite.ReadyGone.add(Lib.raw(app.stremiolite.ReadyGone.ID)?.toString(), id)) }

    fun films(): List<Waiting.Entry> {
        val next = NextList.ids()
        return Waiting.current().filter { it.ready > 0 && it.id !in next }.sortedByDescending { it.ready }
    }

    class Episode(val show: Full, val ep: Video)

    fun episodes(): List<Episode> {
        val next = NextList.ids()
        val gone = gone()
        val news = NewEpisodes.isNews()
        return NewEpisodes.shows().flatMap { sh ->
            val done = Lib.watchedEpisodes(sh.id, MetaParse.stremioOrder(sh))
            Releases.recent(sh, Releases.day(EPISODE_DAYS)).filter { it.id !in done && Lib.resumeAt(sh.id, it.id) <= 0L && news(sh, it) }.map { Episode(sh, it) }
        }.filter { it.ep.id !in gone && it.ep.id !in next }
            .sortedWith(compareByDescending<Episode> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.season }.thenBy { it.ep.episode })
    }

    fun summary(): String { val n = films().size + episodes().size; return if (n == 0) "nothing yet" else "$n  ►" }

    private fun playEpisode(x: Episode) =
        Play.best("series", x.ep.id, "${x.show.name} ${MetaParse.epLabel(x.ep)}", Details.watchFor(x.show, x.ep), Lib.resumeAt(x.show.id, x.ep.id))

    fun screen(): Screen {
        Prefs.readySeen = System.currentTimeMillis(); NewEpisodes.markSeen()
        val films = films(); val eps = episodes()
        val rows = mutableListOf<Row>()
        if (films.isEmpty() && eps.isEmpty()) rows += Row("Empty.")
        if (films.isNotEmpty()) {
            rows += Row("──── Films ────")
            films.forEach { e ->
                rows += Row(Marks.title(Marks.READY, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else "", (if (e.better) "better: ${e.found}" else "stream found") + "  ►",
                    opens = { Details.screenFor(e.id, e.type, e.name) }, play = { App.push(Details.screenFor(e.id, e.type, e.name, autoPlay = true)) })
            }
        }
        if (eps.isNotEmpty()) {
            rows += Row("──── Episodes ────")
            eps.forEach { x ->
                rows += Row(Marks.title(Marks.READY, x.show.name), (if (Releases.opensSeason(x.ep)) "new season · " else "") + MetaParse.epLabel(x.ep) + "  ►",
                    opens = { Details.screenFor(x.show.id, "series", x.show.name) }, play = { App.push(playEpisode(x)) })
            }
        }
        val s = Screen("Ready", rows)
        s.onShow = {
            val missing = (Waiting.following().map { it.first } + Lib.continueWatching().filter { it.type == "series" }.map { it.id }).filter { id -> NewEpisodes.shows().none { it.id == id } }.toSet()
            // Ask once for each show missing (a redrawn screen mustn't ask again: load can answer at once).
            if (missing.isNotEmpty() && !askedShows.containsAll(missing)) { askedShows = askedShows + missing; NewEpisodes.load(force = true) { if (App.top()?.title == "Ready") App.replaceTop(screen()) } }
        }
        return s
    }

    /** On a title's page, when something of it is in Ready: play it, line it up, or dismiss it. */
    fun rowsFor(f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        films().firstOrNull { it.id == f.id }?.let { e ->
            rows += Row("◆ Ready: " + if (e.better) "better version found (${e.found})" else "a stream is available now")
            lateinit var add: Row
            add = Row("Add to Next watch", action = {
                NextList.add(NextWatch.Entry(e.id, e.type, e.name, e.year)); Waiting.remove(e.id)
                add.label = "In Next watch ✓"; App.toast("${e.name} is in Next watch"); App.refresh()
            })
            lateinit var gone: Row
            gone = Row("Dismiss from Ready", action = { Waiting.remove(e.id); gone.label = "Dismissed ✓"; App.refresh() })
            rows += add; rows += gone
        }
        episodes().filter { it.show.id == f.id }.forEach { x ->
            val label = MetaParse.epLabel(x.ep)
            rows += Row("◆ Ready: $label" + if (x.ep.title.isNotBlank()) " · ${x.ep.title}" else "", "play  ►", action = { App.push(playEpisode(x)) })
            lateinit var add: Row
            add = Row("Add $label to Next watch", action = {
                NextList.add(NextWatch.Entry(x.ep.id, "series", "${x.show.name} · $label", "", x.show.id)); add.label = "$label is in Next watch ✓"; App.refresh()
            })
            lateinit var gone: Row
            gone = Row("Dismiss $label from Ready", action = { dismiss(x.ep.id); gone.label = "Dismissed ✓"; App.refresh() })
            rows += add; rows += gone
        }
        if (rows.isNotEmpty()) rows += Row("────────────────────")
        return rows
    }

}
