package app.stremiolite

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Adds "Type on my phone" to a typing box: the box gains a QR code; scan it and type on the phone,
 * the text fills the box as you type, and Go on the phone presses the box's main button.
 * The little web page ([PhoneKeyboard]) closes with the box.
 */
object PhoneTyping {
    /**
     * Builds [b] with a "Type on my phone" button and shows it, with [input] in [box] as its view
     * (the QR is added below). Go on the phone runs [submit]: by default the box's main button.
     */
    fun show(
        app: MainActivity, b: AlertDialog.Builder, input: EditText, title: String,
        box: LinearLayout = box(app, input),
        submit: (AlertDialog) -> Unit = { it.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick() },
    ): AlertDialog {
        var kb: PhoneKeyboard? = null
        b.setView(box)
        b.setNeutralButton("Type on my phone", null)
        val dialog = b.create()
        dialog.setOnDismissListener { kb?.stop(); kb = null }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener { button ->
            if (kb != null) return@setOnClickListener
            val k = PhoneKeyboard(title, input.text.toString(),
                onText = { t -> app.runOnUiThread { if (dialog.isShowing) { input.setText(t); input.setSelection(input.text.length) } } },
                onGo = { t -> app.runOnUiThread { if (dialog.isShowing) { input.setText(t); submit(dialog) } } },
            )
            val link = k.start()
            if (link == null) { app.toast("This device isn't on a Wi-Fi or home network"); return@setOnClickListener }
            kb = k
            val px = (app.resources.displayMetrics.density * 170).toInt()
            Qr.make(link, px)?.let { bmp ->
                box.addView(ImageView(app).apply { setImageBitmap(bmp); setPadding(0, 16, 0, 8) },
                    LinearLayout.LayoutParams(px, px).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
            }
            box.addView(TextView(app).apply {
                text = "Scan with your phone's camera (same Wi-Fi) and type there.\nOr open: $link"
                textSize = 13f
            })
            button.isEnabled = false
            input.requestFocus()
        }
        return dialog
    }

    /** A box holding [views], padded like the app's other typing boxes. */
    fun box(app: MainActivity, vararg views: android.view.View) = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
        views.forEach { addView(it) }
    }
}
