package app.stremiolite.linux

import app.stremiolite.Full
import app.stremiolite.Marks
import app.stremiolite.MetaParse
import app.stremiolite.NextWatch

/** Linux side of [NextWatch]: the list on the account, its screen (in your order), and the button on a title. */
object NextList {
    private fun raw() = Lib.raw(NextWatch.ID)?.toString()

    private fun started(id: String): Boolean {
        // An episode put here from Ready leaves once you've started or watched that episode.
        if (':' in id) {
            val show = id.substringBefore(':')
            return Lib.resumeAt(show, id) > 0L || episodeWatched(show, id)
        }
        val i = Lib.info(id)
        return i?.continuing == true || i?.filmWatched == true
    }

    private fun episodeWatched(show: String, id: String) =
        NewEpisodes.shows().firstOrNull { it.id == show }?.let { id in Lib.watchedEpisodes(show, MetaParse.stremioOrder(it)) } == true

    fun ids(): Set<String> = NextWatch.parse(raw()).mapTo(HashSet()) { it.id }

    fun add(e: NextWatch.Entry) { save(NextWatch.added(NextWatch.parse(raw()), e)) }

    private fun openEpisode(e: NextWatch.Entry, play: Boolean = false) {
        App.async({ MetaParse.fetch(Home.all(), "series", e.show) }) { r ->
            val f = r.getOrNull()
            val v = f?.videos?.firstOrNull { it.id == e.id }
            if (f == null || v == null) { App.toast("Couldn't load that episode"); return@async }
            App.push(if (play) Play.best("series", v.id, "${f.name} ${MetaParse.epLabel(v)}", Details.watchFor(f, v), Lib.resumeAt(f.id, v.id)) else Details.episode(f, v))
        }
    }

    /** Your Next watch, without anything you've since started (those drop out on every device). */
    fun all(): List<NextWatch.Entry> {
        val list = NextWatch.parse(raw())
        val keep = NextWatch.stillNext(list) { started(it) }
        if (keep.size != list.size) save(keep)
        return keep
    }

    fun has(id: String) = NextWatch.parse(raw()).any { it.id == id }

    private fun save(list: List<NextWatch.Entry>) = Lib.put(NextWatch.record(raw(), list))

    fun summary(): String {
        val list = all()
        if (list.isEmpty()) return "empty"
        val w = Waiting.list().associate { it.id to (it.better to it.ready) }
        val NS = app.stremiolite.NextStatus
        val ready = list.count { it.isEpisode || NS.effective(it.id) { id -> w[id] }?.group == app.stremiolite.NextStatus.Group.READY }
        val known = list.count { it.isEpisode || NS.effective(it.id) { id -> w[id] } != null }
        if (known < list.size && ready == 0) return "${list.size}  ►"
        return listOfNotNull("$ready ready", (list.size - ready).takeIf { it > 0 }?.let { "$it on the way" }).joinToString(" · ") + "  ►"
    }

    /** "Add to Next watch" on a title's page (not shown once you've started or finished it). */
    fun row(f: Full): Row? {
        if (started(f.id) && !has(f.id)) return null
        lateinit var r: Row
        fun label() = if (has(f.id)) "Remove from Next watch" else "Add to Next watch"
        r = Row(label(), action = {
            val list = NextWatch.parse(raw())
            if (has(f.id)) { save(NextWatch.removed(list, f.id)); App.toast("Removed from Next watch") }
            else { add(NextWatch.Entry(f.id, f.type, f.name, f.year)); App.toast("Added to Next watch") }
            r.label = label()
            App.refresh()
        })
        return r
    }

    // ---- the list: by status (default) or in your order ------------------------------------------

    private fun byStatus() = Prefs.nextView != "order"

    private fun viewRow() = Row("View", if (byStatus()) "◄ By status ►" else "◄ Your order ►", action = {
        Prefs.nextView = if (byStatus()) "order" else "status"; App.replaceTop(screen())
    })

    private fun statusOf(): (String) -> app.stremiolite.NextStatus.Status? {
        val w = Waiting.list().associate { it.id to (it.better to it.ready) }
        return { id -> if (':' in id) app.stremiolite.NextStatus.Status(app.stremiolite.NextStatus.Group.READY, "new episode") else app.stremiolite.NextStatus.effective(id) { w[it] } }
    }

    private fun detail(st: app.stremiolite.NextStatus.Status?): String = st?.detail ?: "checking…"

    fun screen(): Screen = if (byStatus()) statusScreen() else orderScreen()

    private fun statusScreen(): Screen {
        lateinit var s: Screen
        fun build() {
            val list = all()
            val rows = mutableListOf(viewRow())
            if (list.isEmpty()) rows += Row("Nothing lined up. On a film or show, choose 'Add to Next watch'.")
            val status = statusOf()
            fun row(e: NextWatch.Entry): Row {
                val st = status(e.id)
                return Row(Marks.title(st?.shape ?: Marks.NEXT, e.name), detail(st) + "  ►", action = { if (e.isEpisode) App.push(Details.screenFor(e.show, "series", e.name.substringBefore(" · "))) else App.push(Details.screenFor(e.id, e.type, e.name)) },
                    play = { if (e.isEpisode) openEpisode(e, play = true) else App.push(Details.screenFor(e.id, e.type, e.name, autoPlay = true)) })
            }
            // Only what plays now; the rest is on its way (in Waiting or Following) and comes in by itself.
            val groups = app.stremiolite.NextStatus.grouped(list, status)
            val ready = groups.firstOrNull { it.first == app.stremiolite.NextStatus.Group.READY }?.second.orEmpty()
            val coming = groups.filter { it.first != app.stremiolite.NextStatus.Group.READY }
            ready.forEach { rows += row(it) }
            if (list.isNotEmpty() && ready.isEmpty()) rows += Row(if (coming.any { it.first == null }) "Checking what's ready…" else "Nothing ready to play yet.")
            val n = coming.sumOf { it.second.size }
            if (n > 0) {
                rows += Row("────────────────────")
                rows += Row("On the way", "$n  ►", opens = {
                    Screen("On the way", listOf(Row("Not playable yet: each shows as ready here once a stream plays. To be told, choose "Tell me when a stream is available" on its page.")) + coming.flatMap { (g, items) ->
                        listOf(Row("──── ${g?.heading ?: "Checking…"} ────")) + items.map { row(it) }
                    })
                })
            }
            s.rows = rows
            App.refresh()
        }
        s = Screen("Next watch", emptyList())
        s.onShow = { build(); fill(s) { build() } }
        return s
    }

    private fun orderScreen(): Screen {
        lateinit var r: Reorder<NextWatch.Entry>
        r = Reorder(
            head = {
                listOf(viewRow()) +
                    if (r.items.isEmpty()) listOf(Row("Nothing lined up. On a film or show, choose 'Add to Next watch'."))
                    else listOf(Row("In your order. Starting one moves it to Continue watching."))
            },
            items = all().toMutableList(),
            label = { Marks.title(statusOf()(it.id)?.shape ?: Marks.NEXT, it.name) },
            detail = { detail(statusOf()(it.id)) },
            open = { if (it.isEpisode) Details.screenFor(it.show, "series", it.name.substringBefore(" · ")) else Details.screenFor(it.id, it.type, it.name) },
            commit = { save(it) },
        )
        val s = r.build("Next watch")
        s.onShow = { r.items = all().toMutableList(); r.redraw(); fill(s) { r.redraw() } }
        return s
    }

    private fun fill(s: Screen, changed: () -> Unit) {
        val todo = app.stremiolite.NextStatus.missing(all().filter { !it.isEpisode })
        if (todo.isEmpty()) return
        val addons = Home.all()
        fun next(i: Int) {
            if (!App.inStack(s)) return
            if (Player.playing) { App.later(10_000) { next(i) }; return }
            // Nothing goes into Waiting by itself: you choose that on a title's page.
            if (i >= todo.size) return
            App.async({ app.stremiolite.NextStatus.check(addons, todo[i], System.currentTimeMillis(), Prefs.tmdbKey, Prefs.watchRegion) }) { _ -> if (App.inStack(s)) changed(); next(i + 1) }
        }
        next(0)
    }
}
