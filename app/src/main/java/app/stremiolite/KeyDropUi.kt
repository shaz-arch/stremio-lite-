package app.stremiolite

import android.os.Build

/** Settings › Your keys: send them to another device on your Wi-Fi, or receive them from one. */
object KeyDropUi {
    private var receiver: KeyDrop.Receiver? = null

    fun rows(app: MainActivity): List<Row> = listOf(
        Row("Send my keys to another device", "same Wi-Fi  ►", action = { send(app) }),
        Row("Receive keys from another device", "►", opens = { receive(app) }),
    )

    /** Waits for 5 minutes for keys from the phone (or computer). */
    private fun receive(app: MainActivity): Screen {
        val status = Row("Starting…")
        val s = Screen("Receive keys", listOf(
            status,
            Row("On your phone (signed in to this account): Settings › Your keys › Send my keys to another device."),
        ))
        val key = Account.authKey(app)
        val ip = LocalFiles.lanAddress()
        if (key == null) { status.label = "Sign in first."; return s }
        if (ip == null) { status.label = "Not on Wi-Fi: connect to the same Wi-Fi as your phone."; return s }
        receiver?.stop()
        val r = KeyDrop.Receiver { o ->
            Net.later(0, Runnable {
                val k = Settings.Keys(o.optString("tmdb"), o.optString("region").ifEmpty { "GB" }, StreamSetup.parseKeys(o.optString("debrid")), o.optString("mdblist"), o.optString("gemini"))
                Settings.putKeys(Profiles.prefsFor(app, "settings"), k, app, send = true)
                History.putRemote(app, KeyDrop.record(History.rawRemote(app, KeyDrop.ID), null))
                status.label = if (k.isEmpty) "Received, but there were no keys in it." else "✓ Keys received: " + k.names.joinToString(", ")
                app.toast(status.label); Home.redraw()
                if (app.isTop(s)) app.refresh(s)
            })
        }.also { receiver = it }
        val port = r.start()
        val offer = KeyDrop.Offer("http://$ip:$port/k", r.token, Build.MODEL ?: "TV", System.currentTimeMillis())
        History.putRemote(app, KeyDrop.record(History.rawRemote(app, KeyDrop.ID), offer))
        status.label = "Waiting for 5 minutes for your keys…"
        return s
    }

    /** Finds a device waiting for keys on this account and sends them over the Wi-Fi. */
    private fun send(app: MainActivity) {
        val key = Account.authKey(app) ?: run { app.toast("Sign in first"); return }
        val k = Settings.keysOf(Profiles.prefsFor(app, "settings"))
        if (k.isEmpty) { app.toast("No keys here to send"); return }
        app.toast("Looking for the other device…")
        Net.async({
            val raw = Sync.pullIds(key, listOf(KeyDrop.ID)).optJSONObject(0)?.toString()
            val offer = KeyDrop.parse(raw)?.takeIf { it.fresh } ?: throw IllegalStateException("none")
            KeyDrop.send(offer, KeyDrop.pack(k.tmdb, k.region, k.debrid, k.mdblist, k.gemini))
            offer.device
        }) { r ->
            r.onSuccess { d -> app.toast("✓ Sent ${k.names.joinToString(", ")} to $d") }
                .onFailure { e -> app.toast(if (e is IllegalStateException) "Nothing's waiting: on the other device choose Receive keys from another device first" else "Couldn't reach it: are both on the same Wi-Fi? (${Net.describe(e)})") }
        }
    }
}
