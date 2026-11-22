package app.stremiolite

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * The whole app is this one activity: a stack of text screens.
 * OK opens a line (pushes a screen), Back pops one, Back on the first screen exits.
 */
class MainActivity : Activity() {

    private val stack = mutableListOf<Screen>()

    private lateinit var titleView: TextView
    private lateinit var counterView: TextView
    private lateinit var list: ListView
    private lateinit var sideImage: ImageView
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        counterView = findViewById(R.id.counter)
        list = findViewById(R.id.list)
        sideImage = findViewById(R.id.side_image)
        adapter = RowAdapter(layoutInflater, this)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ -> choose(position) }
        // Phones and tablets have no Left/Right keys for paging: give long lists a drag handle instead.
        if (packageManager.hasSystemFeature("android.hardware.touchscreen")) {
            list.isFastScrollEnabled = true
            list.isFastScrollAlwaysVisible = false
            // Finger scrolling doesn't move the cursor, so tell the list what's on screen:
            // reaching the bottom loads the next page just as moving the cursor there does.
            list.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {}
                override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                    if (total > 0 && visible > 0) stack.lastOrNull()?.onSelect?.invoke(first + visible - 1)
                }
            })
        }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCounter(position)
                stack.lastOrNull()?.onSelect?.invoke(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        applyLook()
        Net.init(this)
        push(
            when {
                Profiles.all(this).size > 1 -> Profiles.chooser(this)   // "Who's watching?"
                Account.authKey(this) != null -> Home.screen(this)
                else -> SignIn.screen(this)
            }
        )
    }

    /** The player has closed: file how far it got (VLC: extra_position/extra_duration; MX-style: position/duration). */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Player.REQUEST_PLAY) return
        val (watch, launched) = History.takePending(this)
        val extras = data?.extras
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k -> (extras?.get(k) as? Number)?.toLong() }
        val position = num("extra_position", "position")?.takeIf { it >= 0 }
        val duration = num("extra_duration", "duration")?.takeIf { it > 0 }
        val completed = extras?.getString("end_by") == "playback_completion"

        // Back within 15 seconds without getting past 5: the stream didn't play.
        // A "video" under 2 minutes is an error clip (e.g. ElfHosted's "couldn't play this" screen), not the title.
        val elapsed = System.currentTimeMillis() - launched
        val errorClip = duration != null && duration < 120_000
        val failed = errorClip || (!completed && launched > 0 && elapsed < 15_000 && (position ?: 0) < 5_000)
        val retry = onPlayerFailed
        onPlayerFailed = null
        if (failed) {
            // Don't overwrite the saved position; let Play best try its next choice.
            if (retry?.invoke() == true) return
        } else if (watch != null) {
            History.record(this, watch, position, duration, completed)
            val finished = completed || History.isFinished(position ?: 0, duration ?: 0)
            if (finished && watch.nextVideoId != null && Settings.autoNext(this)) {
                push(Binge.upNext(this, watch))
                return
            }
        }
        refreshTop()
        stack.lastOrNull()?.onShow?.invoke()
    }

    /** Set by Play best before starting the player; returns true if it is trying another stream. */
    var onPlayerFailed: (() -> Boolean)? = null

    override fun onPause() {
        super.onPause()
        History.flush()
        clockTick?.let { clockHandler.removeCallbacks(it) }
    }

    override fun onResume() {
        super.onResume()
        updateClock()
    }

    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var clockTick: Runnable? = null

    /** Shows the time, then wakes again just after the next minute starts. Costs one redraw a minute. */
    fun updateClock() {
        clockTick?.let { clockHandler.removeCallbacks(it) }
        val view = findViewById<TextView>(R.id.clock)
        val pattern = Look.clock(this).pattern
        if (pattern.isEmpty()) { view.visibility = View.GONE; return }
        view.visibility = View.VISIBLE
        view.text = java.text.SimpleDateFormat(pattern, java.util.Locale.UK).format(java.util.Date())
        val tick = Runnable { updateClock() }
        clockTick = tick
        clockHandler.postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L + 200L)
    }

    override fun onDestroy() {
        stack.lastOrNull()?.onHide?.invoke()
        super.onDestroy()
    }

    // ---- navigation -------------------------------------------------------

    /** Cursor line per remembered list (Screen.key), for this run of the app. */
    private val places = HashMap<String, Int>()

    fun savedPlace(key: String): Int? = places[key]

    private fun rememberPlace(s: Screen) {
        val k = s.key ?: return
        if (stack.lastOrNull() === s) list.selectedItemPosition.takeIf { it >= 0 }?.let { s.position = it }
        places[k] = s.position
    }

    fun push(screen: Screen) {
        stack.lastOrNull()?.let { rememberPlace(it); it.onHide?.invoke() }
        screen.key?.let { k -> places[k]?.let { screen.position = it } }
        stack.add(screen)
        render()
        screen.onShow?.invoke()
    }

    /** Go up one screen (or exit from the first screen). */
    fun back() {
        if (stack.size <= 1) {
            finish()
            return
        }
        stack.last().let { rememberPlace(it) }
        stack.removeAt(stack.lastIndex).onHide?.invoke()
        render()
        stack.last().onShow?.invoke()
    }

    /** Throw away the whole history and start again from [screen] (after sign-in or sign-out). */
    fun resetTo(screen: Screen) {
        stack.lastOrNull()?.onHide?.invoke()
        stack.clear()
        push(screen)
    }

    /** Redraw [screen] if it's the one showing, keeping the cursor where it is. */
    /**
     * Redraws [screen] if it's showing: just the text, so the cursor stays put and nothing re-lays out
     * unless the screen asked to move the cursor ([Screen.moveTo]).
     */
    fun refresh(screen: Screen) {
        if (stack.lastOrNull() !== screen || isFinishing) return
        adapter.rows = screen.rows
        adapter.notifyDataSetChanged()
        showImage(screen)
        val move = screen.moveTo
        screen.moveTo = null
        if (move != null) {
            val p = move.coerceIn(0, (screen.rows.size - 1).coerceAtLeast(0))
            list.setSelection(p)
            screen.position = p
        } else {
            list.selectedItemPosition.takeIf { it >= 0 }?.let { screen.position = it }
        }
        updateCounter(screen.position)
    }

    /** Where the cursor is on the current screen. */
    fun cursor(): Int = list.selectedItemPosition

    private fun showImage(screen: Screen) {
        val image = screen.image
        if (image != null) {
            if (sideImage.visibility != View.VISIBLE || sideImage.tag !== image) {
                sideImage.setImageBitmap(image)
                sideImage.tag = image
            }
            sideImage.visibility = View.VISIBLE
        } else if (sideImage.visibility != View.GONE) {
            sideImage.setImageDrawable(null)
            sideImage.tag = null
            sideImage.visibility = View.GONE
        }
    }

    /** How many characters of list text fit across the screen, for wrapping descriptions. */
    fun columns(): Int {
        val paint = android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = Look.size(this@MainActivity).sp * resources.displayMetrics.scaledDensity
        }
        val rowPadding = 24f * resources.displayMetrics.density
        val width = (if (list.width > 0) list.width else resources.displayMetrics.widthPixels) - rowPadding
        return ((width / paint.measureText("M")).toInt() - 1).coerceIn(30, 120)
    }

    /** Swaps the top screen for another (the up-next countdown handing over to playback). */
    fun replaceTop(screen: Screen) {
        stack.removeAt(stack.lastIndex).onHide?.invoke()
        stack.add(screen)
        render()
        screen.onShow?.invoke()
    }

    fun isTop(screen: Screen) = stack.lastOrNull() === screen

    /** Applies the chosen theme and text size to the whole screen. */
    fun applyLook() {
        val t = Look.theme(this)
        val sp = Look.size(this).sp
        window.decorView.setBackgroundColor(t.background)
        findViewById<View>(R.id.root).setBackgroundColor(t.background)
        findViewById<View>(R.id.rule_top).setBackgroundColor(t.bar)
        findViewById<View>(R.id.rule_bottom).setBackgroundColor(t.bar)
        titleView.setTextColor(t.title)
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp + 2)
        counterView.setTextColor(t.dim)
        counterView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
        for (id in intArrayOf(R.id.footer, R.id.clock)) findViewById<TextView>(id).apply {
            setTextColor(t.dim)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, (sp * 0.8f).coerceAtLeast(14f))
        }
        updateClock()
        list.selector = android.graphics.drawable.ColorDrawable(t.bar)
        adapter.notifyDataSetChanged()
    }

    fun refreshTop() {
        stack.lastOrNull()?.let { refresh(it) }
    }

    /** A new sign-in becomes a profile (or refreshes the existing one for that account) and opens its Home. */
    fun signedIn(authKey: String, email: String = "", name: String = "") {
        History.flush()
        Profiles.add(this, name, authKey, email)
        History.reset()
        Home.forget()
        applyLook()
        resetTo(Home.screen(this))
    }

    /** Fallback sign-in with the TV's on-screen keyboard. */
    fun askEmailAndPassword() {
        val email = EditText(this).apply {
            hint = "Email"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(email)
            addView(password)
        }
        AlertDialog.Builder(this)
            .setTitle("Sign in to Stremio")
            .setView(box)
            .setPositiveButton("Sign in") { _, _ ->
                val e = email.text.toString().trim()
                val p = password.text.toString()
                if (e.isEmpty() || p.isEmpty()) return@setPositiveButton
                toast("Signing in…")
                Net.async({ Stremio.login(e, p) }) { r ->
                    r.onSuccess { key -> signedIn(key, e) }
                        .onFailure { err ->
                            toast(if (err is Stremio.ApiError) "Sign-in failed: ${err.message}" else "Sign-in failed: ${Net.describe(err)}")
                        }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choose(position: Int) {
        val screen = stack.last()
        val row = screen.rows.getOrNull(position) ?: return
        screen.position = position
        when {
            row.opens != null -> push(row.opens.invoke())
            row.action != null -> row.action?.invoke()
        }
    }

    private fun render() {
        val screen = stack.last()
        titleView.text = buildString {
            append(screen.title.uppercase())
            if (screen.tag.isNotEmpty()) append("   [").append(screen.tag).append(']')
        }
        adapter.rows = screen.rows
        adapter.notifyDataSetChanged()
        screen.moveTo = null
        showImage(screen)
        list.requestFocus()
        list.setSelection(screen.position.coerceIn(0, (screen.rows.size - 1).coerceAtLeast(0)))
        updateCounter(screen.position)
    }

    private fun updateCounter(position: Int) {
        val count = adapter.count
        counterView.text = if (count == 0) "" else "${position + 1}/$count"
    }

    /** Left / Right jump a page at a time in long lists. */
    private fun jumpPage(direction: Int) {
        val count = adapter.count
        if (count == 0) return
        val page = (list.lastVisiblePosition - list.firstVisiblePosition).coerceAtLeast(1)
        val from = list.selectedItemPosition.coerceAtLeast(0)
        val target = (from + direction * page).coerceIn(0, count - 1)
        list.setSelection(target)
        updateCounter(target)
        stack.lastOrNull()?.onSelect?.invoke(target)
    }

    // ---- remote keys --------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Act on key-up only, so one press never goes back two screens.
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (event.action == KeyEvent.ACTION_DOWN) jumpPage(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (event.action == KeyEvent.ACTION_DOWN) jumpPage(1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    val row = stack.lastOrNull()?.rows?.getOrNull(list.selectedItemPosition)
                    (row?.play ?: row?.action)?.invoke()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> {
                if (event.action == KeyEvent.ACTION_UP && Account.authKey(this) != null) Search.start(this)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ---- list adapter -------------------------------------------------------

    private class RowAdapter(private val inflater: LayoutInflater, private val context: android.content.Context) : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.row, parent, false).also {
                it.tag = Holder(it.findViewById(R.id.label), it.findViewById(R.id.detail))
            }
            val holder = view.tag as Holder
            val row = rows[position]
            val t = Look.theme(context)
            val sp = Look.size(context).sp
            if (holder.sp != sp || holder.theme !== t) {
                holder.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
                holder.detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
                holder.label.setTextColor(t.label)
                holder.detail.setTextColor(t.detail)
                holder.sp = sp
                holder.theme = t
            }
            holder.label.text = row.label
            holder.detail.text = when {
                row.detail.isNotEmpty() -> row.detail
                row.opens != null -> "►"
                else -> ""
            }
            return view
        }

        private class Holder(val label: TextView, val detail: TextView) {
            var sp = 0f
            var theme: Look.Theme? = null
        }
    }
}
