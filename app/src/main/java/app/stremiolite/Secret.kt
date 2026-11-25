package app.stremiolite

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.EditText

/** Passwords and keys: hidden as you type, with a "Show" tick box to check them. */
object Secret {
    fun hide(input: EditText) = show(input, false)

    private fun show(input: EditText, on: Boolean) {
        val at = input.selectionEnd.coerceAtLeast(0)
        // The input type matters too: the TV's full-screen keyboard shows the text unless it's a password.
        input.inputType = InputType.TYPE_CLASS_TEXT or
            if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.transformationMethod = if (on) null else PasswordTransformationMethod.getInstance()
        input.setSelection(at.coerceAtMost(input.text.length))
    }

    /** A "Show" tick box for [input] (which it hides to start with). Add it under the box. */
    fun toggle(app: MainActivity, input: EditText): CheckBox {
        hide(input)
        return CheckBox(app).apply {
            text = "Show"
            setOnCheckedChangeListener { _, on -> show(input, on) }
        }
    }
}
