package app.stremiolite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Web links (IMDb, trailers…): a phone opens them; the stick shows a QR code to scan. */
object LinkOut {
    fun row(app: MainActivity, label: String, title: String, url: String): Row =
        if (app.isPhone) Row(label, "opens browser  ►", action = { open(app, url) })
        else Row(label, "QR  ►", opens = { qr(title, url) })

    fun open(app: MainActivity, url: String) {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { copy(app, url, "Link") }
    }

    fun copy(app: MainActivity, text: String, what: String, note: String = "") {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) { app.toast("Couldn't reach the clipboard"); return }
        cm.setPrimaryClip(ClipData.newPlainText(what, text))
        app.toast("$what copied.$note")
    }

    fun qr(title: String, url: String): Screen {
        val s = Screen(title, listOf(Row("Scan the code with your phone's camera."), Row(""), Row(url.removePrefix("https://").take(60))))
        s.image = Qr.make(url)
        return s
    }
}
