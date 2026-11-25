package app.stremiolite

/**
 * The line at the top of Home when a film you're waiting on has a stream (or a better version
 * turned up): shown until you open it or the Waiting screen, then gone. "Seen" is kept per device.
 */
object ReadyNotice {
    /** [ready] holds each waiting film's name and when its stream turned up (0 = still waiting). */
    fun unseen(ready: List<Pair<String, Long>>, seenAt: Long): List<String> =
        ready.filter { it.second > seenAt }.sortedByDescending { it.second }.map { it.first }

    fun label(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "Ready to watch: ${names[0]}"
        2 -> "Ready to watch: ${names[0]} and ${names[1]}"
        else -> "Ready to watch: ${names[0]} and ${names.size - 1} more"
    }

    /**
     * New episodes not yet seen, as (show name, "S03E04") newest first:
     * "New episode: The Bear S03E04", "New episodes: The Bear (3)",
     * "New episodes: The Bear and Slow Horses", "New episodes: The Bear and 2 more shows".
     */
    fun episodesLabel(eps: List<Pair<String, String>>): String? {
        if (eps.isEmpty()) return null
        if (eps.size == 1) return "New episode: ${eps[0].first} ${eps[0].second}"
        val shows = eps.map { it.first }.distinct()
        return when (shows.size) {
            1 -> "New episodes: ${shows[0]} (${eps.size})"
            2 -> "New episodes: ${shows[0]} and ${shows[1]}"
            else -> "New episodes: ${shows[0]} and ${shows.size - 1} more shows"
        }
    }

    /** The episode ids already seen, stored as one line (newest kept, so it never grows much). */
    fun readSeen(raw: String?): Set<String> = raw.orEmpty().split('|').filter { it.isNotEmpty() }.toSet()

    fun writeSeen(old: Set<String>, add: Collection<String>): String =
        (add.toList() + old.filter { it !in add }).take(400).joinToString("|")
}

/** When a film leaves Waiting for streams by itself. */
object WaitRules {
    /**
     * Waiting for a first stream: once you've started or finished it. Waiting for a better version:
     * once one was found and you've played it since (it's in Continue watching or finished again).
     */
    fun done(better: Boolean, ready: Long, continuing: Boolean, watched: Boolean, lastPlayed: Long): Boolean =
        if (!better) continuing || watched
        else ready > 0 && (continuing || watched) && lastPlayed > ready
}
