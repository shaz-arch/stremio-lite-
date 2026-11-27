package app.stremiolite

import android.os.Looper

/**
 * One Home redraw: lists that several lines need (your library, the Waiting list) are read once and
 * shared for that redraw only, then thrown away. Any change in the middle clears them, so a count
 * is never older than the redraw it's in.
 */
object Pass {
    private var active = false
    private val memo = HashMap<String, Any?>()

    fun <T> run(block: () -> T): T {
        if (active) return block()
        active = true
        try { return block() } finally { active = false; memo.clear() }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> memo(key: String, compute: () -> T): T {
        if (!active || Looper.myLooper() !== Looper.getMainLooper()) return compute()
        if (memo.containsKey(key)) return memo[key] as T
        return compute().also { memo[key] = it }
    }

    /** Something changed: read afresh. */
    fun changed() = memo.clear()
}
