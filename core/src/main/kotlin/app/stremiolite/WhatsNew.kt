package app.stremiolite

/**
 * Settings › About › What's new, also shown once after an update: plain notes per build, newest
 * first. A build with no note of its own is covered by the next one that has one.
 */
object WhatsNew {
    class Note(val build: Int, val lines: List<String>)

    val NOTES = listOf(
        Note(258, listOf(
            "Search my history (under Watched): anything you've played, on any device.",
            "Backup: keep this device's own settings (keys, kids lock, drives) on your account, locked with your passphrase.",
            "This screen: what's new after each update.",
        )),
        Note(257, listOf(
            "Your profile's name sits beside the line counter at the top.",
            "Settings › Look › Lines and bars: line style, the whole path in the title, counter, clock at the top or bottom, key hints.",
            "Settings › Look › Your own colours (and on Linux: copy kitty's, or use the terminal's own).",
        )),
        Note(254, listOf(
            "Kids lock: bedtime and a daily watch limit; the parent PIN allows the rest of the day.",
            "Watched › Your stats: time watching, films and episodes, your most-watched.",
            "Episode scores (★) in season lists.",
            "Settings › Your keys: send your keys to another device on the same Wi-Fi.",
            "When AIOStreams is down, titles go straight to your fail-safes for 10 minutes, then try it again.",
        )),
    )

    /** Notes for builds after [seen] up to [now], newest first. */
    fun since(seen: Int, now: Int): List<Note> = NOTES.filter { it.build in (seen + 1)..now }

    /** All the notes up to [now]. */
    fun upTo(now: Int): List<Note> = NOTES.filter { it.build <= now }
}
