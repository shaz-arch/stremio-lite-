package app.stremiolite

/** Where "Coming soon" goes among the Browse rows: straight after In Cinemas, or first if there's none. */
object ComingSoonPlace {
    fun after(names: List<String>): Int {
        val i = names.indexOfFirst { it.lowercase().contains("in cinemas") }
        return if (i >= 0) i + 1 else 0
    }
}
