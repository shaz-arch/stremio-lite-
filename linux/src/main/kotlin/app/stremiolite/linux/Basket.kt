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
        if (list.isEmpty()) rows += Row("Empty. While browsing, choose 'Add to basket' on anything you like the look of.")
        else rows += Row("Open one to choose: Next watch, Favourites, or tell me when it's ready.")
        list.forEach { e ->
            rows += Row(Marks.title(Catalogs.mark(e.id, e.type), e.name) + if (e.year.isNotEmpty()) " (${e.year})" else "",
                MetaParse.typeLabel(e.type) + "  ►", opens = { Details.screenFor(e.id, e.type, e.name) },
                play = { App.push(Details.screenFor(e.id, e.type, e.name, autoPlay = true)) })
        }
        if (list.isNotEmpty()) {
            rows += Row("────────────────────")
            rows += Row("Empty the basket", "►", opens = {
                confirmScreen("Empty the basket?", "Takes all ${list.size} out, on every device.", "Yes, empty it") {
                    save(emptyList()); App.toast("Basket emptied"); App.back(); App.replaceTop(screen())
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

    private fun gone() = ReadyNotice.readSeen(Prefs.readyGone)
    private fun dismiss(id: String) { Prefs.readyGone = ReadyNotice.writeSeen(gone(), listOf(id)) }

    fun films(): List<Waiting.Entry> {
        val next = NextList.ids()
        return Waiting.current().filter { it.ready > 0 && it.id !in next }.sortedByDescending { it.ready }
    }

    class Episode(val show: Full, val ep: Video)

    fun episodes(): List<Episode> {
        val followed = Waiting.following().map { it.first }.toSet()
        val next = NextList.ids()
        val gone = gone()
        return NewEpisodes.shows().filter { it.id in followed }.flatMap { sh ->
            val done = Lib.watchedEpisodes(sh.id, MetaParse.stremioOrder(sh))
            Releases.recent(sh, Releases.day(EPISODE_DAYS)).filter { it.id !in done && Lib.resumeAt(sh.id, it.id) <= 0L }.map { Episode(sh, it) }
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
        if (films.isEmpty() && eps.isEmpty()) rows += Row("Nothing ready yet. Films you're waiting on and new episodes of shows you follow come here.")
        if (films.isNotEmpty()) {
            rows += Row("──── Films ────")
            films.forEach { e ->
                rows += Row(Marks.title(Marks.READY, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else "", (if (e.better) "better: ${e.found}" else "stream found") + "  ►",
                    opens = { filmScreen(e) }, play = { App.push(Details.screenFor(e.id, e.type, e.name, autoPlay = true)) })
            }
        }
        if (eps.isNotEmpty()) {
            rows += Row("──── Episodes ────")
            eps.forEach { x ->
                rows += Row(Marks.title(Marks.READY, x.show.name), (if (Releases.opensSeason(x.ep)) "new season · " else "") + MetaParse.epLabel(x.ep) + "  ►",
                    opens = { episodeScreen(x) }, play = { App.push(playEpisode(x)) })
            }
        }
        val s = Screen("Ready", rows)
        var loaded = false
        s.onShow = { if (!loaded) { loaded = true; if (NewEpisodes.shows().isEmpty()) NewEpisodes.load { if (App.top()?.title == "Ready") App.replaceTop(screen()) } } }
        return s
    }

    private fun filmScreen(e: Waiting.Entry) = Screen(e.name, listOf(
        Row(if (e.better) "Better version found: ${e.found}" else "A stream is available now."),
        Row("────────────────────"),
        Row("Play now", "►", action = { App.back(); App.push(Details.screenFor(e.id, e.type, e.name, autoPlay = true)) }),
        Row("Add to Next watch", action = {
            NextList.add(NextWatch.Entry(e.id, e.type, e.name, e.year)); Waiting.remove(e.id)
            App.toast("${e.name} is in Next watch"); App.back(); App.replaceTop(screen())
        }),
        Row("Open its page", "►", opens = { Details.screenFor(e.id, e.type, e.name) }),
        Row("Dismiss", action = { Waiting.remove(e.id); App.toast("Dismissed"); App.back(); App.replaceTop(screen()) }),
    ))

    private fun episodeScreen(x: Episode) = Screen(x.show.name, listOf(
        Row(MetaParse.epLabel(x.ep) + (if (x.ep.title.isNotBlank()) " · ${x.ep.title}" else "")),
        Row("────────────────────"),
        Row("Play now", "►", action = { App.back(); App.push(playEpisode(x)) }),
        Row("Add to Next watch", action = {
            NextList.add(NextWatch.Entry(x.ep.id, "series", "${x.show.name} · ${MetaParse.epLabel(x.ep)}", "", x.show.id))
            App.toast("In Next watch"); App.back(); App.replaceTop(screen())
        }),
        Row("Open the episode", "►", opens = { Details.episode(x.show, x.ep) }),
        Row("Dismiss", action = { dismiss(x.ep.id); App.toast("Dismissed"); App.back(); App.replaceTop(screen()) }),
    ))
}
