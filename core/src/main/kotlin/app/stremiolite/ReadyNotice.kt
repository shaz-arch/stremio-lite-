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
}
