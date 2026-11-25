package app.stremiolite.linux

import app.stremiolite.Full
import app.stremiolite.Marks
import app.stremiolite.MetaParse
import app.stremiolite.NextWatch

/** Linux side of [NextWatch]: the list on the account, its screen (in your order), and the button on a title. */
object NextList {
    private fun raw() = Lib.raw(NextWatch.ID)?.toString()

    private fun started(id: String): Boolean {
        val i = Lib.info(id)
        return i?.continuing == true || i?.filmWatched == true
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

    fun summary(): String = all().size.let { if (it == 0) "empty" else "$it  ►" }

    /** "Add to Next watch" on a title's page (not shown once you've started or finished it). */
    fun row(f: Full): Row? {
        if (started(f.id) && !has(f.id)) return null
        lateinit var r: Row
        fun label() = if (has(f.id)) "Remove from Next watch" else "Add to Next watch"
        r = Row(label(), action = {
            val list = NextWatch.parse(raw())
            if (has(f.id)) { save(NextWatch.removed(list, f.id)); App.toast("Removed from Next watch") }
            else { save(NextWatch.added(list, NextWatch.Entry(f.id, f.type, f.name, f.year))); App.toast("Added to Next watch") }
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

    private fun detail(e: NextWatch.Entry): String = app.stremiolite.NextStatus.of(e.id)?.detail ?: "checking…"

    fun screen(): Screen = if (byStatus()) statusScreen() else orderScreen()

    private fun statusScreen(): Screen {
        lateinit var s: Screen
        fun build() {
            val list = all()
            val rows = mutableListOf(viewRow())
            if (list.isEmpty()) rows += Row("Nothing lined up. On a film or show, choose 'Add to Next watch'.")
            app.stremiolite.NextStatus.grouped(list).forEach { (g, items) ->
                rows += Row("──── ${g?.heading ?: "Checking…"} ────")
                items.forEach { e ->
                    val st = app.stremiolite.NextStatus.of(e.id)
                    rows += Row(Marks.title(st?.shape ?: Marks.NEXT, e.name), detail(e) + "  ►", opens = { Details.screenFor(e.id, e.type, e.name) })
                }
            }
            if (list.isNotEmpty()) rows += Row("────────────────────")
            if (list.isNotEmpty()) rows += Row("Films not ready yet are followed: you'll be told when they are.")
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
            label = { Marks.title(app.stremiolite.NextStatus.of(it.id)?.shape ?: Marks.NEXT, it.name) },
            detail = { detail(it) },
            open = { Details.screenFor(it.id, it.type, it.name) },
            commit = { save(it) },
        )
        val s = r.build("Next watch")
        s.onShow = { r.items = all().toMutableList(); r.redraw(); fill(s) { r.redraw() } }
        return s
    }

    private fun fill(s: Screen, changed: () -> Unit) {
        val todo = app.stremiolite.NextStatus.missing(all())
        if (todo.isEmpty()) return
        val addons = Home.all()
        fun next(i: Int) {
            if (!App.inStack(s)) return
            if (Player.playing) { App.later(10_000) { next(i) }; return }
            if (i >= todo.size) {
                Waiting.follow(all().filter { app.stremiolite.NextStatus.of(it.id)?.group?.let { g -> g != app.stremiolite.NextStatus.Group.READY } == true })
                return
            }
            App.async({ app.stremiolite.NextStatus.check(addons, todo[i]) }) { _ -> if (App.inStack(s)) changed(); next(i + 1) }
        }
        next(0)
    }
}
