package app.stremiolite

import android.graphics.Bitmap

/**
 * One line on a screen.
 *  - [opens] builds the next screen only when the line is chosen, so nothing
 *    is held in memory until it's needed.
 *  - [action] runs something instead (for example, play a stream).
 *  - A line with neither is information only; OK does nothing.
 *  - [detail] can change after the screen is shown (e.g. a connection result).
 */
class Row(
    var label: String,
    var detail: String = "",
    val opens: (() -> Screen)? = null,
    val action: (() -> Unit)? = null,
    /** What the Play/Pause button does on this line (start or resume watching straight away). */
    val play: (() -> Unit)? = null,
)

/** One text screen: a title and a vertical list of rows. */
class Screen(
    val title: String,
    var rows: List<Row>,
    val tag: String = "",
) {
    /** Where the cursor was, so Back returns to the same line. */
    var position: Int = 0

    /** Optional picture shown beside the list (only the sign-in QR code uses it). */
    var image: Bitmap? = null

    /** Called each time the screen comes to the front, and when it leaves the front. */
    var onShow: (() -> Unit)? = null

    /** Called when the cursor moves to a line (lists use it to load the next page). */
    var onSelect: ((Int) -> Unit)? = null
    var onHide: (() -> Unit)? = null
}
