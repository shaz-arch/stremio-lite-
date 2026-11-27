package app.stremiolite

/** Settings › Look: your own colours, and lines, frame, banner and bars ([Rice]). */
object RiceUi {
    private fun set(app: MainActivity, c: Rice.Config) { Look.setRice(app, c); app.applyLook(); app.updateClock() }

    fun style(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val c = Look.rice(app)
            fun onOff(b: Boolean) = if (b) "◄ On ►" else "◄ Off ►"
            fun redo(n: Rice.Config) { set(app, n); s.rows = build(); app.refresh(s) }
            return listOf(
                Row("Lines", "◄ ${c.line.name} ►", action = { redo(c.with(lines = Rice.next(c.lines, Rice.LINES.size))) }),
                Row("────────────────────"),
                Row("Title bar shows", if (c.path) "◄ The whole path ►" else "◄ This screen ►", action = { redo(c.with(path = !c.path)) }),
                Row("Line counter (3/12)", onOff(c.counter), action = { redo(c.with(counter = !c.counter)) }),
                Row("Clock", if (c.clockTop) "◄ Top bar ►" else "◄ Bottom bar ►", action = { redo(c.with(clockTop = !c.clockTop)) }),
                Row("Key hints at the bottom", onOff(c.hints), action = { redo(c.with(hints = !c.hints)) }),
                Row("────────────────────"),
                Row("Back to the plain look", action = { redo(Rice.Config()); app.toast("Plain look") }),
            )
        }
        s = Screen("Lines and bars", build())
        return s
    }

    private var startFrom = 0

    fun colours(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val k = Look.colours(app)
            val using = Profiles.prefsFor(app, "settings").getInt("theme", 0) == Look.OWN
            fun use(n: Rice.Colours) { Look.setColours(app, n); app.applyLook(); s.rows = build(); app.refresh(s) }
            val rows = mutableListOf(
                Row(if (using) "✓ Using your own colours" else "Use these colours", action = { use(k); app.toast("Using your own colours") }),
                Row("Start from a theme", "◄ ${Look.themes[startFrom].name} ►", action = { startFrom = (startFrom + 1) % Look.themes.size; s.rows = build(); app.refresh(s) }),
                Row("  Copy its colours", action = { use(Look.coloursOf(Look.themes[startFrom])); app.toast("Copied ${Look.themes[startFrom].name}") }),
                Row("────────────────────"),
            )
            Rice.LABELS.forEachIndexed { i, label ->
                rows += Row(info(label, k.list[i]), "change", action = {
                    val input = android.widget.EditText(app).apply { setText(k.list[i]); setSingleLine(); setSelection(text.length) }
                    android.app.AlertDialog.Builder(app).setTitle("$label (hex, like #1e1e2e)").setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val hex = Rice.hex(input.text.toString())
                            if (hex == null) app.toast("That isn't a colour: use #rrggbb") else use(Look.colours(app).with(i, hex))
                        }
                        .setNegativeButton("Cancel", null).show()
                })
            }
            rows += Row("────────────────────")
            rows += Row("Easier on the computer: Settings › Look › Copy my kitty colours there, and they come here too.")
            return rows
        }
        s = Screen("Your own colours", build())
        return s
    }
}
