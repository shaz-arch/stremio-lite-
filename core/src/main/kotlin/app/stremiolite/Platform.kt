package app.stremiolite

/**
 * What each front-end (the Android app, the Linux app, and any later one) gives the shared code,
 * so logic can live here once instead of being copied into every front-end. Set at start-up,
 * before any screen opens: see [Platform].
 */
interface Async {
    /** Runs [work] in the background, then [done] on the screen's thread. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit)

    /** Runs [block] on the screen's thread after [delayMs]. */
    fun later(delayMs: Long, block: () -> Unit)
}

/**
 * Small named stores of text values for the current profile. Android keeps each in its own
 * SharedPreferences ("binge", or "binge-<profile>"); Linux in a JSON file in the profile's
 * folder ("binge.json"), so values saved before this existed are still found.
 */
interface Storage {
    /** The value under [key] in [store], or null. */
    fun get(store: String, key: String): String?

    /** Every key in [store]. */
    fun keys(store: String): List<String>

    /** Applies all of [changes] to [store] at once; a null value removes that key. */
    fun edit(store: String, changes: Map<String, String?>)
}

object Platform {
    lateinit var async: Async
    lateinit var storage: Storage
}
