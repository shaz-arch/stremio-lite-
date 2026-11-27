package app.stremiolite

/** Splits [text] into lines of at most [width] characters at spaces (a long word gets a line of its own). */
fun wrapWords(text: String, width: Int): List<String> {
    val out = mutableListOf<String>()
    var cur = ""
    for (w in text.split(' ').filter { it.isNotEmpty() }) {
        cur = when {
            cur.isEmpty() -> w
            cur.length + 1 + w.length <= width -> "$cur $w"
            else -> { out += cur; w }
        }
    }
    if (cur.isNotEmpty()) out += cur
    return out.ifEmpty { listOf("") }
}
