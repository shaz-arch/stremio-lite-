package app.stremiolite.linux

/**
 * Pick up and drop: "Move" picks an item up, Up and Down carry it through the list, OK drops it.
 * Items of a different [kindOf] (folders and rows in one folder) only move among their own kind.
 */
class Reorder<T>(
    private val head: () -> List<Row>,
    var items: MutableList<T>,
    private val label: (T) -> String,
    private val detail: (T) -> String,
    private val open: (T) -> Screen?,
    private val commit: (List<T>) -> Unit,
    private val kindOf: (T) -> Int = { 0 },
) {
    private enum class Mode { NORMAL, PICKING, CARRYING }
    private var mode = Mode.NORMAL
    private var carried: T? = null
    private var before: List<T> = emptyList()
    private var busy = false
    lateinit var screen: Screen
    private var offset = 0

    fun build(title: String, tag: String = ""): Screen {
        screen = Screen(title, emptyList(), tag)
        screen.rows = rows()
        screen.onSelect = { pos -> follow(pos) }
        screen.onHide = {
            // Leaving mid-move puts things back as they were.
            if (mode == Mode.CARRYING && !App.inStack(screen)) { items = before.toMutableList(); mode = Mode.NORMAL; carried = null }
        }
        return screen
    }

    /** Rebuilds the rows (after something outside changed the items). */
    fun redraw(moveTo: Int? = null) {
        screen.rows = rows()
        if (moveTo != null) screen.moveTo = moveTo
        App.refresh()
    }

    private fun modeRow(): Row = when (mode) {
        Mode.NORMAL -> Row("Move something", "pick up  ►", action = {
            mode = Mode.PICKING
            App.toast(if (false) "Tap what you want to move" else "Choose what to move and press OK")
            redraw(offset)
        })
        Mode.PICKING -> Row("Cancel", "choose what to move", action = { mode = Mode.NORMAL; redraw() })
        Mode.CARRYING -> Row("Cancel the move", "puts it back", action = {
            items = before.toMutableList(); mode = Mode.NORMAL; carried = null; redraw()
        })
    }

    private fun rows(): List<Row> {
        val h = head() + modeRow()
        offset = h.size
        return h + items.mapIndexed { i, it ->
            val isCarried = mode == Mode.CARRYING && it === carried
            Row(
                if (isCarried) "► ${label(it)} ◄" else label(it),
                when {
                    isCarried -> if (false) "tap where it goes" else "↑↓ move · OK drops"
                    mode == Mode.PICKING -> "pick up"
                    mode == Mode.CARRYING -> if (false) "drop here" else ""
                    else -> detail(it)
                },
                action = { ok(i) },
            )
        }
    }

    private fun ok(i: Int) {
        val it = items.getOrNull(i) ?: return
        when (mode) {
            Mode.NORMAL -> open(it)?.let { App.push(it) }
            Mode.PICKING -> {
                carried = it; before = items.toList(); mode = Mode.CARRYING
                redraw(offset + i)
            }
            Mode.CARRYING -> {
                val c = carried ?: return
                if (it !== c && kindOf(it) == kindOf(c)) { items.remove(c); items.add(i, c) }
                mode = Mode.NORMAL; carried = null
                commit(items.toList())
                redraw(offset + items.indexOf(c))
            }
        }
    }

    /** Up/Down while carrying: the item follows the cursor, within its own kind. */
    private fun follow(pos: Int) {
        if (busy || mode != Mode.CARRYING || false) return
        val c = carried ?: return
        val from = items.indexOf(c)
        var to = (pos - offset).coerceIn(0, items.lastIndex)
        val same = items.indices.filter { kindOf(items[it]) == kindOf(c) }
        to = to.coerceIn(same.first(), same.last())
        if (to == from && pos == offset + from) return
        busy = true
        if (to != from) { items.removeAt(from); items.add(to, c) }
        redraw(offset + to)
        busy = false
    }
}
