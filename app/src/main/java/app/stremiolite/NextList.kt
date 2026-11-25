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

    // ---- the list: by status (default) or in your order ------------------------------------------

    private fun byStatus(c: Context) = Profiles.prefsFor(c, "settings").getString("nextView", "status") == "status"
    private fun setByStatus(c: Context, on: Boolean) { Profiles.prefsFor(c, "settings").edit().putString("nextView", if (on) "status" else "order").apply() }

    private fun viewRow(app: MainActivity) = Row("View", if (byStatus(app)) "◄ By status ►" else "◄ Your order ►", action = {
        setByStatus(app, !byStatus(app)); app.replaceTop(screen(app))
    })

    private fun detail(e: NextWatch.Entry): String = NextStatus.of(e.id)?.detail ?: "checking…"

    fun screen(app: MainActivity): Screen = if (byStatus(app)) statusScreen(app) else orderScreen(app)

    /** Grouped: Ready now, Coming soon (soonest first), Waiting for streams; your order inside each. */
    private fun statusScreen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build() {
            val list = all(app)
            val rows = mutableListOf(viewRow(app))
            if (list.isEmpty()) rows += Row("Nothing lined up. On a film or show, choose 'Add to Next watch'.")
            NextStatus.grouped(list).forEach { (g, items) ->
                rows += Row("──── ${g?.heading ?: "Checking…"} ────")
                items.forEach { e ->
                    val st = NextStatus.of(e.id)
                    rows += Row(Marks.title(st?.shape ?: Marks.NEXT, e.name), detail(e) + "  ►", opens = { Details.screenFor(app, e.id, e.type, e.name, e.year) })
                }
            }
            if (list.isNotEmpty()) rows += Row("────────────────────")
            if (list.isNotEmpty()) rows += Row("Films not ready yet are followed: you'll be told when they are.")
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Next watch", emptyList())
        s.onShow = { build(); fill(app, s) { build() } }
        return s
    }

    private fun orderScreen(app: MainActivity): Screen {
        lateinit var r: Reorder<NextWatch.Entry>
        r = Reorder(
            app,
            head = {
                listOf(viewRow(app)) +
                    if (r.items.isEmpty()) listOf(Row("Nothing lined up. On a film or show, choose 'Add to Next watch'."))
                    else listOf(Row("In your order. Starting one moves it to Continue watching."))
            },
            items = all(app).toMutableList(),
            label = { Marks.title(NextStatus.of(it.id)?.shape ?: Marks.NEXT, it.name) },
            detail = { detail(it) },
            open = { Details.screenFor(app, it.id, it.type, it.name, it.year) },
            commit = { save(app, it) },
        )
        val s = r.build("Next watch")
        s.onShow = { r.items = all(app).toMutableList(); r.redraw(); fill(app, s) { r.redraw() } }
        return s
    }

    /**
     * Checks titles one at a time while the list is open (never while something plays), then
     * follows films that aren't ready in Waiting for streams so you're told when they are.
     */
    private fun fill(app: MainActivity, s: Screen, changed: () -> Unit) {
        val todo = NextStatus.missing(all(app))
        if (todo.isEmpty()) return
        val addons = Home.allAddons()
        fun next(i: Int) {
            if (!app.inStack(s)) return
            if (app.playerOpen) { Net.later(10_000) { next(i) }; return }
            if (i >= todo.size) {
                Waiting.follow(app, all(app).filter { NextStatus.of(it.id)?.group?.let { g -> g != NextStatus.Group.READY } == true })
                return
            }
            Net.async({ NextStatus.check(addons, todo[i]) }) { _ -> if (app.inStack(s)) changed(); next(i + 1) }
        }
        next(0)
    }
}
