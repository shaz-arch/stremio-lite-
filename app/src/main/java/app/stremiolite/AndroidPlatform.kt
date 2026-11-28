package app.stremiolite

import android.content.Context

/** The Android app's side of [Platform]: background threads, and each profile's SharedPreferences. */
object AndroidPlatform {
    /** Safe to call more than once (the app and its background job both do). */
    fun install(context: Context) {
        val c = context.applicationContext
        Platform.async = object : Async {
            override fun <T> background(work: () -> T, done: (Result<T>) -> Unit) = Net.async(work, done)
            override fun later(delayMs: Long, block: () -> Unit) {
                Net.later(delayMs, Runnable { block() })
            }
        }
        Platform.storage = object : Storage {
            override fun get(store: String, key: String): String? = Profiles.prefsFor(c, store).getString(key, null)
            override fun keys(store: String): List<String> = Profiles.prefsFor(c, store).all.keys.toList()
            override fun edit(store: String, changes: Map<String, String?>) {
                val e = Profiles.prefsFor(c, store).edit()
                for ((k, v) in changes) if (v == null) e.remove(k) else e.putString(k, v)
                e.apply()
            }
        }
    }
}
