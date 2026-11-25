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

    fun screen(): Screen {
        lateinit var r: Reorder<NextWatch.Entry>
        r = Reorder(
            head = {
                if (r.items.isEmpty()) listOf(Row("Nothing lined up. On a film or show, choose 'Add to Next watch'."))
                else listOf(Row("In your order. Starting one moves it to Continue watching."))
            },
            items = all().toMutableList(),
            label = { Marks.title(Marks.NEXT, it.name) },
            detail = { listOf(it.year, MetaParse.typeLabel(it.type)).filter { s -> s.isNotBlank() }.joinToString("  ") },
            open = { Details.screenFor(it.id, it.type, it.name) },
            commit = { save(it) },
        )
        val s = r.build("Next watch")
        s.onShow = { r.items = all().toMutableList(); r.redraw() }
        return s
    }
}
