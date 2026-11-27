package app.stremiolite.linux

import app.stremiolite.Rice

/** Settings › Look: your own colours, and lines, frame, banner and bars. */
object RiceUi {
    fun rows(rebuild: () -> Unit): List<Row> = listOf(
        Row("Use my terminal's colours", if (Prefs.termColours) "◄ On: kitty's theme ►" else "◄ Off ►", action = {
            Prefs.termColours = !Prefs.termColours
            App.toast(if (Prefs.termColours) "Using kitty's colours (and its see-through background, if set)" else "Using the Stremonino theme")
            rebuild()
        }),
        Row("Your own colours", (if (Prefs.theme == Look.OWN && !Prefs.termColours) "in use  " else "") + "►", opens = { colours() }),
        Row("Lines and bars", "►", opens = { style() }),
    )

    private fun set(c: Rice.Config) { Prefs.rice = c; Prefs.syncOut() }

    fun style(): Screen {
        lateinit var s: Screen
        fun build() {
            val c = Prefs.rice
            fun onOff(b: Boolean) = if (b) "◄ On ►" else "◄ Off ►"
            s.rows = listOf(
                Row("Lines", "◄ ${c.line.name} ►", action = { set(c.with(lines = Rice.next(c.lines, Rice.LINES.size))); build() }),
                Row("────────────────────"),
                Row("Title bar shows", if (c.path) "◄ The whole path (⌂ › …) ►" else "◄ This screen ►", action = { set(c.with(path = !c.path)); build() }),
                Row("Line counter (3/12)", onOff(c.counter), action = { set(c.with(counter = !c.counter)); build() }),
                Row("Clock", if (c.clockTop) "◄ Top bar ►" else "◄ Bottom bar ►", action = { set(c.with(clockTop = !c.clockTop)); build() }),
                Row("Key hints at the bottom", onOff(c.hints), action = { set(c.with(hints = !c.hints)); build() }),
                Row("────────────────────"),
                Row("Back to the plain look", action = { set(Rice.Config()); App.toast("Plain look"); build() }),
            )
            App.refresh()
        }
        s = Screen("Lines and bars", emptyList())
        build()
        return s
    }

    fun colours(): Screen {
        lateinit var s: Screen
        fun build() {
            val k = Look.colours()
            val rows = mutableListOf<Row>()
            rows += Row(if (Prefs.theme == Look.OWN) "✓ Using your own colours" else "Use these colours", action = {
                Prefs.termColours = false; Look.setColours(k); App.toast("Using your own colours"); build()
            })
            rows += Row("Copy my kitty colours", "►", action = {
                val kc = Look.kittyColours()
                if (kc == null) App.toast("Couldn't find colours in ~/.config/kitty/kitty.conf")
                else { Prefs.termColours = false; Look.setColours(kc); App.toast("Copied kitty's colours"); build() }
            })
            rows += Row("Start from a theme", "◄ ${themes.getOrNull(startFrom)?.name ?: ""} ►", action = {
                startFrom = (startFrom + 1) % themes.size; build()
            })
            rows += Row("  Copy its colours", action = {
                Look.setColours(Rice.readColours(ColoursOf.of(themes[startFrom]))!!); App.toast("Copied ${themes[startFrom].name}"); build()
            })
            rows += Row("────────────────────")
            Rice.LABELS.forEachIndexed { i, label ->
                rows += Row(info(label, k.list[i]), "change", action = {
                    App.ask("$label (hex, like #1e1e2e)", k.list[i]) { v ->
                        val hex = Rice.hex(v) ?: run { if (v != null) App.toast("That isn't a colour: use #rrggbb"); return@ask }
                        Prefs.termColours = false; Look.setColours(Look.colours().with(i, hex)); build()
                    }
                })
            }
            rows += Row("────────────────────")
            rows += Row(info("Or edit the file", Look.themeFile.path.replace(System.getProperty("user.home"), "~")))
            rows += Row("Changes there show within a couple of seconds, and go to your other devices.")
            s.rows = rows
            App.refresh()
        }
        s = Screen("Your own colours", emptyList())
        build()
        return s
    }

    private var startFrom = 0
}

/** A built-in theme's colours as hex, to start your own from. */
object ColoursOf {
    private fun hex(c: com.googlecode.lanterna.TextColor) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
    fun of(t: Theme) = listOf(t.bg, t.text, t.dim, t.title, t.bar, t.barText).joinToString(",") { hex(it) }
}
