package app.stremiolite

/**
 * The status shape before a title in lists. The filling circles tell how far you've got
 * (lined up, partway, done), the diamonds and clock the streams (waiting, better coming, ready),
 * ♡ a favourite and ○ one you follow.
 * All plain text shapes: they take the text colour, never a colour emoji.
 * One per title, the most useful first.
 */
object Marks {
    const val WATCHED = "●"
    const val CONTINUE = "◐"
    const val NEXT = "◔"
    const val READY = "◆"
    const val BETTER = "◇"
    const val WAITING = "◷"
    const val FAVOURITE = "♡"
    const val FOLLOWING = "○"

    /** In priority order, with what each means (for a key on screen if wanted). */
    val KEY = listOf(
        FAVOURITE to "a favourite", FOLLOWING to "following", NEXT to "Next watch", CONTINUE to "Continue watching", WATCHED to "watched",
        WAITING to "waiting for a stream", READY to "stream now ready", BETTER to "better version coming",
    )

    fun pick(watched: Boolean, continuing: Boolean, ready: Boolean, better: Boolean, waiting: Boolean, library: Boolean, next: Boolean = false, following: Boolean = false): String? = when {
        watched -> WATCHED
        continuing -> CONTINUE
        next -> NEXT
        ready -> READY
        better -> BETTER
        waiting -> WAITING
        library -> FAVOURITE
        following -> FOLLOWING
        else -> null
    }

    /** The title with its shape in a fixed slot, so names line up with or without one. */
    fun title(mark: String?, name: String) = (mark ?: " ") + " " + name
}
