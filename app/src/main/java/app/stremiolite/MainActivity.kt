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
        adapter = RowAdapter(layoutInflater)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ -> choose(position) }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCounter(position)
                stack.lastOrNull()?.onSelect?.invoke(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        Net.init(this)
        push(if (Account.authKey(this) != null) Home.screen(this) else SignIn.screen(this))
    }

    /** The player has closed: file how far it got (VLC: extra_position/extra_duration; MX-style: position/duration). */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != Player.REQUEST_PLAY) return
        val watch = History.takePending(this) ?: return
        val extras = data?.extras
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k -> (extras?.get(k) as? Number)?.toLong() }
        val position = num("extra_position", "position")?.takeIf { it >= 0 }
        val duration = num("extra_duration", "duration")?.takeIf { it > 0 }
        val completed = extras?.getString("end_by") == "playback_completion"
        History.record(this, watch, position, duration, completed)
        refreshTop()
        stack.lastOrNull()?.onShow?.invoke()
    }

    override fun onDestroy() {
        stack.lastOrNull()?.onHide?.invoke()
        super.onDestroy()
    }

    // ---- navigation -------------------------------------------------------

    fun push(screen: Screen) {
        stack.lastOrNull()?.onHide?.invoke()
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
    fun refresh(screen: Screen) {
        if (stack.lastOrNull() !== screen || isFinishing) return
        val pos = list.selectedItemPosition
        if (pos >= 0) screen.position = pos
        render()
    }

    /** How many characters of list text fit across the screen, for wrapping descriptions. */
    fun columns(): Int {
        val paint = android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 20f * resources.displayMetrics.scaledDensity
        }
        val rowPadding = 24f * resources.displayMetrics.density
        val width = (if (list.width > 0) list.width else resources.displayMetrics.widthPixels) - rowPadding
        return ((width / paint.measureText("M")).toInt() - 1).coerceIn(30, 120)
    }

    fun refreshTop() {
        stack.lastOrNull()?.let { refresh(it) }
    }

    fun signedIn(authKey: String, email: String = "") {
        Account.save(this, authKey, email)
        Home.forget()
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
            row.action != null -> row.action.invoke()
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
        val image = screen.image
        if (image != null) {
            sideImage.setImageBitmap(image)
            sideImage.visibility = View.VISIBLE
        } else {
            sideImage.setImageDrawable(null)
            sideImage.visibility = View.GONE
        }
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

    private class RowAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
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
            holder.label.text = row.label
            holder.detail.text = when {
                row.detail.isNotEmpty() -> row.detail
                row.opens != null -> "►"
                else -> ""
            }
            return view
        }

        private class Holder(val label: TextView, val detail: TextView)
    }
}
