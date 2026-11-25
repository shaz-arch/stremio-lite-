package app.stremiolite.linux

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/** One line of a screen: [opens] another screen, or runs [action]; [play] is the P key. */
class Row(
    var label: String,
    var detail: String = "",
    val opens: (() -> Screen)? = null,
    var action: (() -> Unit)? = null,
    val play: (() -> Unit)? = null,
)

/** One text screen: a title and a list of rows. */
class Screen(val title: String, var rows: List<Row>, var tag: String = "") {
    var pos = 0
    var top = 0
    /** Set to move the cursor on the next redraw. */
    var moveTo: Int? = null
    /** A QR code (lines of block characters) shown beside the list. */
    var qr: List<String>? = null
    var onShow: (() -> Unit)? = null
    var onHide: (() -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    /** Games: takes the arrows, Enter/space and p ("up", "down", "left", "right", "ok", "play") instead of the list. */
    var onKey: ((String) -> Unit)? = null
    /** Lists worth coming back to: the cursor returns to the same line next time. */
    var key: String? = null
}

class Theme(val name: String, val bg: TextColor, val text: TextColor, val dim: TextColor, val title: TextColor, val bar: TextColor, val barText: TextColor)

/** The same four themes as the Android app (the choice is shared through the account). */
val themes = listOf(
    Theme("DOS blue", TextColor.RGB(0, 0, 170), TextColor.RGB(255, 255, 255), TextColor.RGB(170, 170, 170), TextColor.RGB(255, 255, 85), TextColor.RGB(0, 170, 170), TextColor.RGB(0, 0, 0)),
    Theme("Terminal green", TextColor.RGB(0, 0, 0), TextColor.RGB(51, 255, 102), TextColor.RGB(0, 170, 68), TextColor.RGB(153, 255, 153), TextColor.RGB(51, 255, 102), TextColor.RGB(0, 0, 0)),
    Theme("Amber", TextColor.RGB(16, 10, 0), TextColor.RGB(255, 176, 0), TextColor.RGB(190, 120, 0), TextColor.RGB(255, 214, 102), TextColor.RGB(255, 176, 0), TextColor.RGB(0, 0, 0)),
    Theme("Black & white", TextColor.RGB(0, 0, 0), TextColor.RGB(255, 255, 255), TextColor.RGB(190, 190, 190), TextColor.RGB(255, 255, 255), TextColor.RGB(255, 255, 255), TextColor.RGB(0, 0, 0)),
    Theme("Newspaper grey", TextColor.RGB(214, 212, 206), TextColor.RGB(26, 26, 26), TextColor.RGB(96, 96, 92), TextColor.RGB(0, 0, 0), TextColor.RGB(58, 58, 56), TextColor.RGB(236, 234, 228)),
)

/** "Label               value" lines, like the Android app. */
fun info(label: String, value: String) = "${label.padEnd(18)} $value"

/** The terminal, the screen stack, and a main loop that also runs background results. */
object App {
    private val stack = mutableListOf<Screen>()
    private val posts = ConcurrentLinkedQueue<() -> Unit>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r).apply { isDaemon = true } }
    private val places = HashMap<String, Int>()
    private lateinit var term: TerminalScreen
    @Volatile private var dirty = true
    private var running = true
    private var toast: Pair<String, Long>? = null
    private var prompt: Prompt? = null
    private var listHeight = 10

    private class Prompt(val label: String, var text: String, val hidden: Boolean, val done: (String?) -> Unit) { var showing = false }

    // ---- threads ----------------------------------------------------------------------------

    /** Runs [work] in the background, then [done] on the main loop. */
    fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val r = runCatching(work)
            posts += { done(r) }
            dirty = true
        }
    }

    /** Runs [fn] on the main loop after [ms]. */
    fun later(ms: Long, fn: () -> Unit) {
        posts += { timers += (System.currentTimeMillis() + ms) to fn }
    }

    fun post(fn: () -> Unit) {
        posts += fn
        dirty = true
    }

    // ---- navigation -------------------------------------------------------------------------

    fun top(): Screen? = stack.lastOrNull()
    fun isTop(s: Screen) = stack.lastOrNull() === s
    fun inStack(s: Screen) = stack.any { it === s }

    private fun remember(s: Screen) { s.key?.let { places[it] = s.pos } }

    fun place(key: String): Int? = places[key]

    fun push(s: Screen) {
        stack.lastOrNull()?.let { remember(it); it.onHide?.invoke() }
        s.key?.let { k -> places[k]?.let { s.pos = it } }
        stack += s
        dirty = true
        s.onShow?.invoke()
    }

    fun back() {
        if (stack.size <= 1) return
        val s = stack.removeAt(stack.lastIndex)
        remember(s)
        s.onHide?.invoke()
        dirty = true
        stack.last().onShow?.invoke()
    }

    fun resetTo(s: Screen) {
        stack.lastOrNull()?.onHide?.invoke()
        stack.clear()
        push(s)
    }

    fun replaceTop(s: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).onHide?.invoke()
        push(s)
    }

    fun refresh() { dirty = true }

    /** Shown on every screen, bottom-right before the clock (e.g. "⬆ build 70"). */
    var badge = ""
        set(v) { field = v; dirty = true }

    /** Run after the screen closes (used to restart into a new build). */
    var afterExit: (() -> Unit)? = null

    fun toast(msg: String, ms: Long = 4_000) {
        toast = msg to System.currentTimeMillis() + ms
        dirty = true
    }

    fun ask(label: String, initial: String = "", hidden: Boolean = false, done: (String?) -> Unit) {
        prompt = Prompt(label, initial, hidden, done)
        dirty = true
    }

    fun quit() { running = false }

    /** Columns available for wrapped text. */
    fun columns(): Int = if (::term.isInitialized) (term.terminalSize.columns - 6).coerceIn(30, 110) else 80

    // ---- main loop --------------------------------------------------------------------------

    fun run(first: Screen) {
        val terminal = DefaultTerminalFactory().setForceTextTerminal(true).createTerminal()
        term = TerminalScreen(terminal)
        term.startScreen()
        term.cursorPosition = null
        try {
            push(first)
            var lastMinute = -1L
            while (running) {
                while (true) {
                    val p = posts.poll() ?: break
                    runCatching { p() }.onFailure { toast("Error: ${it.message ?: it.javaClass.simpleName}") }
                    dirty = true
                }
                val now = System.currentTimeMillis()
                val due = timers.filter { it.first <= now }
                if (due.isNotEmpty()) {
                    timers.removeAll(due)
                    due.forEach { runCatching { it.second() } }
                    dirty = true
                }
                if (term.doResizeIfNecessary() != null) dirty = true
                val key = term.pollInput()
                if (key != null) { handle(key); dirty = true }
                toast?.let { if (it.second < now) { toast = null; dirty = true } }
                // Redraw when the clock changes: each minute, or each second when it shows seconds.
                val step = if (Look.clock().pattern.contains("ss")) 1_000L else 60_000L
                if (now / step != lastMinute) { lastMinute = now / step; dirty = true }
                if (dirty) { dirty = false; draw() }
                if (key == null) Thread.sleep(15)
            }
        } finally {
            term.stopScreen()
        }
        afterExit?.invoke()
    }

    // ---- keys -------------------------------------------------------------------------------

    private fun handle(k: KeyStroke) {
        prompt?.let { p -> promptKey(p, k); return }
        val s = top() ?: return
        val n = s.rows.size
        s.onKey?.let { handler ->
            val name = when (k.keyType) {
                KeyType.ArrowUp -> "up"
                KeyType.ArrowDown -> "down"
                KeyType.ArrowLeft -> "left"
                KeyType.ArrowRight -> "right"
                KeyType.Enter -> "ok"
                KeyType.Character -> when (k.character) {
                    ' ' -> "ok"; 'p', 'P', 'f', 'F' -> "play"
                    'k', 'w' -> "up"; 'j', 's' -> "down"; 'h', 'a' -> "left"; 'l', 'd' -> "right"
                    else -> null
                }
                else -> null
            }
            if (name != null) { handler(name); return }
        }
        when (k.keyType) {
            KeyType.ArrowUp -> move(s, -1)
            KeyType.ArrowDown -> move(s, 1)
            KeyType.PageUp, KeyType.ArrowLeft -> move(s, -listHeight)
            KeyType.PageDown, KeyType.ArrowRight -> move(s, listHeight)
            KeyType.Home -> move(s, -n)
            KeyType.End -> move(s, n)
            KeyType.Enter -> choose(s)
            KeyType.Escape, KeyType.Backspace -> back()
            KeyType.EOF -> quit()
            KeyType.Character -> when (val c = k.character) {
                'k' -> move(s, -1)
                'j' -> move(s, 1)
                'p', 'P' -> s.rows.getOrNull(s.pos)?.let { r -> (r.play ?: r.action)?.invoke() }
                '/' -> if (!KidsGuard.hides()) Search.start()
                'q', 'Q' -> if (stack.size <= 1) quit() else back()
                ' ' -> choose(s)
                else -> if (k.isCtrlDown && (c == 'c' || c == 'C')) quit()
            }
            else -> {}
        }
    }

    private fun move(s: Screen, by: Int) {
        if (s.rows.isEmpty()) return
        s.pos = (s.pos + by).coerceIn(0, s.rows.size - 1)
        s.onSelect?.invoke(s.pos)
    }

    private fun choose(s: Screen) {
        val r = s.rows.getOrNull(s.pos) ?: return
        when {
            r.opens != null -> push(r.opens.invoke())
            else -> r.action?.invoke()
        }
    }

    private fun promptKey(p: Prompt, k: KeyStroke) {
        when (k.keyType) {
            KeyType.Enter -> { prompt = null; p.done(p.text) }
            KeyType.Escape -> { prompt = null; p.done(null) }
            // Tab shows or hides a password or key while you type it.
            KeyType.Tab -> if (p.hidden) p.showing = !p.showing
            KeyType.Backspace -> if (p.text.isNotEmpty()) p.text = p.text.dropLast(1)
            KeyType.Character -> if (k.isCtrlDown && (k.character == 'c' || k.character == 'C')) { prompt = null; p.done(null) } else p.text += k.character
            else -> {}
        }
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun width(s: String) = TerminalTextUtils.getColumnWidth(s)

    /** Cuts [s] to [w] columns, ending in "…" if it had to cut. */
    private fun fit(s: String, w: Int): String {
        if (w <= 0) return ""
        if (width(s) <= w) return s
        val sb = StringBuilder()
        var used = 0
        for (ch in s) {
            val cw = if (TerminalTextUtils.isCharDoubleWidth(ch)) 2 else 1
            if (used + cw > w - 1) break
            sb.append(ch)
            used += cw
        }
        return sb.append('…').toString()
    }

    private fun draw() {
        val t = Look.theme()
        val size = term.terminalSize
        val w = size.columns
        val h = size.rows
        val g = term.newTextGraphics()
        g.backgroundColor = t.bg
        g.foregroundColor = t.text
        g.fill(' ')
        val s = top() ?: run { term.refresh(); return }
        s.moveTo?.let { s.pos = it.coerceIn(0, (s.rows.size - 1).coerceAtLeast(0)); s.moveTo = null }
        if (s.pos >= s.rows.size) s.pos = (s.rows.size - 1).coerceAtLeast(0)

        val left = 2
        val inner = w - 4
        // Title and counter
        val counter = if (s.rows.isEmpty()) "" else "${s.pos + 1}/${s.rows.size}"
        val title = s.title.uppercase() + if (s.tag.isNotEmpty()) "   [${s.tag}]" else ""
        g.foregroundColor = t.title
        g.putString(left, 0, fit(title, inner - width(counter) - 2))
        g.foregroundColor = t.dim
        g.putString(w - 2 - width(counter), 0, counter)
        g.foregroundColor = t.bar
        g.putString(left, 1, "─".repeat(inner.coerceAtLeast(0)))

        // QR code beside the list, when there's room
        val qr = s.qr
        val qrW = qr?.maxOfOrNull { width(it) } ?: 0
        val showQrRight = qr != null && w >= qrW + 50
        val listW = if (showQrRight) inner - qrW - 3 else inner
        var listTop = 2
        var listBottom = h - 3
        if (qr != null && !showQrRight) listBottom = (h - 3 - qr.size).coerceAtLeast(listTop + 3)
        listHeight = (listBottom - listTop + 1).coerceAtLeast(1)

        if (s.pos < s.top) s.top = s.pos
        if (s.pos >= s.top + listHeight) s.top = s.pos - listHeight + 1
        s.top = s.top.coerceIn(0, (s.rows.size - listHeight).coerceAtLeast(0))

        for (i in 0 until listHeight) {
            val idx = s.top + i
            val r = s.rows.getOrNull(idx) ?: break
            val y = listTop + i
            val sel = idx == s.pos
            val detail = when {
                r.detail.isNotEmpty() -> r.detail
                r.opens != null -> "►"
                else -> ""
            }
            if (sel) {
                g.backgroundColor = t.bar
                g.putString(left - 1, y, " ".repeat(listW + 2))
            }
            val dW = width(detail).coerceAtMost(listW / 2)
            val labelW = if (dW > 0) listW - dW - 2 else listW
            g.foregroundColor = if (sel) t.barText else t.text
            g.putString(left, y, fit(r.label, labelW))
            if (dW > 0) {
                g.foregroundColor = if (sel) t.barText else t.dim
                g.putString(left + listW - dW, y, fit(detail, dW))
            }
            g.backgroundColor = t.bg
        }

        if (qr != null) {
            g.backgroundColor = TextColor.RGB(255, 255, 255)
            g.foregroundColor = TextColor.RGB(0, 0, 0)
            val x0 = if (showQrRight) w - 2 - qrW else left
            val y0 = if (showQrRight) 2 else listBottom + 1
            qr.forEachIndexed { i, line -> if (y0 + i < h - 2) g.putString(x0, y0 + i, line) }
            g.backgroundColor = t.bg
        }

        // Footer: keys (or a prompt / message) and the clock
        g.foregroundColor = t.bar
        g.putString(left, h - 2, "─".repeat(inner.coerceAtLeast(0)))
        val time = Look.clock().pattern.let { if (it.isEmpty()) "" else SimpleDateFormat(it, Locale.UK).format(Date()) }
        val clock = listOf(badge, time).filter { it.isNotEmpty() }.joinToString("  ")
        val p = prompt
        val msg = toast?.first
        when {
            p != null -> {
                g.backgroundColor = t.bar
                g.foregroundColor = t.barText
                val shown = if (p.hidden && !p.showing) "*".repeat(p.text.length) else p.text
                val hint = if (p.hidden) (if (p.showing) "  (Tab hides)" else "  (Tab shows)") else ""
                g.putString(left - 1, h - 1, " ".repeat(inner + 2))
                g.putString(left, h - 1, fit("${p.label}$hint: $shown▌", inner))
                g.backgroundColor = t.bg
            }
            msg != null -> {
                g.foregroundColor = t.title
                g.putString(left, h - 1, fit(msg, inner - width(clock) - 2))
            }
            else -> {
                g.foregroundColor = t.dim
                g.putString(left, h - 1, fit("↑↓ move  Enter open  p play  ←→ page  Esc back  / search  q quit", inner - width(clock) - 2))
            }
        }
        if (p == null && clock.isNotEmpty()) {
            g.foregroundColor = t.dim
            g.putString(w - 2 - width(clock), h - 1, clock)
        }
        term.refresh()
    }
}


/** An "are you sure?" screen: the cursor starts on "No", so a stray Enter changes nothing. */
fun confirmScreen(title: String, question: String, yes: String, onYes: () -> Unit) = Screen(
    title,
    // One sentence per line, so nothing is cut off on a TV.
    question.split(Regex("(?<=[.;]) ")).map { Row(it) } + listOf(
        Row("────────────────────"),
        Row("No, keep everything", action = { App.back() }),
        Row(yes, action = onYes),
    ),
).also { it.pos = it.rows.size - 2 }
