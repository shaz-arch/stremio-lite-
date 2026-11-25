package app.stremiolite

/**
 * The sleep timer, like a TV's: set it from Home, and when the time is up the player is closed and
 * no next episode starts. "End of this one" lets the current film or episode finish and then stops.
 * It lives only while the app runs (never saved, never synced), and turns itself off once used.
 */
object Sleep {
    /** Minutes offered; 0 means "at the end of what's playing". */
    val CHOICES = listOf(15, 30, 45, 60, 90, 120)
    const val END_OF_THIS = 0

    private var endsAt = 0L          // when the time runs out (0 = off)
    private var atEnd = false        // stop once the current play finishes
    private var playFrom = 0L        // where the current play started, for a resume point
    private var playedAt = 0L

    /** When the timer last stopped playback (0 = never): a play or countdown that began before this was stopped by it. */
    var firedAt = 0L
        private set

    /** True when the timer has stopped things since [since] (e.g. since a player or countdown started). */
    fun stoppedSince(since: Long) = firedAt in since..Long.MAX_VALUE && firedAt > 0

    val on get() = endsAt > 0 || atEnd

    fun set(minutes: Int, now: Long = System.currentTimeMillis()) {
        if (minutes == END_OF_THIS) { atEnd = true; endsAt = 0 } else { atEnd = false; endsAt = now + minutes * 60_000L }
    }

    fun off() { endsAt = 0; atEnd = false }

    /** Milliseconds until the time is up, or null with no clock running. */
    fun left(now: Long = System.currentTimeMillis()): Long? = if (endsAt > 0) (endsAt - now).coerceAtLeast(0) else null

    fun dueAt(): Long = endsAt

    /** For the Home row: "Off", "23 min left", "At the end of this one". */
    fun summary(now: Long = System.currentTimeMillis()): String = when {
        atEnd -> "after what's playing"
        endsAt > 0 -> left(now)!!.let { ms -> val m = (ms + 59_999) / 60_000; if (m >= 60) "${m / 60} h ${m % 60} min left" else "$m min left" }
        else -> "off"
    }

    fun label(minutes: Int) = when {
        minutes == END_OF_THIS -> "At the end of this film or episode"
        minutes % 60 == 0 -> "${minutes / 60} hour" + if (minutes > 60) "s" else ""
        minutes > 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes minutes"
    }

    /** Call when a player starts, so a stop can estimate where you'd got to. */
    fun started(startAt: Long, now: Long = System.currentTimeMillis()) { playFrom = startAt; playedAt = now }

    /** Roughly where playback had reached (players that don't say, like VLC when it's closed for us). */
    fun reached(now: Long = System.currentTimeMillis()): Long = playFrom + (now - playedAt).coerceAtLeast(0)

    /**
     * Call when the time comes (or when a play ends): true when playback should stop now.
     * Uses up the timer, so it fires once.
     */
    fun timeUp(now: Long = System.currentTimeMillis()): Boolean {
        if (endsAt > 0 && now >= endsAt) { off(); firedAt = now; return true }
        return false
    }

    /**
     * Call when a film or episode ends on its own: true when the next one shouldn't start.
     * "End of this one" is used up here; a clock that has run out also stops it.
     */
    fun stopsNext(now: Long = System.currentTimeMillis()): Boolean {
        if (atEnd) { off(); firedAt = now; return true }
        return timeUp(now)
    }
}
