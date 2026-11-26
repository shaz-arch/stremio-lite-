package app.stremiolite

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

/** Text size and colour theme, chosen in Settings. */
object Look {
    class Theme(
        val name: String,
        val background: Int,
        val text: Int,
        val dim: Int,
        val title: Int,
        val bar: Int,
        val barText: Int,
    ) {
        /** Row text: normal, and dark on the highlight bar. */
        val label = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(barText, text))
        val detail = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(barText, dim))
    }

    val themes = listOf(
        Theme("DOS blue", Color.rgb(0, 0, 170), Color.WHITE, Color.rgb(170, 170, 170), Color.rgb(255, 255, 85), Color.rgb(0, 170, 170), Color.BLACK),
        Theme("Terminal green", Color.BLACK, Color.rgb(51, 255, 102), Color.rgb(0, 170, 68), Color.rgb(153, 255, 153), Color.rgb(51, 255, 102), Color.BLACK),
        Theme("Amber", Color.rgb(16, 10, 0), Color.rgb(255, 176, 0), Color.rgb(190, 120, 0), Color.rgb(255, 214, 102), Color.rgb(255, 176, 0), Color.BLACK),
        Theme("Black & white", Color.BLACK, Color.WHITE, Color.rgb(190, 190, 190), Color.WHITE, Color.WHITE, Color.BLACK),
        // Newsprint: dark ink on grey paper, a charcoal bar for the cursor.
        Theme("Newspaper grey", Color.rgb(214, 212, 206), Color.rgb(26, 26, 26), Color.rgb(96, 96, 92), Color.BLACK, Color.rgb(58, 58, 56), Color.rgb(236, 234, 228)),
        // Lavender: soft white on near-black, periwinkle headings and bar (like Android's own dark screens).
        Theme("Lavender", Color.rgb(20, 18, 24), Color.rgb(230, 225, 229), Color.rgb(147, 143, 153), Color.rgb(190, 194, 255), Color.rgb(190, 194, 255), Color.rgb(27, 29, 72)),
    )

    class Size(val name: String, val sp: Float)

    val sizes = listOf(Size("Small", 16f), Size("Medium", 20f), Size("Large", 24f), Size("Extra large", 28f))

    private fun prefs(c: Context) = Profiles.prefsFor(c, "settings")

    fun theme(c: Context): Theme = themes.getOrElse(prefs(c).getInt("theme", 0)) { themes[0] }
    fun size(c: Context): Size = sizes.getOrElse(prefs(c).getInt("textSize", 1)) { sizes[1] }

    fun nextTheme(c: Context) {
        prefs(c).edit().putInt("theme", (themes.indexOf(theme(c)) + 1) % themes.size).apply()
        Settings.syncOut(c)
    }
    fun nextSize(c: Context) = prefs(c).edit().putInt("textSize", (sizes.indexOf(size(c)) + 1) % sizes.size).apply()

    /** Clock in the bottom-right corner; the pattern is for java.text.SimpleDateFormat, empty = off. */
    class Clock(val name: String, val pattern: String)

    val clocks = listOf(
        Clock("Day/month, 24 h", "dd/MM HH:mm"),
        Clock("Weekday, day/month, 24 h", "EEE dd/MM HH:mm"),
        Clock("Time only, 24 h", "HH:mm"),
        Clock("Day/month, 12 h", "dd/MM h:mm a"),
        Clock("Off", ""),
        // With seconds (added after "Off" so existing choices keep their place in the shared settings).
        Clock("Day/month, 24 h, seconds", "dd/MM HH:mm:ss"),
        Clock("Weekday, day/month, 24 h, seconds", "EEE dd/MM HH:mm:ss"),
        Clock("Time only, 24 h, seconds", "HH:mm:ss"),
        Clock("Day/month, 12 h, seconds", "dd/MM h:mm:ss a"),
    )

    fun clock(c: Context): Clock = clocks.getOrElse(prefs(c).getInt("clock", 0)) { clocks[0] }
    fun nextClock(c: Context) {
        prefs(c).edit().putInt("clock", (clocks.indexOf(clock(c)) + 1) % clocks.size).apply()
        Settings.syncOut(c)
    }
}
