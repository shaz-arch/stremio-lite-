package app.stremiolite

/** How long things take on this device (Settings › Checks › Timings). In memory only; never sent anywhere. */
object Timings {
    private class T(var last: Long, var slowest: Long, var count: Int)
    private val all = LinkedHashMap<String, T>()

    @Synchronized fun record(name: String, ms: Long) {
        val t = all.getOrPut(name) { T(ms, ms, 0) }
        t.last = ms; t.count++; if (ms > t.slowest) t.slowest = ms
    }

    /** Times [block] as [name]. */
    inline fun <R> time(name: String, block: () -> R): R {
        val start = System.nanoTime()
        try { return block() } finally { record(name, (System.nanoTime() - start) / 1_000_000) }
    }

    private fun fmt(ms: Long) = if (ms < 1_000) "$ms ms" else String.format(java.util.Locale.UK, "%.1f s", ms / 1000.0)

    /** (what, "last 120 ms · slowest 400 ms · 12×") in the order first seen. */
    @Synchronized fun lines(): List<Pair<String, String>> = all.entries.sortedBy { if (it.key.startsWith("·")) 1 else 0 }.map { (k, t) ->
        k to (if (t.count == 1) fmt(t.last) else "last ${fmt(t.last)} · slowest ${fmt(t.slowest)} · ${t.count}×")
    }
}
