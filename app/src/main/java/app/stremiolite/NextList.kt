package app.stremiolite

import android.content.Context

/** Android side of [NextWatch]: the list on the account, its screen (in your order), and the button on a title. */
object NextList {
    private fun raw(c: Context) = History.rawRemote(c, NextWatch.ID)

    private fun started(c: Context): (String) -> Boolean {
        val m = History.marks(c)
        return { id -> m[id] == Marks.WATCHED || m[id] == Marks.CONTINUE }
    }

    /** Your Next watch, without anything you've since started (those drop out on every device). */
    fun all(c: Context): List<NextWatch.Entry> {
        val list = NextWatch.parse(raw(c))
        val keep = NextWatch.stillNext(list, started(c))
        if (keep.size != list.size) save(c, keep)
        return keep
    }

    /** Ids in the list as stored (for the shapes in lists; no tidying here). */
    fun ids(c: Context): Set<String> = NextWatch.parse(raw(c)).mapTo(HashSet()) { it.id }

    fun has(c: Context, id: String) = ids(c).contains(id)

    private fun save(c: Context, list: List<NextWatch.Entry>) = History.putRemote(c, NextWatch.record(raw(c), list))

    fun summary(c: Context): String = all(c).size.let { if (it == 0) "empty" else "$it  ►" }

    /** "Add to Next watch" on a title's page (not shown once you've started or finished it). */
    fun row(app: MainActivity, f: Full): Row? {
        if (started(app)(f.id) && !has(app, f.id)) return null
        lateinit var r: Row
        fun label() = if (has(app, f.id)) "Remove from Next watch" else "Add to Next watch"
        r = Row(label(), action = {
            val list = NextWatch.parse(raw(app))
            if (has(app, f.id)) { save(app, NextWatch.removed(list, f.id)); app.toast("Removed from Next watch") }
            else { save(app, NextWatch.added(list, NextWatch.Entry(f.id, f.type, f.name, f.year))); app.toast("Added to Next watch") }
            r.label = label()
            app.refreshTop()
        })
        return r
    }

    fun screen(app: MainActivity): Screen {
        lateinit var r: Reorder<NextWatch.Entry>
        r = Reorder(
            app,
            head = {
                if (r.items.isEmpty()) listOf(Row("Nothing lined up. On a film or show, choose 'Add to Next watch'."))
                else listOf(Row("In your order. Starting one moves it to Continue watching."))
            },
            items = all(app).toMutableList(),
            label = { Marks.title(Marks.NEXT, it.name) },
            detail = { listOf(it.year, MetaParse.typeLabel(it.type)).filter { s -> s.isNotBlank() }.joinToString("  ") },
            open = { Details.screenFor(app, it.id, it.type, it.name, it.year) },
            commit = { save(app, it) },
        )
        val s = r.build("Next watch")
        s.onShow = { r.items = all(app).toMutableList(); r.redraw() }
        return s
    }
}
