package app.stremiolite.linux

import app.stremiolite.Addon
import app.stremiolite.Folders
import app.stremiolite.Http
import app.stremiolite.MetaParse
import app.stremiolite.Stremio
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

// ============================================================================================
// Clipboard (kitty and most desktops: wl-copy on Wayland, xclip or xsel on X11)
// ============================================================================================

object Clipboard {
    fun copy(text: String): Boolean {
        val tools = listOf(listOf("wl-copy"), listOf("xclip", "-selection", "clipboard"), listOf("xsel", "--clipboard", "--input"))
        for (cmd in tools) {
            val ok = runCatching {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                p.outputStream.use { it.write(text.toByteArray()) }
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** Copies [text] and says so; if no clipboard tool exists, shows it to copy by hand. */
    fun copyOrShow(text: String, what: String) {
        if (copy(text)) App.toast("$what copied: paste it with ctrl+shift+v (or ctrl+v)")
        else App.push(Screen(what, listOf(Row("No clipboard tool found (wl-copy or xclip). Select and copy this:"), Row("")) +
            text.chunked(App.columns()).map { Row(it) }))
    }
}

// ============================================================================================
// Profiles: each one a Stremio account signed in on this computer, with its own folder
// ============================================================================================

object Profiles {
    class Profile(val id: String, val name: String, val email: String)

    private fun file() = File(Store.root, "profiles.json")
    private fun read(): JSONObject = runCatching { JSONObject(file().readText()) }.getOrDefault(JSONObject())
    private fun write(o: JSONObject) { file().writeText(o.toString(2)) }

    fun dirFor(id: String) = if (id == "main") Store.root else File(Store.root, "profiles/$id")

    fun all(): List<Profile> {
        val a = read().optJSONArray("list") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Profile(it.optString("id"), it.optString("name"), it.optString("email")) } }
    }

    private fun save(list: List<Profile>, current: String?) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email)) }
        write(JSONObject().put("list", a).put("current", current ?: JSONObject.NULL))
    }

    fun current(): Profile? {
        val list = all()
        val id = read().optString("current")
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /** At start-up: the account from before profiles becomes the first profile; then use the current one's folder. */
    fun start() {
        if (!file().exists()) {
            val acc = runCatching { JSONObject(File(Store.root, "account.json").readText()) }.getOrNull()
            val email = acc?.optString("email").orEmpty()
            if (acc?.optString("authKey").isNullOrEmpty()) save(emptyList(), null)
            else save(listOf(Profile("main", email.substringBefore('@').ifBlank { "Profile 1" }, email)), "main")
        }
        Store.use(dirFor(current()?.id ?: "main"))
    }

    /** Set by "Add a profile": the next sign-in makes a new profile instead of replacing this one. */
    var adding = false

    /** Called by sign-in before the account is saved: picks (or makes) the profile it belongs to. */
    fun beforeSave(email: String) {
        val list = all().toMutableList()
        val same = list.firstOrNull { email.isNotEmpty() && it.email.equals(email, true) }
        val target = when {
            same != null -> same
            adding || list.isEmpty() -> {
                val id = if (list.none { it.id == "main" }) "main" else "p" + System.currentTimeMillis().toString(36)
                // A new profile starts with a copy of the current one's settings (player, theme…).
                val from = File(Store.dir, "settings.json")
                val p = Profile(id, email.substringBefore('@').ifBlank { "Profile ${list.size + 1}" }, email)
                dirFor(id).mkdirs()
                if (id != "main" && from.isFile) runCatching { from.copyTo(File(dirFor(id), "settings.json"), overwrite = true) }
                list += p
                p
            }
            else -> current()!!.let { c -> Profile(c.id, c.name, email.ifEmpty { c.email }).also { n -> list.replaceAll { if (it.id == c.id) n else it } } }
        }
        adding = false
        save(list, target.id)
        Store.use(dirFor(target.id))
        forgetAll()
    }

    private fun forgetAll() {
        Account.reload(); Prefs.reload(); Lib.unload(); Home.forget(); NewEpisodes.forget()
    }

    fun switchTo(id: String) {
        save(all(), id)
        Store.use(dirFor(id))
        forgetAll()
        App.resetTo(if (Account.authKey != null) Home.screen() else SignIn.screen())
    }

    fun rename(id: String, name: String) = save(all().map { if (it.id == id) Profile(it.id, name, it.email) else it }, current()?.id)

    /** Signs [id] out of this computer: its sign-in and account caches go; the Stremio account stays. */
    fun remove(id: String) {
        val wasCurrent = current()?.id == id
        val d = dirFor(id)
        listOf("account.json", "addons.json", "library.json").forEach { File(d, it).delete() }
        if (id != "main") d.deleteRecursively()
        val rest = all().filter { it.id != id }
        save(rest, if (wasCurrent) rest.firstOrNull()?.id else current()?.id)
        if (wasCurrent) {
            Store.use(dirFor(rest.firstOrNull()?.id ?: "main"))
            forgetAll()
        }
    }

    private fun keyOf(p: Profile): String? =
        runCatching { JSONObject(File(dirFor(p.id), "account.json").readText()).optString("authKey") }.getOrNull()?.ifEmpty { null }

    // ---- screens ------------------------------------------------------------------------------

    fun chooser(): Screen {
        lateinit var s: Screen
        fun build() {
            val cur = current()
            s.rows = all().map { p -> Row(p.name, (if (p.id == cur?.id) "current  " else "") + p.email, action = { switchTo(p.id) }) } +
                listOf(
                    Row("────────────────────"),
                    Row("Add a profile (another Stremio account)", "►", opens = { adding = true; SignIn.screen() }),
                    Row("Manage profiles", "►", opens = { manage() }),
                )
            App.refresh()
        }
        s = Screen("Who's watching?", emptyList())
        build()
        s.pos = all().indexOfFirst { it.id == current()?.id }.coerceAtLeast(0)
        s.onShow = { adding = false; build() }
        return s
    }

    private fun manage(): Screen {
        lateinit var s: Screen
        fun build() {
            s.rows = all().map { p -> Row(p.name, p.email + "  ►", opens = { profile(p) }) }.ifEmpty { listOf(Row("No profiles yet.")) }
            App.refresh()
        }
        s = Screen("Manage profiles", emptyList())
        build()
        s.onShow = { build() }
        return s
    }

    private fun profile(p: Profile): Screen {
        val others = all().filter { it.id != p.id }
        val rows = mutableListOf(
            Row("Switch to ${p.name}", action = { switchTo(p.id) }),
            Row("Rename", action = {
                App.ask("New name", p.name) { v -> if (!v.isNullOrBlank()) { rename(p.id, v.trim()); App.toast("Renamed"); App.back() } }
            }),
        )
        if (others.isNotEmpty()) rows += Row("Copy add-ons from another profile", "►", opens = {
            Screen("Copy add-ons into ${p.name}", others.map { o ->
                Row("From ${o.name}", "replaces ${p.name}'s add-ons", action = {
                    val from = keyOf(o); val to = keyOf(p)
                    if (from == null || to == null) { App.toast("One of the two profiles isn't signed in"); return@Row }
                    App.toast("Copying add-ons…")
                    App.async({ copyAddons(from, to) }) { r ->
                        r.onSuccess {
                            App.toast("Copied ${o.name}'s add-ons into ${p.name}")
                            if (current()?.id == p.id) { File(Store.dir, "addons.json").delete(); Home.forget() }
                        }.onFailure { e -> App.toast("Couldn't copy: " + if (e is Stremio.ApiError) e.message else Net.describe(e)) }
                    }
                })
            })
        })
        rows += Row("────────────────────")
        rows += Row("Remove ${p.name} from this computer", action = {
            val wasCurrent = current()?.id == p.id
            remove(p.id)
            App.toast("${p.name} removed from this computer (the Stremio account still exists)")
            if (wasCurrent) App.resetTo(if (Account.authKey != null) Home.screen() else SignIn.screen()) else App.back()
        })
        return Screen(p.name, rows)
    }

    /** One account's add-ons onto another; very large setups are copied in slim form if Stremio refuses the size. */
    private fun copyAddons(fromKey: String, toKey: String) {
        val addons = JSONObject(Stremio.addonsJson(fromKey)).optJSONArray("addons") ?: JSONArray()
        try {
            Stremio.setAddons(toKey, addons)
        } catch (e: Stremio.ApiError) {
            if (e.message?.contains("size", true) != true) throw e
            for (i in 0 until addons.length()) addons.optJSONObject(i)?.let { o -> o.optJSONObject("manifest")?.let { o.put("manifest", Stremio.slim(it)) } }
            Stremio.setAddons(toKey, addons)
        }
    }
}

// ============================================================================================
// Your add-ons: connection checks, details, edit/link codes, install and remove
// ============================================================================================

object Addons {
    private val checks = HashMap<String, String>()

    fun screen(): Screen {
        val list = Home.all()
        if (list.isEmpty()) return Screen("Your add-ons", listOf(Row("Your add-ons are still loading. Go back and try again in a moment.")))
        val summary = Row("")
        val rows = list.map { a -> a to Row(a.name, "", opens = { details(a) }) }
        lateinit var s: Screen

        fun update() {
            rows.forEach { (a, row) -> row.detail = "v${a.version}  " + (checks[a.transportUrl] ?: "checking…") + "  ►" }
            val done = list.count { checks.containsKey(it.transportUrl) }
            val ok = list.count { checks[it.transportUrl]?.startsWith("OK") == true }
            val skipped = list.count { checks[it.transportUrl]?.startsWith("built") == true }
            val failed = done - ok - skipped
            summary.label = if (done < list.size) "Checking ${list.size} add-ons… $done done"
            else "${list.size} add-ons: $ok connect" + (if (failed > 0) ", $failed FAILED" else "") + (if (skipped > 0) ", $skipped built into Stremio" else "")
            App.refresh()
        }

        fun checkAll() {
            checks.clear()
            for (a in list) {
                if (a.isLocal) { checks[a.transportUrl] = "built into Stremio (skipped)"; continue }
                App.async({ val t0 = System.currentTimeMillis(); Http.get(a.transportUrl, 10_000); System.currentTimeMillis() - t0 }) { r ->
                    checks[a.transportUrl] = r.fold({ ms -> "OK $ms ms" }, { e -> "FAILED: ${Net.describe(e)}" })
                    update()
                }
            }
            update()
        }

        s = Screen(
            "Your add-ons",
            listOf(summary, Row("────────────────────")) + rows.map { it.second } + listOf(
                Row("────────────────────"),
                Row("Check connections again", action = { checkAll() }),
                Row("Install or replace an add-on by link", "►", action = { askInstall(false) }),
                Row("Add stick rows (an index config just for this app)", "►", action = { askInstall(true) }),
            ),
        )
        s.pos = 2
        s.onShow = { if (checks.isEmpty()) checkAll() else update() }
        return s
    }

    /** Paste an add-on's link (https:// or stremio://); replaces an installed copy of the same add-on. */
    fun askInstall(stickRows: Boolean) {
        val key = Account.authKey ?: return
        App.ask(if (stickRows) "Stick rows config link (ctrl+shift+v pastes)" else "Add-on link (ctrl+shift+v pastes)") { v ->
            val link = v?.trim().orEmpty()
            if (link.isEmpty()) return@ask
            val status = Screen("Installing", listOf(Row("Installing the add-on…")))
            App.push(status)
            App.async({ Stremio.installAddon(key, link, stickRows) }) { r ->
                status.rows = r.fold(
                    { name -> listOf(Row("Installed: $name"), Row("Your rows reload on Home.")) },
                    { e -> listOf(Row("Couldn't install: " + if (e is Stremio.ApiError) e.message else Net.describe(e))) },
                )
                if (r.isSuccess) { File(Store.dir, "addons.json").delete(); Home.forget(); Home.load(force = true) }
                App.refresh()
            }
        }
    }

    private fun details(a: Addon): Screen {
        val searchOnly = a.catalogs.count { it.required.isNotEmpty() }
        val head = listOf(
            Row(info("Version", a.version)),
            Row(info("Server", a.host)),
            Row(info("Connection", checks[a.transportUrl] ?: "not checked yet")),
            Row(info("Types", a.types.joinToString(", ").ifEmpty { "-" })),
            Row(info("Provides", a.resources.joinToString(", ").ifEmpty { "-" })),
            Row(info("Catalogue rows", "${a.catalogs.size}" + if (searchOnly > 0) " ($searchOnly not on home)" else "")),
        )
        val cats = if (a.catalogs.isEmpty()) emptyList() else listOf(Row("──── Catalogue rows ────")) +
            a.catalogs.map { c -> Row(c.name, MetaParse.typeLabel(c.type) + if (c.required.isNotEmpty()) " · not on home" else "") }
        val edit = if (a.isLocal) emptyList() else listOf(
            Row("Edit this add-on's settings", "►", opens = { editPage(a) }),
            Row("Copy its install link (to keep a copy)", action = { Clipboard.copyOrShow(a.transportUrl, "Install link") }),
            Row("Remove from this account", "►", opens = { removeScreen(a) }),
        )
        return Screen(a.name, edit + head + cats)
    }

    private fun editPage(a: Addon): Screen {
        val url = a.base + "/configure"
        val s = Screen(
            "Edit ${a.name.substringBefore('|').trim()}",
            listOf(
                Row("Open in your browser", action = {
                    val ok = runCatching { ProcessBuilder("xdg-open", url).redirectErrorStream(true).start(); true }.getOrDefault(false)
                    App.toast(if (ok) "Opening the settings page…" else "Couldn't open a browser: copy the link instead")
                }),
                Row("Copy the link", action = { Clipboard.copyOrShow(url, "Settings page link") }),
                Row("Or scan the code with your phone."),
                Row(""),
                Row("For AIOMetadata: press Load Configuration,"),
                Row("enter your password, change things, then Save."),
                Row("Back here, choose Reload my rows and add-ons."),
                Row("Your install link stays the same, so nothing"),
                Row("needs reinstalling."),
            ),
        )
        s.qr = qrLines(url)
        return s
    }

    private fun removeScreen(a: Addon) = Screen(
        "Remove ${a.name}?",
        listOf(
            Row("No, go back", action = { App.back() }),
            Row("Yes, remove it from this Stremio account", action = {
                val key = Account.authKey ?: return@Row
                App.async({ Stremio.removeAddon(key, a.transportUrl) }) { r ->
                    r.onSuccess { App.toast("Removed ${a.name}"); File(Store.dir, "addons.json").delete(); Home.forget(); App.resetTo(Home.screen()) }
                        .onFailure { App.toast("Couldn't remove: ${Net.describe(it)}") }
                }
            }),
        ),
    )
}

// ============================================================================================
// Share my setup (same link format as the Android app, so phone, stick and Linux all read it)
// ============================================================================================

object Share {
    private const val SCHEME = "stremonino"
    private val SCHEMES = listOf(SCHEME, "stremiolean")
    private const val STREAMS_CONFIGURE = "https://aiostreams.elfhosted.com/stremio/configure"
    private const val STREAMS_CONFIG_FILE = "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/setup/aiostreams-config.json"

    private fun payload(): JSONObject {
        val all = Home.all().filter { !it.isLocal }
        val streams = all.filter { "stream" in it.resources }
        val share = all - streams.toSet()
        val personal = Folders.rowAddons(all).flatMap { a -> a.catalogs.filter { Folders.isPersonal(it) }.map { Folders.rowKey(a, it) } }
        return JSONObject()
            .put("v", 1)
            .put("from", Profiles.current()?.name ?: "")
            .put("a", JSONArray(share.filter { !it.stickRows }.map { it.transportUrl }))
            .put("r", JSONArray(share.filter { it.stickRows }.map { it.transportUrl }))
            .put("s", Prefs.exportShared())
            .put("h", JSONArray(personal))
            .put("st", streams.isNotEmpty())
    }

    private fun encode(o: JSONObject): String {
        val d = Deflater(9).apply { setInput(o.toString().toByteArray()); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return "$SCHEME://share?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun decode(link: String): JSONObject? = runCatching {
        val d = link.trim().substringAfter("d=", "").substringBefore('&').ifEmpty { return null }
        val inf = Inflater().apply { setInput(Base64.getUrlDecoder().decode(d)) }
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

    fun screen(): Screen {
        if (Home.all().isEmpty()) return Screen("Export my setup", listOf(Row("Your add-ons are still loading. Go back and try again in a moment.")))
        val p = payload()
        val link = encode(p)
        val streams = p.optBoolean("st")
        val s = Screen(
            "Export my setup",
            listOf(
                Row("For someone else's Stremio account. On their phone: install"),
                Row("Stremonino, sign in with their account, then point the camera"),
                Row("at this code. It opens Stremonino and asks before adding anything."),
                Row("Or copy the link and send it: they paste it in"),
                Row("'Import a setup from another account'."),
                Row("────────────────────"),
                Row(info("Add-ons shared", "${p.optJSONArray("a")!!.length() + p.optJSONArray("r")!!.length()}")),
                Row(info("Settings", "theme, clock, subtitles, hidden rows")),
                Row(info("Your watchlist", "hidden on theirs")),
                Row(info("Stream add-on", if (streams) "not shared (yours uses your own debrid)" else "none")),
                Row("────────────────────"),
                Row("Copy the link to send as a message", action = { Clipboard.copyOrShow(link, "Setup link") }),
            ),
        )
        // Long setups make a code too big for a terminal: the link is the way then.
        val code = qrLines(link)
        if (code.size <= 60) s.qr = code
        return s
    }

    /** Where to start an import: a phone's camera, or a pasted link. */
    fun importScreen(): Screen {
        val rows = listOf(
            Row("The setup goes onto the Stremio account you're signed in with"),
            Row("(${Account.email.ifEmpty { "this profile" }}), so every device on that account gets it."),
            Row("Do it once, on any one of them."),
            Row("────────────────────"),
            Row("Easiest: on your phone, sign in to Stremonino with this same"),
            Row("account and point the camera at their code. Then choose"),
            Row("Reload my rows and add-ons here."),
            Row("────────────────────"),
            Row("Paste a link they sent you", "►", action = { askPaste() }),
        )
        return Screen("Import a setup", rows).also { it.pos = rows.size - 1 }
    }

    fun askPaste() {
        App.ask("Paste the shared setup link (ctrl+shift+v)") { v ->
            val text = v.orEmpty()
            val start = SCHEMES.map { text.indexOf("$it://") }.filter { it >= 0 }.minOrNull() ?: -1
            val p = if (start >= 0) decode(text.substring(start).split(Regex("\\s"))[0]) else null
            if (p == null) App.toast("That doesn't look like a setup link") else App.push(receive(p))
        }
    }

    private fun urls(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.startsWith("http") }
    private fun host(u: String) = runCatching { java.net.URI(u).host ?: u }.getOrDefault(u)

    private fun receive(p: JSONObject): Screen {
        val from = p.optString("from").ifBlank { "Someone" }
        val addons = urls(p.optJSONArray("a"))
        val rows = urls(p.optJSONArray("r"))
        val hide = p.optJSONArray("h")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        val status = Row("")
        val list = mutableListOf(
            Row("$from's setup will be added to your account (${Account.email.ifEmpty { "this profile" }})."),
            Row("Add-ons with the same name are replaced; others are kept."),
            Row("────────────────────"),
        )
        addons.forEach { list += Row("  add-on: " + host(it)) }
        rows.forEach { list += Row("  rows for this app: " + host(it)) }
        list += Row("  their settings and hidden rows")
        if (hide.isNotEmpty()) list += Row("  (their personal watchlist stays hidden)")
        list += Row("────────────────────")
        list += status
        list += Row("Add it to my account", action = {
            val key = Account.authKey ?: return@Row
            val hadStreams = Home.all().any { "stream" in it.resources }
            status.label = "Adding… this takes a few seconds"
            App.refresh()
            App.async({
                val failed = mutableListOf<String>()
                addons.forEach { u -> runCatching { Stremio.installAddon(key, u) }.onFailure { failed += host(u) + ": " + Net.describe(it) } }
                rows.forEach { u -> runCatching { Stremio.installAddon(key, u, stickRows = true) }.onFailure { failed += host(u) + ": " + Net.describe(it) } }
                failed
            }) { r ->
                val failed = r.getOrDefault(emptyList())
                p.optJSONObject("s")?.let { Prefs.importShared(it, hide) }
                File(Store.dir, "addons.json").delete()
                Home.forget()
                App.replaceTop(done(from, failed, p.optBoolean("st") && !hadStreams))
            }
        })
        list += Row("Cancel", action = { App.back() })
        return Screen("Shared setup", list)
    }

    private fun done(from: String, failed: List<String>, needStreams: Boolean): Screen {
        val rows = mutableListOf(Row(if (failed.isEmpty()) "Done: $from's setup is on your account." else "Mostly done. These didn't add:"))
        failed.forEach { rows += Row("  $it") }
        rows += Row("────────────────────")
        if (needStreams) rows += Row("One more step: your own stream add-on", "►", opens = { streamStep() })
        rows += Row("Go to Home", action = { App.resetTo(Home.screen()) })
        return Screen("Shared setup", rows)
    }

    private fun streamStep(): Screen {
        val s = Screen(
            "Your own stream add-on",
            listOf(
                Row("Streams need your own debrid account (e.g. TorBox)."),
                Row("1. Download the stream config (first link)."),
                Row("2. Open the AIOStreams page, import the file, add YOUR"),
                Row("   debrid key, and save."),
                Row("3. Copy its install link and add it here:"),
                Row("────────────────────"),
                Row("Copy the config file link", action = { Clipboard.copyOrShow(STREAMS_CONFIG_FILE, "Config file link") }),
                Row("Copy the AIOStreams page link", action = { Clipboard.copyOrShow(STREAMS_CONFIGURE, "AIOStreams page link") }),
                Row("Install my stream add-on by link", "►", action = { Addons.askInstall(false) }),
            ),
        )
        s.qr = qrLines(STREAMS_CONFIG_FILE)
        return s
    }
}

// ============================================================================================
// Device check: this computer, Java, the player, and the connections the app needs
// ============================================================================================

object DeviceCheck {
    private const val TEST_CLIP = "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/testmedia/test-clip.mp4"

    private fun firstLine(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val line = p.inputStream.bufferedReader().readLine()
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        line?.trim()
    }.getOrNull()

    fun screen(): Screen {
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val player = firstLine(Prefs.player, "--version")?.take(50) ?: "NOT FOUND (${Prefs.player})"
        val clip = listOf("wl-copy", "xclip", "xsel").firstOrNull { t -> firstLine("which", t)?.startsWith("/") == true } ?: "none (links are shown to copy by hand)"
        val api = Row(info("Stremio server", "checking…"))
        val le = Row(info("Secure sites test", "checking…"))
        fun test(row: Row, label: String, url: String) {
            App.async({ val t0 = System.currentTimeMillis(); Http.get(url, 10_000, allowErrors = true); System.currentTimeMillis() - t0 }) { r ->
                row.label = info(label, r.fold({ "OK $it ms" }, { "FAILED: ${Net.describe(it)}" })); App.refresh()
            }
        }
        val s = Screen("Device check", listOf(
            Row(info("System", "${System.getProperty("os.name")} ${System.getProperty("os.version")}")),
            Row(info("Java", "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")),
            Row(info("Player", player)),
            Row(info("Clipboard", clip)),
            Row(info("Terminal", "${App.columns() + 6} columns")),
            Row(info("App memory", "${(rt.totalMemory() - rt.freeMemory()) / mb} MB of ${rt.maxMemory() / mb} MB")),
            Row(info("Settings folder", Store.dir.path)),
            Row("────────────────────"),
            api,
            le,
            Row("────────────────────"),
            Row("Test the player: play the 20-second test clip", action = {
                App.toast("Opening the test clip…")
                Player.play(TEST_CLIP, "Stremonino test clip", null, 0) { r ->
                    App.toast(if (r.failed) "The player didn't play it: ${r.why}" else "Player OK")
                }
            }),
        ))
        s.onShow = {
            test(api, "Stremio server", "https://api.strem.io/api/")
            test(le, "Secure sites test", "https://valid-isrgrootx1.letsencrypt.org/")
        }
        return s
    }
}
