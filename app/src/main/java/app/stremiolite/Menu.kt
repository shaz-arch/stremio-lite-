package app.stremiolite

/**
 * One line on a screen.
 *  - [opens] builds the next screen only when the line is chosen, so nothing
 *    is held in memory until it's needed.
 *  - [action] runs something instead (for example, play a stream).
 *  - A line with neither is information only; OK does nothing.
 */
class Row(
    val label: String,
    val detail: String = "",
    val opens: (() -> Screen)? = null,
    val action: (() -> Unit)? = null,
)

/** One text screen: a title and a vertical list of rows. */
class Screen(
    val title: String,
    val rows: List<Row>,
    val tag: String = "",
) {
    /** Where the cursor was, so Back returns to the same line. */
    var position: Int = 0
}
