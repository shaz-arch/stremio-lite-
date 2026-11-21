package app.stremiolite

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
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
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        counterView = findViewById(R.id.counter)
        list = findViewById(R.id.list)
        adapter = RowAdapter(layoutInflater)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ -> choose(position) }
        list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCounter(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        push(DemoMenus.home(this))
    }

    // ---- navigation -------------------------------------------------------

    fun push(screen: Screen) {
        stack.add(screen)
        render()
    }

    private fun pop() {
        stack.removeAt(stack.lastIndex)
        render()
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
        list.requestFocus()
        list.setSelection(screen.position)
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
    }

    // ---- remote keys --------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Act on key-up only, so one press never goes back two screens.
                if (event.action == KeyEvent.ACTION_UP) {
                    if (stack.size > 1) pop() else finish()
                }
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
                if (event.action == KeyEvent.ACTION_UP) toast("Search arrives in step 6")
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
