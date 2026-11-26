package app.stremiolite

import android.content.Context

/** Android side of [NextWatch]: the list on the account, its screen (in your order), and the button on a title. */
object NextList {
    private fun raw(c: Context) = History.rawRemote(c, NextWatch.ID)

    private fun started(c: Context): (String) -> Boolean {
        val m = History.marks(c)
        // An episode put here from Ready leaves once you've started that episode.
        return { id -> if (':' in id) History.progress(c, id) != null else m[id] == Marks.WATCHED || m[id] == Marks.CONTINUE }
    }

    fun add(c: Context, e: NextWatch.Entry) { save(c, NextWatch.added(NextWatch.parse(raw(c)), e)); BasketList.done(c, e.id) }

    /** Opens an episode put in Next watch (its show's details are fetched first). */
    private fun openEpisode(app: MainActivity, e: NextWatch.Entry, play: Boolean = false) {
        val addons = Home.allAddons()
        Net.async({ MetaParse.fetch(addons, "series", e.show) }) { r ->
            val f = r.getOrNull()
            val v = f?.videos?.firstOrNull { it.id == e.id }
            if (f == null || v == null) { app.toast("Couldn't load that episode"); return@async }
            app.push(if (play) Details.playEpisode(app, f, v) else Details.episodeScreen(app, f, v))
        }
    }

    private fun open(app: MainActivity, e: NextWatch.Entry) {
        if (e.isEpisode) openEpisode(app, e) else Details.screenFor(app, e.id, e.type, e.name, e.year)?.let { app.push(it) }
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

    fun summary(c: Context): String {
        val list = all(c)
        if (list.isEmpty()) return "empty"
        val w = Waiting.list(c).associate { it.id to (it.better to it.ready) }
        val ready = list.count { it.isEpisode || NextStatus.effective(it.id) { id -> w[id] }?.group == NextStatus.Group.READY }
        val known = list.count { it.isEpisode || NextStatus.effective(it.id) { id -> w[id] } != null }
        if (known < list.size && ready == 0) return "${list.size}  ►"
        return listOfNotNull("$ready ready", (list.size - ready).takeIf { it > 0 }?.let { "$it on the way" }).joinToString(" · ") + "  ►"
    }

    /** "Add to Next watch" on a title's page (not shown once you've started or finished it). */
    fun row(app: MainActivity, f: Full): Row? {
        if (started(app)(f.id) && !has(app, f.id)) return null
        lateinit var r: Row
        fun label() = if (has(app, f.id)) "Remove from Next watch" else "Add to Next watch"
        r = Row(label(), action = {
            val list = NextWatch.parse(raw(app))
            if (has(app, f.id)) { save(app, NextWatch.removed(list, f.id)); app.toast("Removed from Next watch") }
            else { add(app, NextWatch.Entry(f.id, f.type, f.name, f.year)); app.toast("Added to Next watch") }
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

    /** A title's status, with what Waiting for streams says first (you asked to wait, or it found one). */
    private fun statusOf(app: MainActivity): (String) -> NextStatus.Status? {
        val w = Waiting.list(app).associate { it.id to (it.better to it.ready) }
        return { id -> if (':' in id) NextStatus.Status(NextStatus.Group.READY, "new episode") else NextStatus.effective(id) { w[it] } }
    }

    private fun detail(st: NextStatus.Status?): String = st?.detail ?: "checking…"

    fun screen(app: MainActivity): Screen = if (byStatus(app)) statusScreen(app) else orderScreen(app)

    /** Grouped: Ready now, Coming soon (soonest first), Waiting for streams; your order inside each. */
    private fun statusScreen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build() {
            val list = all(app)
            val rows = mutableListOf(viewRow(app))
            if (list.isEmpty()) rows += Row("Nothing lined up. On a film or show, choose 'Add to Next watch'.")
            val status = statusOf(app)
            fun row(e: NextWatch.Entry): Row {
                val st = status(e.id)
                return Row(Marks.title(st?.shape ?: Marks.NEXT, e.name), detail(st) + "  ►", action = { open(app, e) },
                    play = { if (e.isEpisode) openEpisode(app, e, play = true) else Details.screenFor(app, e.id, e.type, e.name, e.year, autoPlay = true)?.let { app.push(it) } })
            }
            // Only what plays now; the rest is on its way (in Waiting or Following) and comes in by itself.
            val groups = NextStatus.grouped(list, status)
            val ready = groups.firstOrNull { it.first == NextStatus.Group.READY }?.second.orEmpty()
            val coming = groups.filter { it.first != NextStatus.Group.READY }
            ready.forEach { rows += row(it) }
            if (list.isNotEmpty() && ready.isEmpty()) rows += Row(if (coming.any { it.first == null }) "Checking what's ready…" else "Nothing ready to play yet.")
            val n = coming.sumOf { it.second.size }
            if (n > 0) {
                rows += Row("────────────────────")
                rows += Row("On the way", "$n  ►", opens = {
                    Screen("On the way", listOf(Row("These come into Next watch by themselves once they play.")) + coming.flatMap { (g, items) ->
                        listOf(Row("──── ${g?.heading ?: "Checking…"} ────")) + items.map { row(it) }
                    })
                })
            }
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
            label = { Marks.title(statusOf(app)(it.id)?.shape ?: Marks.NEXT, it.name) },
            detail = { detail(statusOf(app)(it.id)) },
            open = { if (it.isEpisode) { openEpisode(app, it); null } else Details.screenFor(app, it.id, it.type, it.name, it.year) },
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
        val todo = NextStatus.missing(all(app).filter { !it.isEpisode })
        if (todo.isEmpty()) return
        val addons = Home.allAddons()
        val key = Settings.tmdbKey(app)
        val region = Settings.watchRegion(app)
        fun next(i: Int) {
            if (!app.inStack(s)) return
            if (app.playerOpen) { Net.later(10_000) { next(i) }; return }
            if (i >= todo.size) {
                Waiting.follow(app, all(app))
                return
            }
            Net.async({ NextStatus.check(addons, todo[i], System.currentTimeMillis(), key, region) }) { _ -> if (app.inStack(s)) changed(); next(i + 1) }
        }
        next(0)
    }
}
