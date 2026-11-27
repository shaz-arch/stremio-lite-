package app.stremiolite

import org.json.JSONObject

/**
 * Settings › Look: your own colours, the line style, and what the
 * top and bottom bars show. Shared through the account like the theme (unless the look is kept to
 * one device).
 */
object Rice {
    class Lines(val name: String, val rule: Char, val ascii: Boolean = false)
    val LINES = listOf(Lines("Single ─", '─'), Lines("Double ═", '═'), Lines("Heavy ━", '━'), Lines("Dotted ┄", '┄'), Lines("ASCII -", '-', ascii = true))

    class Config(
        val lines: Int = 0,
        /** Title bar: the whole way here (⌂ › Settings › Look) instead of just this screen. */
        val path: Boolean = false,
        val counter: Boolean = true,
        /** The clock in the top bar instead of the bottom one. */
        val clockTop: Boolean = false,
        /** The key hints in the bottom bar. */
        val hints: Boolean = true,
    ) {
        val line get() = LINES.getOrElse(lines) { LINES[0] }
        /** Between the profile (or a screen's tag) and the line counter. */
        val divider get() = if (line.ascii) "|" else "│"
        fun json(): String = JSONObject().put("lines", lines).put("path", path)
            .put("counter", counter).put("clockTop", clockTop).put("hints", hints).toString()
        fun with(lines: Int = this.lines, path: Boolean = this.path,
                 counter: Boolean = this.counter, clockTop: Boolean = this.clockTop, hints: Boolean = this.hints) =
            Config(lines, path, counter, clockTop, hints)
    }

    fun read(raw: String?): Config {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Config()
        return Config(o.optInt("lines"), o.optBoolean("path"),
            o.optBoolean("counter", true), o.optBoolean("clockTop"), o.optBoolean("hints", true))
    }

    /** A row's text in the chosen line style: its ─ rules, and (ASCII) its ► ◄ markers. */
    fun style(s: String, c: Config): String {
        val l = c.line
        if (l.rule == '─' && !l.ascii) return s
        var out = if ('─' in s) s.replace('─', l.rule) else s
        if (l.ascii) out = out.replace('►', '>').replace('◄', '<').replace('▶', '>').replace('…', '~')
        return out
    }

    fun next(n: Int, size: Int) = (n + 1) % size

    // ---- your own colours -----------------------------------------------------------------------

    /** Background, text, dim text, titles, bar (rules and the cursor), the text on the bar: as #rrggbb. */
    class Colours(val bg: String, val text: String, val dim: String, val title: String, val bar: String, val barText: String) {
        val list get() = listOf(bg, text, dim, title, bar, barText)
        fun joined() = list.joinToString(",")
        fun with(i: Int, hex: String): Colours { val l = list.toMutableList(); l[i] = hex; return Colours(l[0], l[1], l[2], l[3], l[4], l[5]) }

        /** As the theme file (like kitty.conf). */
        fun conf(): String = buildString {
            append("# Stremonino colours: Settings › Look › Your own colours. Hex, like kitty.conf.\n")
            append("# Save the file and Stremonino picks it up within a couple of seconds.\n")
            NAMES.zip(list).forEach { (n, v) -> append(n.padEnd(11)).append(v).append('\n') }
        }
    }

    /** The right of the title bar: "SR │ 3/12" (profile or tag, then the counter). */
    fun right(tag: String, counter: String, c: Config): String =
        listOf(tag, counter).filter { it.isNotEmpty() }.joinToString("  ${c.divider}  ")

    /** The parts, as named in the theme file, and as shown in Settings. */
    val NAMES = listOf("background", "text", "dim", "title", "bar", "bar_text")
    val LABELS = listOf("Background", "Text", "Dim text", "Titles", "Bar and rules", "Text on the bar")

    /** Lavender's colours, a starting point. */
    val DEFAULT = Colours("#141218", "#e6e1e5", "#938f99", "#bec2ff", "#bec2ff", "#1b1d48")

    fun hex(s: String?): String? {
        val t = s?.trim()?.removePrefix("#")?.lowercase() ?: return null
        val six = when {
            Regex("[0-9a-f]{6}").matches(t) -> t
            Regex("[0-9a-f]{3}").matches(t) -> t.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return "#$six"
    }

    fun rgb(hex: String): Triple<Int, Int, Int> {
        val v = (hex(hex) ?: "#000000").removePrefix("#").toInt(16)
        return Triple((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    fun readColours(raw: String?): Colours? {
        val p = raw?.split(',')?.map { hex(it) } ?: return null
        if (p.size != 6 || p.any { it == null }) return null
        return Colours(p[0]!!, p[1]!!, p[2]!!, p[3]!!, p[4]!!, p[5]!!)
    }

    /** Reads a theme file (name value lines); missing parts come from [base]. */
    fun parseConf(text: String, base: Colours = DEFAULT): Colours {
        val m = HashMap<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { l ->
            val k = l.substringBefore(' ').substringBefore('\t').trim().lowercase(); val v = hex(l.substring(k.length).trim())
            if (v != null) m[k] = v
        }
        return Colours(m["background"] ?: base.bg, m["text"] ?: base.text, m["dim"] ?: base.dim, m["title"] ?: base.title, m["bar"] ?: base.bar, m["bar_text"] ?: base.barText)
    }

    /**
     * Colours from kitty's settings ([read] gives a file's text from its path, for includes):
     * background and foreground, color8 for dim text, color11 (or 3) for titles, color4 for the bar.
     */
    fun fromKitty(text: String, read: (String) -> String?, depth: Int = 0): Colours? {
        val m = HashMap<String, String>()
        fun take(t: String, d: Int) {
            for (raw in t.lineSequence()) {
                val l = raw.trim(); if (l.isEmpty() || l.startsWith("#")) continue
                val k = l.substringBefore(' ').substringBefore('\t'); val v = l.substring(k.length).trim()
                if ((k == "include" || k == "globinclude") && d < 4) { read(v)?.let { take(it, d + 1) }; continue }
                hex(v.substringBefore(' '))?.let { m[k] = it }
            }
        }
        take(text, depth)
        val bg = m["background"] ?: return null
        val fg = m["foreground"] ?: return null
        return Colours(bg, fg, m["color8"] ?: m["color7"] ?: fg, m["color11"] ?: m["color3"] ?: fg,
            m["color4"] ?: m["selection_background"] ?: fg, m["selection_foreground"]?.takeIf { m["color4"] == null } ?: bg)
    }
}
