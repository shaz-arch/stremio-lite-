package app.stremiolite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Sign-in with a link code, the way Stremio's own TV apps do it: the TV shows a code,
 * you approve it on your phone, and the app picks up the sign-in key. No password typing.
 */
object SignIn {
    private const val POLL_MS = 3_000L
    private const val CODE_LIFETIME_MS = 10 * 60_000L

    fun screen(app: MainActivity): Screen {
        val linkRow = Row("   getting a code…")
        val codeRow = Row("   ")
        val status = Row("")
        lateinit var screen: Screen

        var code: String? = null
        var startedAt = 0L
        var active = false

        fun show(text: String) {
            status.label = text
            app.refresh(screen)
        }

        val poll = object : Runnable {
            override fun run() {
                val current = code ?: return
                if (!active) return
                if (System.currentTimeMillis() - startedAt > CODE_LIFETIME_MS) {
                    show("This code has expired. Choose 'Get a new code'.")
                    return
                }
                val self = this
                Net.async({ Stremio.linkRead(current) }) { r ->
                    if (!active || current != code) return@async
                    val key = r.getOrNull()
                    if (key != null) {
                        active = false
                        app.toast("Signed in")
                        app.signedIn(key)
                    } else {
                        Net.later(POLL_MS, self)
                    }
                }
            }
        }

        fun newCode() {
            Net.cancel(poll)
            code = null
            screen.image = null
            linkRow.label = "   getting a code…"
            codeRow.label = "   "
            show("")
            Net.async({ Stremio.linkCreate() }) { r ->
                r.onSuccess { lc ->
                    code = lc.code
                    startedAt = System.currentTimeMillis()
                    linkRow.label = "   " + lc.link.ifEmpty { "www.stremio.com" }
                        .removePrefix("https://").removePrefix("http://")
                    codeRow.label = "   " + lc.code
                    show("Waiting for you to approve it on your phone…")
                    loadQr(lc.qrcode) { bmp ->
                        if (code == lc.code) {
                            screen.image = bmp
                            app.refresh(screen)
                        }
                    }
                    if (active) Net.later(POLL_MS, poll)
                }.onFailure { e ->
                    show("Couldn't get a code (${Net.describe(e)}). Choose 'Get a new code'.")
                }
            }
        }

        screen = Screen(
            title = "Sign in to Stremio",
            rows = listOf(
                Row("1. On your phone, scan the code or open:"),
                linkRow,
                Row("2. Sign in to Stremio and approve this code:"),
                codeRow,
                Row("────────────────────"),
                status,
                Row("────────────────────"),
                Row("Get a new code", action = { newCode() }),
                Row("Sign in with email and password instead", action = { app.askEmailAndPassword() }),
            ),
        )
        screen.position = 7
        screen.onShow = {
            active = true
            if (code == null) newCode() else Net.later(POLL_MS, poll)
        }
        screen.onHide = {
            active = false
            Net.cancel(poll)
        }
        return screen
    }

    /** The QR code arrives as a data: link, a web link or plain base64; any failure just means no picture. */
    private fun loadQr(qr: String, done: (Bitmap) -> Unit) {
        if (qr.isBlank()) return
        Net.async({
            val bytes = when {
                qr.startsWith("data:") -> Base64.decode(qr.substringAfter(','), Base64.DEFAULT)
                qr.startsWith("http") -> Net.getBytes(qr)
                else -> Base64.decode(qr, Base64.DEFAULT)
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not an image")
            // Scale up without smoothing so the squares stay sharp for the phone camera.
            val size = 480
            if (raw.width >= size) raw else Bitmap.createScaledBitmap(raw, size, size * raw.height / raw.width, false)
        }) { r -> r.onSuccess(done) }
    }
}
