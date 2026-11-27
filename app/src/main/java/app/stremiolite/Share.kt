package app.stremiolite

import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * "Share my setup": your add-ons (rows and Index) and settings, as a QR code or a link someone
 * else opens in their own copy of the app, into their own account.
 *
 * Left out on purpose:
 *  - stream add-ons (AIOStreams etc.): their links carry your debrid account. The other person
 *    is shown how to make their own from the same config, with their own key;
 *  - personal list rows (your MDBList/Trakt/Simkl watchlist): hidden on their side.
 */
object Share {
    private const val SCHEME = "stremonino"
    /** Links made before the rename still open. */
    private val SCHEMES = listOf(SCHEME, "stremiolean")
    private const val STREAMS_CONFIGURE = "https://aiostreams.elfhosted.com/stremio/configure"
    private const val STREAMS_CONFIG_FILE = "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/setup/aiostreams-config.json"

    /** A setup link that arrived before anyone was signed in. */
    private var pending: JSONObject? = null

    fun takePending(): JSONObject? = pending.also { pending = null }

    // ---- sending ---------------------------------------------------------------------------

    private fun payload(app: MainActivity, withLive: Boolean): JSONObject {
        val all = Home.allAddons().filter { !it.isLocal }
        val streams = all.filter { "stream" in it.resources }
        val share = all - streams.toSet()
        val personal = Home.rowAddons(all).flatMap { a ->
            a.catalogs.filter { c -> Folders.isPersonal(c) }.map { Settings.rowKey(a, it) }
        }
        return JSONObject()
            .put("v", 1)
            .put("from", Profiles.current(app)?.name ?: "")
            .put("a", JSONArray(share.filter { !it.stickRows }.map { it.transportUrl }))
            .put("r", JSONArray(share.filter { it.stickRows }.map { it.transportUrl }))
            .put("s", Settings.exportShared(app))
            .put("h", JSONArray(personal))
            .put("st", streams.isNotEmpty())
            .also { o -> if (withLive) Settings.live(app).takeIf { !it.isEmpty }?.let { o.put("lt", it.json()) } }
    }

    private fun encode(o: JSONObject): String {
        val d = Deflater(9).apply { setInput(o.toString().toByteArray()); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return "$SCHEME://share?d=" + Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun decode(link: String): JSONObject? = runCatching {
        val d = Uri.parse(link.trim()).getQueryParameter("d") ?: return null
        val bytes = Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val inf = Inflater().apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        JSONObject(String(out.toByteArray())).takeIf { it.optInt("v") == 1 }
    }.getOrNull()

    fun screen(app: MainActivity): Screen {
        if (Home.allAddons().isEmpty()) return Screen("Export my setup", listOf(Row("Your add-ons are still loading. Go back and try again in a moment.")))
        val live = Settings.live(app)
        var withLive = false
        lateinit var s: Screen
        fun build() {
            val p = payload(app, withLive)
            val link = encode(p)
            val streams = p.optBoolean("st")
            val rows = mutableListOf(
                Row("For someone else's Stremio account. On their phone:"),
                Row("install Stremonino, sign in with their account, then"),
                Row("point the phone's camera at this code. It opens"),
                Row("Stremonino and asks before adding anything."),
                Row("Their stick and other devices on that account get it"),
                Row("too: it goes onto the account, not just the phone."),
                Row("────────────────────"),
                Row(info("Add-ons shared", "${p.optJSONArray("a")!!.length() + p.optJSONArray("r")!!.length()}")),
                Row(info("Settings", "theme, clock, subtitles, hidden rows")),
                Row(info("Your watchlist", "hidden on theirs")),
                Row(info("Stream add-on", if (streams) "not shared (yours uses your TorBox)" else "none")),
            )
            if (!live.isEmpty) {
                rows += Row("Include my Live TV", "◄ ${if (withLive) "Yes: " + live.summary() else "No"} ►", action = { withLive = !withLive; build() })
                if (withLive && live.hasLogins()) {
                    rows += Row("   Careful: your playlist links hold your logins.")
                    rows += Row("   Anyone with this code can use those accounts.")
                }
            }
            rows += Row("────────────────────")
            rows += Row("Send it as a message instead", "►", action = {
                val text = "My Stremonino setup. On your phone, tap this link to open it in Stremonino, or copy it and choose " +
                    "'Import a setup from another account' → 'Paste a link':\n$link"
                runCatching {
                    app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share setup"))
                }.onFailure { app.toast("No app to share with on this device") }
            })
            s.image = Qr.make(link, 600)
            if (s.image == null) rows.add(rows.size - 1, Row("Too much for one code: send it as a message instead."))
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Export my setup", emptyList())
        build()
        return s
    }

    // ---- receiving -------------------------------------------------------------------------

    /** Called with the link that opened the app (if any). */
    fun open(app: MainActivity, uri: Uri?) {
        if (uri == null || uri.scheme !in SCHEMES) return
        val p = decode(uri.toString()) ?: run { app.toast("That setup link looks incomplete"); return }
        if (Account.authKey(app) == null) {
            pending = p
            app.toast("Sign in first, then the shared setup will be offered")
            return
        }
        app.push(receive(app, p))
    }

    /** Where to start an import: the camera on a phone, or a pasted link. */
    fun importScreen(app: MainActivity): Screen {
        val rows = mutableListOf(
            Row("The setup goes onto the Stremio account you're signed"),
            Row("in with (${Account.email(app).ifEmpty { "this profile" }}), so every device on that"),
            Row("account gets it. Do it once, on any one of them."),
            Row("────────────────────"),
        )
        if (app.isPhone) {
            rows += Row("Easiest: point this phone's camera at their code")
            rows += Row("(Export my setup on their device). It opens here.")
        } else {
            rows += Row("Easiest: on your phone, sign in to Stremonino with")
            rows += Row("this same account and point the camera at their code.")
            rows += Row("Then choose Settings › Reload my rows and add-ons here.")
        }
        rows += Row("────────────────────")
        rows += Row("Paste a link they sent you", "►", action = { askPaste(app) })
        return Screen("Import a setup", rows).also { it.position = rows.size - 1 }
    }

    fun askPaste(app: MainActivity) {
        val input = android.widget.EditText(app).apply { hint = "$SCHEME://share?d=…"; setSingleLine() }
        PhoneTyping.show(app, android.app.AlertDialog.Builder(app)
            .setTitle("Paste the shared setup")
            .setPositiveButton("Next") { _, _ ->
                val text = input.text.toString()
                val start = SCHEMES.map { text.indexOf("$it://") }.filter { it >= 0 }.minOrNull() ?: -1
                val p = if (start >= 0) decode(text.substring(start).split(Regex("\\s"))[0]) else null
                if (p == null) app.toast("That doesn't look like a setup link") else app.push(receive(app, p))
            }
            .setNegativeButton("Cancel", null), input, "Shared setup link")
    }

    private fun urls(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.startsWith("http") }

    fun receive(app: MainActivity, p: JSONObject): Screen {
        val from = p.optString("from").ifBlank { "Someone" }
        val addons = urls(p.optJSONArray("a"))
        val rows = urls(p.optJSONArray("r"))
        val hide = p.optJSONArray("h")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        fun host(u: String) = runCatching { Uri.parse(u).host ?: u }.getOrDefault(u)
        lateinit var screen: Screen
        val status = Row("")
        val list = mutableListOf(
            Row("$from's setup will be added to your account (${Account.email(app).ifEmpty { "this profile" }})."),
            Row("Add-ons with the same name are replaced; others are kept."),
            Row("────────────────────"),
        )
        addons.forEach { list += Row("  add-on: " + host(it)) }
        rows.forEach { list += Row("  rows for this app: " + host(it)) }
        list += Row("  their settings and hidden rows")
        if (hide.isNotEmpty()) list += Row("  (their personal watchlist stays hidden)")
        val live = LiveTv.bundle(p.optJSONObject("lt"))
        if (live != null) list += Row("  Live TV: " + live.summary() + " (added to yours)")
        list += Row("────────────────────")
        list += status
        list += Row("Add it to my account", action = {
            val key = Account.authKey(app) ?: return@Row
            val hadStreams = Home.allAddons().any { "stream" in it.resources }
            status.label = "Adding… this takes a few seconds"
            app.refresh(screen)
            Net.async({
                val failed = mutableListOf<String>()
                addons.forEach { u -> runCatching { Stremio.installAddon(key, u) }.onFailure { failed += host(u) + ": " + Net.describe(it) } }
                rows.forEach { u -> runCatching { Stremio.installAddon(key, u, stickRows = true) }.onFailure { failed += host(u) + ": " + Net.describe(it) } }
                failed
            }) { r ->
                val failed = r.getOrDefault(emptyList())
                p.optJSONObject("s")?.let { Settings.importShared(app, it, hide) }
                live?.let { Settings.addLive(app, it) }
                app.applyLook()
                Home.refreshAddons(app)
                runCatching { Account.addonCache(app).delete() }
                app.replaceTop(done(app, from, failed, p.optBoolean("st") && !hadStreams))
            }
        })
        list += Row("Cancel", action = { app.back() })
        screen = Screen("Shared setup", list)
        return screen
    }

    private fun done(app: MainActivity, from: String, failed: List<String>, needStreams: Boolean): Screen {
        val rows = mutableListOf(Row(if (failed.isEmpty()) "Done: $from's setup is on your account." else "Mostly done. These didn't add:"))
        failed.forEach { rows += Row("  $it") }
        rows += Row("────────────────────")
        if (needStreams) {
            rows += Row("One more step: your own stream add-on", "►", opens = { streamStep(app) })
        }
        rows += Row("Go to Home", action = { app.resetTo(Home.screen(app)) })
        return Screen("Shared setup", rows)
    }

    /** How to make your own AIOStreams from the same config, with your own debrid key. */
    fun streamStep(app: MainActivity): Screen {
        val s = Screen(
            "Your own stream add-on",
            listOf(
                Row("Streams need your own debrid account (e.g. TorBox)."),
                Row("1. Download the stream config: scan the code,"),
                Row("   or open the link below on your phone."),
                Row("2. Open the AIOStreams page (second link), import"),
                Row("   the file, add YOUR debrid key, and save."),
                Row("3. Copy its install link and add it here:"),
                Row("────────────────────"),
                Row("Open the config file", action = { open(app, STREAMS_CONFIG_FILE) }),
                Row("Open the AIOStreams page", action = { open(app, STREAMS_CONFIGURE) }),
                Row("Install my stream add-on by link", "►", action = { Home.askInstall(app, false) }),
                Row("────────────────────"),
                Row("Easier: set up my streams for me", "with the fail-safe  ►", opens = { StreamsSetup.screen(app) }),
            ),
        )
        s.image = Qr.make(STREAMS_CONFIG_FILE)
        return s
    }

    private fun open(app: MainActivity, url: String) {
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { app.toast("No browser on this device: scan the code with your phone instead") }
    }
}
