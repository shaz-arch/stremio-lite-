package app.stremiolite

import android.content.Context

/**
 * The basket on Android: titles put aside while browsing, decided on later. Opening one shows its
 * page with every choice; once you choose (Next watch, Waiting, follow) it leaves the basket.
 */
object BasketList {
    private fun raw(c: Context) = History.rawRemote(c, Basket.ID)

    fun all(c: Context): List<NextWatch.Entry> = Basket.parse(raw(c))

    fun has(c: Context, id: String) = all(c).any { it.id == id }

    private fun save(c: Context, list: List<NextWatch.Entry>) = History.putRemote(c, Basket.record(raw(c), list))

    fun add(c: Context, e: NextWatch.Entry) = save(c, NextWatch.added(all(c), e))

    /** Out of the basket (only when you take it out: choosing something for it leaves it here to review). */
    fun done(c: Context, id: String) { if (has(c, id)) save(c, NextWatch.removed(all(c), id)) }

    /** Play on a title in a list: in or out of the basket. */
    fun toggle(app: MainActivity, id: String, type: String, name: String, year: String) {
        if (has(app, id)) { done(app, id); app.toast("Taken out of the basket: $name") }
        else { add(app, NextWatch.Entry(id, type, name, year)); app.toast("In the basket: $name") }
    }

    fun summary(c: Context): String = all(c).size.let { if (it == 0) "empty" else "$it  ►" }

    /** "Add to basket" on a title's page. */
    fun row(app: MainActivity, f: Full): Row {
        lateinit var r: Row
        fun label() = if (has(app, f.id)) "In your basket ✓ (remove)" else "Add to basket"
        r = Row(label(), action = {
            if (has(app, f.id)) { done(app, f.id); app.toast("Taken out of the basket") }
            else { add(app, NextWatch.Entry(f.id, f.type, f.name, f.year)); app.toast("In the basket: decide later") }
            r.label = label(); app.refreshTop()
        })
        return r
    }

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val list = all(app)
            if (list.isEmpty()) return listOf(Row("Empty. While browsing, choose 'Add to basket' on anything you like the look of."))
            val marks = History.marks(app)
            val rows = mutableListOf(Row("Open one to choose: Next watch, Favourites, or tell me when it's ready. It stays here until you take it out."))
            list.forEach { e ->
                rows += Row(Marks.title(marks[e.id], e.name) + if (e.year.isNotEmpty()) " (${e.year})" else "",
                    Catalogs.typeLabel(e.type).removeSuffix("s") + "  ►",
                    opens = { Details.screenFor(app, e.id, e.type, e.name, e.year) ?: Screen(e.name, listOf(Row("Your add-ons are still loading. Try again in a moment."))) },
                    play = { Details.screenFor(app, e.id, e.type, e.name, e.year, autoPlay = true)?.let { app.push(it) } })
            }
            rows += Row("────────────────────")
            rows += Row("Clear the basket", "►", opens = {
                confirmScreen(app, "Clear the basket?", "Takes all ${list.size} out, on every device. Whatever you chose for them (Next watch, Waiting) stays.", "Yes, clear it") {
                    save(app, emptyList()); app.toast("Basket cleared"); app.back()
                }
            })
            return rows
        }
        s = Screen("Basket", build())
        s.onShow = { s.rows = build(); app.refresh(s); History.pull(app) { if (app.isTop(s)) { s.rows = build(); app.refresh(s) } } }
        return s
    }
}
