package app.stremiolite

/**
 * The status shape before a title in lists. The circles tell the story of watching it
 * (saved, partway, done) and the diamonds the streams (waiting, better coming, ready).
 * All plain text shapes: they take the text colour, never a colour emoji.
 * One per title, the most useful first.
 */
object Marks {
    const val WATCHED = "●"
    const val CONTINUE = "◐"
    const val READY = "◆"
    const val BETTER = "◇"
    const val WAITING = "◷"
    const val LIBRARY = "○"

    /** In priority order, with what each means (for a key on screen if wanted). */
    val KEY = listOf(
        LIBRARY to "in your Library", CONTINUE to "Continue watching", WATCHED to "watched",
        WAITING to "waiting for a stream", READY to "stream now ready", BETTER to "better version coming",
    )

    fun pick(watched: Boolean, continuing: Boolean, ready: Boolean, better: Boolean, waiting: Boolean, library: Boolean): String? = when {
        watched -> WATCHED
        continuing -> CONTINUE
        ready -> READY
        better -> BETTER
        waiting -> WAITING
        library -> LIBRARY
        else -> null
    }

    /** The title with its shape in a fixed slot, so names line up with or without one. */
    fun title(mark: String?, name: String) = (mark ?: " ") + " " + name
}
