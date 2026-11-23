package app.stremiolite.linux

import app.stremiolite.Addon
import app.stremiolite.Folders
import app.stremiolite.Full
import app.stremiolite.Http
import app.stremiolite.Meta
import app.stremiolite.MetaParse
import app.stremiolite.SearchRank
import app.stremiolite.Stremio
import app.stremiolite.Video
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException

object Net {
    fun describe(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is SocketTimeoutException -> return "timed out"
                is UnknownHostException -> return "address not found (offline?)"
                is Http.HttpError -> return "server said ${t.code}"
                is java.net.ConnectException -> return "connection refused"
                is org.json.JSONException -> return "unexpected reply"
            }
            t = t.cause
        }
        return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** A QR code as lines of half-block characters (dark modules drawn in the foreground colour). */
fun qrLines(text: String): List<String> = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val lines = mutableListOf<String>()
    var y = 0
    while (y < m.height) {
        val sb = StringBuilder()
        for (x in 0 until m.width) {
            val top = m[x, y]
            val bottom = y + 1 < m.height && m[x, y + 1]
            sb.append(when { top && bottom -> '█'; top -> '▀'; bottom -> '▄'; else -> ' ' })
        }
        lines += sb.toString()
        y += 2
    }
    lines
}.getOrDefault(emptyList())

// ============================================================================================
// Sign in
// ============================================================================================

object SignIn {
    fun screen(): Screen {
        val linkRow = Row("   getting a code…")
        val codeRow = Row("")
        val status = Row("")
        lateinit var s: Screen
        var code: String? = null
        var started = 0L
        var active = false

        fun poll() {
            val c = code ?: return
            if (!active || !App.isTop(s)) return
            if (System.currentTimeMillis() - started > 10 * 60_000) { status.label = "This code has expired. Choose 'Get a new code'."; App.refresh(); return }
            App.async({ Stremio.linkRead(c) }) { r ->
                val key = r.getOrNull()
                if (key != null && code == c) {
                    active = false
                    status.label = "Approved. Loading…"
                    App.async({ runCatching { Stremio.userEmail(key) }.getOrDefault("") }) { e -> signedIn(key, e.getOrDefault("")) }
                } else App.later(3_000) { poll() }
            }
        }

        fun newCode() {
            code = null
            s.qr = null
            status.label = ""
            linkRow.label = "   getting a code…"
            App.async({ Stremio.linkCreate() }) { r ->
                r.onSuccess { lc ->
                    code = lc.code
                    started = System.currentTimeMillis()
                    val link = lc.link.ifEmpty { "https://www.stremio.com/link" }
                    linkRow.label = "   " + link.removePrefix("https://").removePrefix("http://")
                    codeRow.label = "   " + lc.code
                    status.label = "Waiting for you to approve it on your phone…"
                    s.qr = qrLines(link)
                    active = true
                    App.later(3_000) { poll() }
                }.onFailure { e -> status.label = "Couldn't get a code (${Net.describe(e)}). Choose 'Get a new code'." }
                App.refresh()
            }
        }

        s = Screen(
            "Sign in to Stremio",
            listOf(
                Row("Scan the code with your phone, or open this link:"),
                linkRow,
                Row("and enter the code:"),
                codeRow,
                Row(""),
                status,
                Row("────────────────────"),
                Row("Get a new code", action = { newCode() }),
                Row("Use email and password instead", action = { askEmail() }),
                Row("Quit", action = { App.quit() }),
            ),
        )
        s.onShow = { if (code == null) newCode() else { active = true; poll() } }
        s.onHide = { active = false }
        s.pos = 7
        return s
    }

    private fun askEmail() {
        App.ask("Email") { email ->
            if (email.isNullOrBlank()) return@ask
            App.ask("Password", hidden = true) { pw ->
                if (pw.isNullOrEmpty()) return@ask
                App.toast("Signing in…")
                App.async({ Stremio.login(email.trim(), pw) }) { r ->
                    r.onSuccess { key -> signedIn(key, email.trim()) }
                        .onFailure { e -> App.toast("Couldn't sign in: " + if (e is Stremio.ApiError) e.message else Net.describe(e)) }
                }
            }
        }
    }

    private fun signedIn(key: String, email: String) {
        Profiles.beforeSave(email)
        Account.save(key, email)
        Lib.reset()
        Home.forget()
        App.resetTo(Home.screen())
    }
}

// ============================================================================================
// Home and the Index
// ============================================================================================

object Home {
    private var addons: List<Addon>? = null
    private var loading = false
    private var error: String? = null
    private var rebuild: (() -> Unit)? = null

    fun all(): List<Addon> = addons ?: emptyList()
    fun rowAddons() = Folders.rowAddons(all())
    fun forget() { addons = null; error = null }
    fun redraw() { rebuild?.invoke() }

    fun screen(): Screen {
        lateinit var s: Screen
        val status = Row("")
        fun build() {
            val list = addons
            val rows = mutableListOf<Row>()
            Updates.homeRow()?.let { rows += it; rows += Row("────────────────────") }
            status.label = when {
                loading && list == null -> "Loading your rows…"
                error != null && list == null -> "Couldn't load your rows: $error"
                error != null -> "Showing saved rows (refresh failed: $error)"
                else -> ""
            }
            if (status.label.isNotEmpty()) rows += status
            s.tag = Profiles.current()?.name ?: "Linux"
            val cw = Lib.continueWatching()
            val lib = Lib.library()
            val waiting = Waiting.list()
            rows += Row("Search", "/  ►", opens = null, action = { Search.start() })
            rows += Row("Continue watching", if (cw.isEmpty()) "nothing yet" else "${cw.size}  ►", opens = { continueScreen() })
            rows += Row("Library", if (lib.isEmpty()) "empty" else "${lib.size}  ►", opens = { libraryScreen() })
            rows += Row("New episodes", NewEpisodes.recentCount()?.let { if (it == 0) "none  ►" else "$it  ►" } ?: "", opens = { NewEpisodes.screen() })
            rows += Row("Calendar", NewEpisodes.weekCount()?.let { if (it == 0) "nothing this week  ►" else "$it this week  ►" } ?: "", opens = { NewEpisodes.calendar() })
            rows += Row("Watched", Watched.list().size.let { if (it == 0) "" else "$it  ►" }, opens = { watchedScreen() })
            rows += Row("Waiting for streams", Waiting.summary(), opens = { Waiting.screen() })
            rows += Row("────────────────────")
            if (list != null) {
                val entries = Folders.entries(list, Prefs.hiddenRows)
                val main = entries.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
                entries.filter { it.folder == null }.forEach { e ->
                    val tag = if (e.addon === main) "" else e.addon.name.substringBefore('|').trim().take(18) + " · "
                    rows += Row(e.name, tag + MetaParse.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(e.addon, e.catalog) },
                        play = null)
                }
                val folders = Index.folderRows(entries)
                if (folders.isNotEmpty()) {
                    rows += Row("──── Index ─────────────")
                    rows += folders
                }
                rows += Row("────────────────────")
            }
            // Same order and wording as the phone and stick.
            rows += Row("Settings", opens = { Settings.screen() })
            rows += Row("Your add-ons", list?.let { "${it.size}  ►" } ?: "►", opens = { Addons.screen() })
            rows += Row("Reload my rows and add-ons", action = { load(force = true) })
            rows += Row("Export my setup to another account", "►", opens = { Share.screen() })
            rows += Row("Import a setup from another account", "►", opens = { Share.importScreen() })
            rows += Row("Device check", opens = { DeviceCheck.screen() })
            rows += Row("About", opens = { about() })
            rows += Row("Profile", Profiles.current()?.name ?: "…")
            rows += Row("Switch profile", "►", opens = { Profiles.chooser() })
            rows += Row("Sign out of this profile", opens = { signOut() })
            s.rows = rows
            App.refresh()
        }
        rebuild = { build() }
        s = Screen("Stremonino", listOf(Row("Loading…")), tag = Profiles.current()?.name ?: "Linux")
        s.onShow = {
            if (addons == null) {
                runCatching { Stremio.parseAddons(Store.file("addons.json").readText()) }.onSuccess { addons = it }
                load()
            }
            Lib.pull { build() }
            Updates.check { build() }
            if (!NewEpisodes.fresh()) App.later(4_000) { if (addons != null) NewEpisodes.load { build() } }
            build()
        }
        return s
    }

    fun load(force: Boolean = false) {
        val key = Account.authKey ?: return
        if (loading) return
        loading = true
        rebuild?.invoke()
        App.async({
            val json = Stremio.withLiveManifests(Stremio.addonsJson(key))
            json to Stremio.parseAddons(json)
        }) { r ->
            loading = false
            r.onSuccess { (json, list) ->
                addons = list
                error = null
                runCatching { Store.file("addons.json").writeText(json) }
                if (force) Lib.pull(full = true) { rebuild?.invoke() }
            }.onFailure { e ->
                error = if (e is Stremio.ApiError) e.message else Net.describe(e)
                if (e is Stremio.ApiError && e.message?.contains("session", true) == true) {
                    App.toast("Your sign-in has expired: sign in again")
                    Account.clear()
                    App.resetTo(SignIn.screen())
                }
            }
            rebuild?.invoke()
        }
    }

    /** 1:38:52 or 38:52, like the phone and stick. */
    private fun clock(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    private fun continueScreen(): Screen {
        val rows = Lib.continueWatching().map { i ->
            // Same as the phone and stick: "S01E02  1:38:52 left", or "next: S01E03".
            val ep = i.videoId.split(':').let { p -> if (p.size >= 3) { val se = p[p.size - 2].toIntOrNull(); val e = p.last().toIntOrNull(); if (se != null && e != null) "S%02dE%02d".format(se, e) else "" } else "" }
            val left = when {
                i.upNext -> "next: $ep".trim().removeSuffix(":")
                i.duration > 0 -> listOf(ep, "${clock((i.duration - i.offset).coerceAtLeast(0))} left").filter { it.isNotEmpty() }.joinToString("  ")
                else -> ep
            }
            Row(i.name, left + "  ►", opens = { Details.screenFor(i.id, i.type, i.name) }, play = { App.push(Details.screenFor(i.id, i.type, i.name, autoPlay = true)) })
        }
        val clear = if (rows.isEmpty()) emptyList() else listOf(
            Row("────────────────────"),
            Row("Clear Continue watching", "►", opens = {
                confirmScreen("Clear Continue watching?", "Removes all ${rows.size} from Continue watching on every device. Watched marks stay.", "Yes, clear all ${rows.size}") {
                    Lib.clearContinue(); App.toast("Continue watching cleared"); App.resetTo(Home.screen())
                }
            }),
        )
        return Screen("Continue watching", rows.ifEmpty { listOf(Row("Nothing yet.")) } + clear)
    }

    private fun libraryScreen(): Screen {
        val rows = Lib.library().map { i ->
            Row(i.name, MetaParse.typeLabel(i.type) + "  ►", opens = { Details.screenFor(i.id, i.type, i.name) })
        }
        val clear = if (rows.isEmpty()) emptyList() else listOf(
            Row("────────────────────"),
            Row("Clear Library", "►", opens = {
                confirmScreen("Clear Library?", "Removes all ${rows.size} from your Library on every device. History and watched marks stay.", "Yes, clear all ${rows.size}") {
                    Lib.clearLibrary(); App.toast("Library cleared"); App.resetTo(Home.screen())
                }
            }),
        )
        return Screen("Library", rows.ifEmpty { listOf(Row("Your library is empty.")) } + clear)
    }

    private fun watchedScreen(): Screen {
        lateinit var s: Screen
        val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        fun build() {
            val list = Watched.list()
            s.rows = if (list.isEmpty()) listOf(Row("Nothing finished yet. Films and episodes you watch to the end appear here."))
            else list.map { e ->
                val detail = listOf(e.label, if (e.time > 0) day.format(java.util.Date(e.time)) else "").filter { it.isNotBlank() }.joinToString("   ")
                Row(e.name, "$detail  ►", opens = { watchedEntry(e) })
            }
            App.refresh()
        }
        s = Screen("Watched", emptyList())
        build()
        s.onShow = { build(); Lib.pull { build() } }
        return s
    }

    /** One finished film or episode: open it, or take it off the watched list (here and on the account). */
    private fun watchedEntry(e: Watched.Entry): Screen = Screen(
        e.name + if (e.label.isNotEmpty()) " ${e.label}" else "",
        listOf(
            Row("Open", "►", opens = { Details.screenFor(e.id, e.type, e.name) }),
            Row("Remove from watched", action = {
                if (e.type == "movie") {
                    Lib.unwatch(e.id, e.type, e.id, emptyList()); Watched.remove(e.id, "")
                    App.toast("Removed from watched"); App.back()
                } else {
                    // Episodes: the show's episode order is needed to update the account's marks.
                    App.toast("Removing…")
                    App.async({ Details.load(e.type, e.id) }) { r ->
                        r.onSuccess { show ->
                            Lib.unwatch(e.id, e.type, e.videoId, MetaParse.stremioOrder(show)); Watched.remove(e.id, e.videoId)
                            App.toast("Removed from watched"); App.back()
                        }.onFailure { App.toast("Couldn't remove: ${Net.describe(it)}") }
                    }
                }
            }),
        ),
    )

    private fun about() = Screen(
        "About",
        listOf(
            Row(info("Stremonino", "Linux, build ${Updates.current}")),
            Row(info("Player", Prefs.player)),
            Row(info("Settings folder", Store.dir.path)),
            Row(info("Fire Stick install", "Downloader code 4200313")),
            Row(""),
            Row("A text-menu way to browse your Stremio add-ons and play"),
            Row("through mpv. Not made by or connected with Stremio."),
        ),
    )

    private fun signOut() = Screen(
        "Sign out?",
        listOf(
            Row("No, go back", action = { App.back() }),
            Row("Yes, sign this profile out of this computer", action = {
                Profiles.current()?.let { Profiles.remove(it.id) } ?: run { Account.clear(); Lib.reset() }
                forget()
                App.resetTo(
                    when {
                        Profiles.all().size > 1 -> Profiles.chooser()
                        Account.authKey != null -> screen()
                        else -> SignIn.screen()
                    }
                )
            }),
        ),
    )
}

object Index {
    private fun parts(e: Folders.Entry) = e.folder!!.split(" > ").map { it.trim() }.filter { it.isNotEmpty() }

    fun folderRows(entries: List<Folders.Entry>): List<Row> = groupRows(entries.filter { it.folder != null }, 0)

    private fun groupRows(list: List<Folders.Entry>, depth: Int): List<Row> {
        val groups = LinkedHashMap<String, MutableList<Folders.Entry>>()
        list.forEach { e -> parts(e).getOrNull(depth)?.let { groups.getOrPut(it) { mutableListOf() } += e } }
        groups.remove("More")?.let { groups["More"] = it }
        return groups.map { (name, l) -> Row(name, "${l.size}  ►", opens = { folder(name, l, depth + 1) }) }
    }

    private fun folder(name: String, list: List<Folders.Entry>, depth: Int): Screen {
        val main = list.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
        val subs = groupRows(list, depth)
        val direct = list.filter { parts(it).size <= depth }.map { e ->
            val tag = if (e.addon === main) "" else e.addon.name.substringBefore('|').trim().take(18) + " · "
            Row(e.name, tag + MetaParse.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(e.addon, e.catalog, e.name) })
        }
        val title = if (depth > 1) parts(list.first()).take(depth).joinToString(" · ") else name
        return Screen(title, subs + direct, tag = "Index").also { it.key = "index:$title" }
    }
}

// ============================================================================================
// Catalogue rows
// ============================================================================================

object Catalogs {
    private const val LOAD_AHEAD = 15

    private fun pageUrl(a: Addon, c: Addon.Catalog, skip: Int) =
        a.base + "/catalog/" + Net.enc(c.type) + "/" + Net.enc(c.id) + (if (skip > 0) "/skip=$skip" else "") + ".json"

    fun detail(m: Meta): String {
        val kind = when (m.type) { "movie" -> "Movie"; "series" -> "Series"; else -> m.type }
        val rating = if (m.rating.isNotBlank()) "★ ${m.rating}" else ""
        return "${m.year.take(9).padEnd(9)} ${rating.padEnd(6)} ${kind.padEnd(6)}"
    }

    fun mark(m: Meta): String? = when {
        m.type == "movie" && Lib.filmWatched(m.id) -> "✓"
        Lib.info(m.id)?.continuing == true -> "▶"
        else -> null
    }

    fun row(a: Addon?, m: Meta): Row = Row(
        (mark(m)?.let { "$it " } ?: "") + m.name, detail(m),
        opens = { Details.screen(a, m) },
        play = { App.push(Details.screen(a, m, autoPlay = true)) },
    )

    fun screen(a: Addon, c: Addon.Catalog, title: String = c.name): Screen {
        val items = mutableListOf<Meta>()
        val seen = HashSet<String>()
        var fetched = 0
        var loading = false
        var ended = false
        var error: String? = null
        lateinit var s: Screen
        lateinit var loadMore: () -> Unit
        val placeKey = "cat:${a.transportUrl}|${c.type}|${c.id}"
        var pending: Int? = App.place(placeKey)?.takeIf { it in 1..400 }

        // "Watched films in lists: Hide them" leaves out films marked watched.
        fun visible(): List<Meta> = if (Prefs.hideWatched) items.filter { !(it.type == "movie" && Lib.filmWatched(it.id)) } else items

        fun rebuild() {
            val shown = visible()
            val rows = shown.mapTo(ArrayList(shown.size + 2)) { row(a, it) }
            when {
                error != null -> { rows += Row("Couldn't load: $error"); rows += Row("Try again", action = { error = null; loadMore() }) }
                loading -> rows += Row(if (items.isEmpty()) "Loading…" else "Loading more…")
                ended -> rows += Row(if (items.isEmpty()) "This row is empty." else "──── end of list: ${items.size} titles ────")
            }
            s.rows = rows
            // With watched films hidden a page can come back nearly empty: keep the screen filled.
            if (shown.size < LOAD_AHEAD && !loading && !ended && error == null && items.isNotEmpty()) App.later(0) { loadMore() }
            pending?.let { want ->
                when {
                    want < shown.size -> { s.moveTo = want; pending = null }
                    ended || error != null -> { s.moveTo = (shown.size - 1).coerceAtLeast(0); pending = null }
                    !loading -> App.later(0) { loadMore() }
                }
            }
            App.refresh()
        }

        loadMore = {
            if (!loading && !ended) {
                loading = true
                rebuild()
                val url = pageUrl(a, c, fetched)
                App.async({ MetaParse.parse(Http.get(url)) }) { r ->
                    loading = false
                    r.onSuccess { page ->
                        fetched += page.size
                        var added = 0
                        for (m in page) if (seen.add(m.type + ":" + m.id)) { items += m; added++ }
                        if (page.isEmpty() || added == 0) ended = true
                    }.onFailure { e -> error = Net.describe(e) }
                    rebuild()
                }
            }
        }

        s = Screen(title, listOf(Row("Loading…")), tag = MetaParse.typeLabel(c.type))
        s.key = placeKey
        s.onShow = { if (items.isEmpty() && !loading && error == null && !ended) loadMore() else if (items.isNotEmpty()) rebuild() }
        s.onSelect = { pos -> if (pos >= visible().size - LOAD_AHEAD && error == null) loadMore() }
        return s
    }
}

// ============================================================================================
// Search
// ============================================================================================

object Search {
    fun start() {
        if (Account.authKey == null) return
        App.ask("Search") { q -> if (!q.isNullOrBlank()) App.push(results(q.trim())) }
    }

    private fun results(q: String): Screen {
        lateinit var s: Screen
        val sources = Folders.rowAddons(Home.all()).filter { !it.isLocal }.flatMap { a -> a.catalogs.filter { it.searchable }.map { a to it } }
        val found = LinkedHashMap<String, Pair<Addon, Meta>>()
        var answered = 0
        fun row(p: Pair<Addon, Meta>): Row {
            val (a, m) = p
            return if (SearchRank.isCollection(m.type)) Row(m.name, listOf(m.year, "Collection").filter { it.isNotBlank() }.joinToString("  ") + "  ►", opens = { Details.screen(a, m) })
            else Catalogs.row(a, m)
        }
        fun build() {
            val rows = mutableListOf<Row>()
            rows += Row(if (answered < sources.size) "Searching… ${answered} of ${sources.size}" else "${found.size} results for \"$q\"")
            val g = SearchRank.group(found.values.toList(), q) { it.second }
            if (g.best.isNotEmpty()) {
                rows += Row("──── Best matches ────")
                g.best.forEach { rows += row(it) }
            }
            if (found.isNotEmpty()) rows += Row("────────────────────")
            fun group(name: String, list: List<Pair<Addon, Meta>>) {
                if (list.isNotEmpty()) rows += Row(name, "${list.size}  ►", opens = { Screen("$name: $q", list.map { row(it) }) })
            }
            group("Collections", g.collections)
            group("Films", g.films)
            group("Shows", g.shows)
            group("Other", g.other)
            if (answered >= sources.size && found.isEmpty() && sources.isNotEmpty()) rows += Row("Nothing found.")
            s.rows = rows
            App.refresh()
        }
        s = Screen("Search: $q", listOf(Row("Searching…")))
        if (sources.isEmpty()) s.rows = listOf(Row("None of your add-ons can search."))
        sources.forEach { (a, c) ->
            val url = a.base + "/catalog/" + Net.enc(c.type) + "/" + Net.enc(c.id) + "/search=" + Net.enc(q) + ".json"
            App.async({ MetaParse.parse(Http.get(url)) }) { r ->
                answered++
                r.getOrNull()?.forEach { m -> found.putIfAbsent(m.type + ":" + m.id, a to m) }
                build()
            }
        }
        s.pos = 2
        return s
    }
}

// ============================================================================================
// Title details, seasons and episodes
// ============================================================================================

object Details {
    private val cache = HashMap<String, Full>()

    /** A title's full details (cached), for screens outside Details. */
    fun load(type: String, id: String): Full = fetch(null, type, id)

    private fun fetch(from: Addon?, type: String, id: String): Full {
        cache["$type/$id"]?.let { return it }
        val candidates = (listOfNotNull(from) + Home.all()).distinct().filter { it.supports("meta", type, id) }
        if (candidates.isEmpty()) throw IllegalStateException("none of your add-ons can describe this title")
        var last: Throwable? = null
        for (a in candidates) {
            try {
                val m = JSONObject(Http.get(a.base + "/meta/" + Net.enc(type) + "/" + Net.enc(id) + ".json")).optJSONObject("meta") ?: continue
                val full = MetaParse.full(m, a.name)
                cache["$type/$id"] = full
                return full
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("no details found")
    }

    fun screenFor(id: String, type: String, name: String, autoPlay: Boolean = false): Screen =
        screen(null, Meta(id, type, name, "", "", "", emptyList(), ""), autoPlay)

    private fun isShow(f: Full) = f.type != "movie" && f.videos.any { it.season > 0 || it.episode > 0 }

    /** What "play" means: a film (resuming if started); for a show, the episode in progress, the next one, or the first not watched. */
    private fun mainVideo(f: Full): Video? {
        if (!isShow(f)) return null
        val eps = MetaParse.ordered(f)
        val info = Lib.info(f.id)
        if (info != null && info.continuing) eps.firstOrNull { it.id == info.videoId }?.let { return it }
        val done = Lib.watchedEpisodes(f.id, MetaParse.stremioOrder(f))
        return eps.firstOrNull { it.id !in done && !upcoming(it) } ?: eps.firstOrNull()
    }

    fun watchFor(f: Full, v: Video?): Watch {
        if (v == null) return Watch(f.id, f.type, f.name, f.id, "", f.runtime)
        val eps = MetaParse.ordered(f)
        val next = eps.getOrNull(eps.indexOfFirst { it.id == v.id } + 1)?.takeIf { !upcoming(it) }
        return Watch(f.id, f.type, f.name, v.id, MetaParse.epLabel(v), f.runtime, MetaParse.stremioOrder(f), next?.id, next?.let { MetaParse.epLabel(it) })
    }

    private fun title(f: Full, v: Video?) = if (v == null) f.name else "${f.name} ${MetaParse.epLabel(v)}"

    private fun playRows(f: Full, v: Video?): List<Row> {
        val w = watchFor(f, v)
        val at = Lib.resumeAt(f.id, w.videoId)
        val t = title(f, v)
        val rows = mutableListOf<Row>()
        val what = if (v != null) " ${MetaParse.epLabel(v)}" else ""
        if (at > 0) {
            rows += Row("Resume$what from ${clock(at)}", "►", opens = { Play.best(f.type, w.videoId, t, w, at) })
            rows += Row("Play$what from the start", opens = { Play.best(f.type, w.videoId, t, w, 0) })
        } else {
            rows += Row("Play best$what", "►", opens = { Play.best(f.type, w.videoId, t, w, 0) })
        }
        rows += Row("Choose a stream", opens = { Play.list(f.type, w.videoId, t, w, at) })
        return rows
    }

    private fun rows(f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        if (!isShow(f) && f.videos.isEmpty()) rows += playRows(f, null)
        if (isShow(f)) {
            mainVideo(f)?.let { rows += playRows(f, it) }
            val seasons = MetaParse.ordered(f).groupBy { it.season }
            rows += Row("────────────────────")
            seasons.forEach { (n, eps) ->
                val done = Lib.watchedEpisodes(f.id, MetaParse.stremioOrder(f))
                val left = eps.count { it.id !in done && !upcoming(it) }
                rows += Row("Season $n", (if (left == 0) "✓ " else "") + "${eps.size} episodes  ►", opens = { episodes(f, n, eps) })
            }
        } else if (f.videos.isNotEmpty()) {
            // A film collection: each part is a film of its own, in release order, with ✓ on the ones watched.
            rows += Row("Films in this collection: ${f.videos.size}")
            rows += Row("────────────────────")
            SearchRank.parts(f.videos).forEach { v ->
                val name = v.title.ifEmpty { v.id }
                val own = v.id.startsWith("tt") || v.id.startsWith("tmdb:")
                rows += Row((if (Lib.filmWatched(v.id)) "✓ " else "") + name, v.released.take(4) + "  ►",
                    opens = { if (own) screenFor(v.id, "movie", name) else episode(f, v) },
                    play = { if (own) App.push(screenFor(v.id, "movie", name, autoPlay = true)) else App.push(episode(f, v)) })
            }
        }
        val collection = !isShow(f) && f.videos.isNotEmpty()
        rows += Row("────────────────────")
        if (!collection) {
            rows += libraryRows(f)
            rows += Row("────────────────────")
        }
        rows += Row(info("Type", if (collection) "Collection" else if (f.type == "movie") "Movie" else if (f.type == "series") "Series" else f.type))
        if (f.year.isNotBlank()) rows += Row(info("Year", f.year))
        if (f.rating.isNotBlank()) rows += Row(info("IMDb rating", "★ ${f.rating}"))
        if (f.runtime.isNotBlank()) rows += Row(info("Runtime", f.runtime))
        if (f.genres.isNotEmpty()) rows += Row(info("Genres", f.genres.joinToString(", ")))
        if (f.director.isNotEmpty()) rows += Row(info("Director", f.director.joinToString(", ")))
        if (f.cast.isNotEmpty()) rows += Row(info("Cast", f.cast.take(5).joinToString(", ")))
        if (f.description.isNotBlank()) {
            rows += Row("────────────────────")
            MetaParse.wrap(f.description, App.columns()).forEach { rows += Row(it) }
        }
        rows += Row("────────────────────")
        rows += Row(info("Details from", f.source.substringBefore('|').trim()))
        return rows
    }

    private fun libraryRows(f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        lateinit var lib: Row
        lib = Row(if (Lib.inLibrary(f.id)) "Remove from library" else "Add to library", action = {
            val added = Lib.toggleLibrary(f.id, f.type, f.name)
            lib.label = if (added) "Remove from library" else "Add to library"
            App.toast(if (added) "Added to library" else "Removed from library")
        })
        rows += lib
        if (f.type == "series") {
            lateinit var fol: Row
            fun folLabel() = if (Follow.isFollowed(f.id)) "Following new episodes and seasons ✓ (stop)" else "Tell me about new episodes and seasons"
            fol = Row(folLabel(), action = {
                if (Follow.isFollowed(f.id)) { Follow.unfollow(f.id); App.toast("Stopped following ${f.name}") }
                else { Follow.follow(f.id, f.name); App.toast("Following ${f.name}: your phone tells you when new episodes play") }
                fol.label = folLabel()
                App.refresh()
            })
            rows += fol
        }
        if (f.type == "movie" && Lib.filmWatched(f.id)) rows += Row("Remove from watched", action = {
            Lib.unwatch(f.id, f.type, f.id, emptyList()); App.toast("${f.name} is no longer marked as watched"); App.top()?.onShow?.invoke()
        })
        if (Lib.info(f.id)?.continuing == true) rows += Row("Remove from Continue watching", action = {
            Lib.removeFromContinue(f.id); App.toast("Removed from Continue watching")
        })
        if (f.type == "movie") {
            lateinit var wait: Row
            fun label() = if (Waiting.has(f.id)) "Stop waiting for a stream" else "Tell me when a stream is available"
            wait = Row(label(), action = {
                if (Waiting.has(f.id)) Waiting.remove(f.id) else Waiting.add(f.id, f.type, f.name, f.year)
                wait.label = label()
                App.refresh()
            })
            rows += wait
            // Played it but the picture wasn't great? Keep an eye out for a better release.
            lateinit var better: Row
            fun betterLabel() = if (Waiting.watchingForBetter(f.id)) "Watching for a better version ✓ (stop)" else "Tell me when a better version appears"
            better = Row(betterLabel(), action = {
                if (Waiting.watchingForBetter(f.id)) { Waiting.remove(f.id); better.label = betterLabel(); App.refresh() }
                else {
                    App.toast("Checking what's there now…")
                    Waiting.addBetter(f.id, f.type, f.name, f.year) { have ->
                        App.toast("Your phone will tell you when something better than $have appears", 8_000)
                        better.label = betterLabel(); wait.label = label(); App.refresh()
                    }
                }
            })
            rows += better
        }
        return rows
    }

    fun screen(from: Addon?, item: Meta, autoPlay: Boolean = false): Screen {
        val loading = Row("Loading details…")
        var full: Full? = null
        var started = false
        lateinit var s: Screen
        s = Screen(item.name, listOf(loading), tag = item.year)
        s.onShow = {
            // Picks up where you got to on another device (resume point, watched marks).
            Lib.pull(force = System.currentTimeMillis() - Lib.lastPull > 15_000) { full?.let { f -> if (App.isTop(s)) { s.rows = rows(f); App.refresh() } } }
            val f = full
            if (f != null) { s.rows = rows(f); App.refresh() }
            else if (!started) {
                started = true
                App.async({ fetch(from, item.type, item.id) }) { r ->
                    r.onSuccess { loaded ->
                        full = loaded
                        s.rows = rows(loaded)
                        if (autoPlay && App.isTop(s)) {
                            val v = mainVideo(loaded)
                            val w = watchFor(loaded, v)
                            App.push(Play.best(loaded.type, w.videoId, title(loaded, v), w, Lib.resumeAt(loaded.id, w.videoId)))
                        }
                    }.onFailure { e -> loading.label = "Couldn't load details: ${Net.describe(e)}" }
                    App.refresh()
                }
            }
        }
        return s
    }

    private fun episodes(f: Full, season: Int, eps: List<Video>): Screen {
        val s = Screen("${f.name} · Season $season", emptyList())
        fun build() {
            val done = Lib.watchedEpisodes(f.id, MetaParse.stremioOrder(f))
            val info = Lib.info(f.id)
            s.rows = eps.map { v ->
                val mark = when {
                    v.id in done -> "✓ "
                    info?.continuing == true && info.videoId == v.id -> "▶ "
                    else -> "  "
                }
                val when_ = if (upcoming(v)) "out ${v.released}" else v.released
                Row(mark + MetaParse.epLabel(v) + "  " + v.title, when_ + "  ►", opens = { episode(f, v) }, play = {
                    val w = watchFor(f, v)
                    App.push(Play.best(f.type, w.videoId, title(f, v), w, Lib.resumeAt(f.id, w.videoId)))
                })
            }
        }
        build()
        s.onShow = { build(); App.refresh() }
        s.key = "eps:${f.id}:$season"
        return s
    }

    private fun episode(f: Full, v: Video): Screen {
        val s = Screen("${f.name} ${MetaParse.epLabel(v)}", emptyList())
        s.onShow = {
            val rows = mutableListOf<Row>()
            if (upcoming(v)) rows += Row("Not out yet: ${v.released}")
            rows += playRows(f, v)
            rows += Row("────────────────────")
            if (v.title.isNotBlank()) rows += Row(v.title)
            if (v.released.isNotBlank()) rows += Row(info("Released", v.released))
            if (v.id in Lib.watchedEpisodes(f.id, MetaParse.stremioOrder(f))) rows += Row("Remove from watched", action = {
                Lib.unwatch(f.id, f.type, v.id, MetaParse.stremioOrder(f))
                App.toast("${MetaParse.epLabel(v)} is no longer marked as watched")
                s.onShow?.invoke()
            })
            if (v.overview.isNotBlank()) MetaParse.wrap(v.overview, App.columns()).forEach { rows += Row(it) }
            s.rows = rows
            App.refresh()
        }
        return s
    }

    private fun upcoming(v: Video): Boolean = v.released.length == 10 && v.released > java.time.LocalDate.now().toString()
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ============================================================================================
// Settings
// ============================================================================================

object Settings {
    fun screen(): Screen {
        lateinit var s: Screen
        fun build() {
            // Same order as the phone and stick. Text size is the terminal's own (kitty: ctrl+shift+= / -).
            s.rows = listOf(
                Row("Enter on a line to change it"),
                Row("────────────────────"),
                Row("Player", Prefs.player, action = {
                    App.ask("Player command (e.g. mpv)", Prefs.player) { v -> if (!v.isNullOrBlank()) { Prefs.player = v.trim(); build() } }
                }),
                Row("Player options", Prefs.playerArgs.ifEmpty { "none" }, action = {
                    App.ask("Extra options (e.g. --fs --volume=80)", Prefs.playerArgs) { v -> if (v != null) { Prefs.playerArgs = v.trim(); build() } }
                }),
                Row("Subtitle language", "◄ ${Prefs.language().name} ►", action = {
                    Prefs.subLang = (Prefs.subLang + 1) % app.stremiolite.Languages.all.size; Prefs.syncOut(); build()
                }),
                Row("Auto-play next episode", "◄ ${if (Prefs.autoNext) "On" else "Off"} ►", action = {
                    Prefs.autoNext = !Prefs.autoNext; Prefs.syncOut(); build()
                }),
                Row("────────────────────"),
                Row("Theme, text size and clock", "◄ ${if (Prefs.lookLocal) "This computer only" else "Same on all devices"} ►", action = {
                    Prefs.lookLocal = !Prefs.lookLocal
                    if (!Prefs.lookLocal) Prefs.syncOut()
                    App.toast(if (Prefs.lookLocal) "This computer keeps its own look" else "Your look is now shared with your other devices")
                    build()
                }),
                Row("Theme", "◄ ${Look.theme().name} ►", action = {
                    Prefs.theme = (Prefs.theme + 1) % themes.size; Prefs.syncOut(); build()
                }),
                Row("Text size", "your terminal's (kitty: ctrl+shift+= / −)"),
                Row("Clock", "◄ ${Look.clock().name} ►", action = {
                    Prefs.clock = (Look.clocks.indexOf(Look.clock()) + 1) % Look.clocks.size; Prefs.syncOut(); build()
                }),
                Row("Watched films in lists", "◄ ${if (Prefs.hideWatched) "Hide them" else "Show with ✓"} ►", action = {
                    Prefs.hideWatched = !Prefs.hideWatched; Prefs.syncOut(); build()
                }),
                Row("Hide rows", "►", opens = { rowsScreen() }),
                Row("────────────────────"),
                Row(info("App version", "build ${Updates.current}")),
                Row("Check for updates", action = { Updates.checkNow() }),
            )
            App.refresh()
        }
        s = Screen("Settings", emptyList())
        build()
        s.pos = 2
        return s
    }

    private fun rowsScreen(): Screen {
        lateinit var s: Screen
        fun build() {
            val pairs = Folders.rowAddons(Home.all()).filter { !it.isLocal }.flatMap { a -> a.catalogs.filter { Folders.openable(it) }.map { a to it } }
            val hidden = Prefs.hiddenRows
            val rows = mutableListOf(
                Row("Enter switches a row between shown and hidden (shared with your other devices)"),
                Row("Show all rows", action = { Prefs.hiddenRows = emptySet(); Prefs.syncOut(); build(); Home.redraw() }),
                Row("────────────────────"),
            )
            pairs.forEach { (a, c) ->
                val key = Folders.rowKey(a, c)
                rows += Row("${c.name}  ·  ${MetaParse.typeLabel(c.type)}", if (key in hidden) "hidden" else "shown", action = {
                    val now = Prefs.hiddenRows.toMutableSet()
                    if (key in now) now -= key else now += key
                    Prefs.hiddenRows = now
                    Prefs.syncOut()
                    build()
                    Home.redraw()
                })
            }
            s.rows = rows
            App.refresh()
        }
        s = Screen("Hide rows", emptyList())
        build()
        s.pos = 3
        return s
    }
}

// ============================================================================================
// Waiting for streams (the list shared with the phone; the phone does the background checks)
// ============================================================================================

object Waiting {
    private const val ID = "stremiolite:waiting"

    /** Same shape as the phone's: [better] entries wait for a release better than [have] (score [bar]). */
    class Entry(
        val id: String, val type: String, val name: String, val year: String, val ready: Long,
        val better: Boolean = false, val bar: Double = 0.0, val have: String = "", val found: String = "",
    )

    fun list(): List<Entry> {
        val name = Lib.raw(ID)?.optString("name") ?: return emptyList()
        val arr = runCatching { JSONArray(name) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Entry(it.optString("id"), it.optString("type"), it.optString("name"), it.optString("year"), it.optLong("ready"),
                    it.optBoolean("better"), it.optDouble("bar", 0.0), it.optString("have"), it.optString("found"))
            }
        }.filter { it.id.isNotEmpty() }
    }

    fun has(id: String) = list().any { it.id == id && !it.better }
    fun watchingForBetter(id: String) = list().any { it.id == id && it.better }

    /** Notes what Play best would pick now, then watches for something clearly better (the phone checks). */
    fun addBetter(id: String, type: String, name: String, year: String, done: (String) -> Unit) {
        App.async({ app.stremiolite.StreamParse.bestPlayable(Home.all(), type, id) }) { r ->
            val now = r.getOrNull()
            val have = now?.first?.summary?.replace(Regex("\\s+"), " ")?.trim() ?: "nothing playable yet"
            save(list().filter { it.id != id } + Entry(id, type, name, year, 0, true, now?.second ?: 0.0, have, ""))
            done(have)
        }
    }

    private fun save(list: List<Entry>) {
        val item = Lib.raw(ID) ?: app.stremiolite.Sync.newItem(ID, "other", "[]")
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("year", it.year).put("ready", it.ready)
            if (it.better) o.put("better", true).put("bar", it.bar).put("have", it.have).put("found", it.found)
            arr.put(o)
        }
        item.put("name", arr.toString()).put("removed", true).put("temp", false).put("_mtime", app.stremiolite.Sync.iso(System.currentTimeMillis()))
        Lib.put(item)
    }

    fun add(id: String, type: String, name: String, year: String) {
        save(list().filter { it.id != id } + Entry(id, type, name, year, 0))
        App.toast("Added to Waiting for streams (your phone checks it)")
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        App.toast("Removed from Waiting for streams")
    }

    /** The same followed list New episodes and the Calendar use (with your list rows). */
    private fun following() = NewEpisodes.followedNow()

    fun summary(): String {
        val l = list()
        val ready = l.count { it.ready > 0 }
        val upgrades = l.count { it.ready == 0L && it.better }
        // Ended shows with nothing more coming aren't counted (same as the phone and stick).
        val followed = following()
        val ids = followed.map { it.first }.toSet()
        val infos = Lib.infos().associateBy { it.id }
        val finished = NewEpisodes.shows().filter {
            it.id in ids && app.stremiolite.Following.section(it, Lib.watchedEpisodes(it.id, MetaParse.stremioOrder(it)), infos[it.id]) == app.stremiolite.Following.Section.FINISHED
        }.map { it.id }.toSet()
        val shows = followed.count { it.first !in finished }
        if (l.isEmpty() && shows == 0) return "empty"
        return listOfNotNull(
            if (ready > 0) "$ready ready" else null,
            l.count { it.ready == 0L && !it.better }.takeIf { it > 0 }?.let { "$it waiting" },
            if (upgrades > 0) "$upgrades for better" else null,
            if (shows > 0) "$shows following" else null,
        ).joinToString(" · ") + "  ►"
    }

    fun screen(): Screen {
        val rows = mutableListOf(Row("──── Films ────"))
        // Films you've started (or finished) leave the list.
        val films = list().filter { !it.better && Lib.info(it.id)?.let { i -> i.continuing || i.filmWatched } != true }
            .sortedWith(compareByDescending<Entry> { it.ready }.thenBy { it.name })
        if (films.isEmpty()) rows += Row("None. On a film, choose 'Tell me when a stream is available'.")
        films.forEach { e ->
            rows += Row((if (e.ready > 0) "● " else "") + e.name + if (e.year.isNotEmpty()) " (${e.year})" else "",
                if (e.ready > 0) "ready  ►" else "waiting  ►", opens = { Details.screenFor(e.id, e.type, e.name) })
        }
        val upgrades = list().filter { it.better }
        if (upgrades.isNotEmpty()) {
            rows += Row("──── Better versions ────")
            upgrades.sortedWith(compareByDescending<Entry> { it.ready }.thenBy { it.name }).forEach { e ->
                rows += Row((if (e.ready > 0) "● " else "") + e.name + if (e.year.isNotEmpty()) " (${e.year})" else "",
                    (if (e.ready > 0) "found: ${e.found}" else "have: ${e.have}") + "  ►",
                    opens = { Details.screenFor(e.id, e.type, e.name) })
            }
        }
        val followed = following()
        if (followed.isEmpty()) {
            rows += Row("──── Shows ────")
            rows += Row("None yet. Shows you start watching are followed automatically.")
        } else if (NewEpisodes.shows().isEmpty() && !NewEpisodes.fresh()) {
            // Only when a check is actually due: a fresh-but-empty result would otherwise redraw for ever.
            rows += Row("──── Shows: ${followed.size} followed ────")
            rows += Row("Checking where you're up to…")
            NewEpisodes.load { App.post { if (App.top()?.title == "Waiting for streams" && NewEpisodes.shows().isNotEmpty()) App.replaceTop(screen()) } }
        } else {
            val R = app.stremiolite.Releases
            val coming = NewEpisodes.ensure(followed) { if (App.top()?.title == "Waiting for streams") App.replaceTop(screen()) }
            val infos = Lib.infos().associateBy { it.id }
            val ids = followed.map { it.first }.toSet()
            val shows = NewEpisodes.shows().filter { it.id in ids }
            val watchedOf = shows.associate { it.id to Lib.watchedEpisodes(it.id, MetaParse.stremioOrder(it)) }
            val by = shows.groupBy { app.stremiolite.Following.section(it, watchedOf[it.id] ?: emptySet(), infos[it.id]) }
            fun next(sh: Full) = app.stremiolite.Following.nextText(sh)
            fun section(title: String, list: List<Full>?, detail: (Full) -> String) {
                if (list.isNullOrEmpty()) return
                rows += Row("──── $title ────")
                list.sortedBy { it.name }.forEach { sh -> rows += Row(sh.name, detail(sh) + "  ►", opens = { Details.screenFor(sh.id, "series", sh.name) }) }
            }
            section("Caught up: waiting for more", by[app.stremiolite.Following.Section.CAUGHT_UP]) { next(it) }
            section("Watching", by[app.stremiolite.Following.Section.WATCHING]) { sh ->
                app.stremiolite.Following.progressText(sh, watchedOf[sh.id] ?: emptySet(), infos[sh.id])
            }
            section("Not started", by[app.stremiolite.Following.Section.NOT_STARTED]) { next(it) }
            val pending = followed.filter { it.first in coming }
            if (pending.isNotEmpty()) {
                rows += Row("──── Checking ────")
                pending.forEach { (_, name) -> rows += Row(name, "checking…") }
            }
        }
        rows += Row("Episodes out in the last week", "►", opens = { NewEpisodes.screen() })
        val filmList = films
        if (filmList.isNotEmpty() || followed.isNotEmpty()) rows += Row("────────────────────")
        if (filmList.isNotEmpty()) rows += Row("Clear the films", "►", opens = {
            confirmScreen("Clear the films?", "Stops waiting for all ${filmList.size} films, on every device.", "Yes, clear all ${filmList.size} films") {
                save(list().filter { it.better }); App.toast("Films cleared"); App.back(); App.replaceTop(screen())
            }
        })
        if (followed.isNotEmpty()) rows += Row("Stop following all shows", "►", opens = {
            confirmScreen("Stop following all shows?", "Stops all ${followed.size} shows, on every device. They stay stopped even if you watch more; follow one again from its page.", "Yes, stop all ${followed.size}") {
                Follow.unfollowAll(followed.map { it.first }); App.toast("Stopped following all shows"); App.back(); App.replaceTop(screen())
            }
        })
        val s = Screen("Waiting for streams", rows)
        // Changes made on another device (follows, the waiting list) are fetched on the way in.
        s.onShow = {
            if (System.currentTimeMillis() - Lib.lastPull > 15_000) Lib.pull(force = true) {
                if (App.top()?.title == "Waiting for streams") App.replaceTop(screen())
            }
        }
        return s
    }
}

// ============================================================================================
// New episodes: Library, Continue watching and your watchlist rows (rules shared with Android)
// ============================================================================================

object NewEpisodes {
    private class Found(val show: Full, val ep: Video)

    private var fulls: List<Full> = emptyList()
    private var fullsAt = 0L
    private var followed = 0
    private var checked = 0
    private var loading = false
    private val waiting = mutableListOf<() -> Unit>()
    private var progress: (() -> Unit)? = null

    fun fresh(): Boolean { if (!cacheRead) readCache(); return System.currentTimeMillis() - fullsAt < 60 * 60_000L }

    /** Drops the episode lists (after switching profile). */
    fun forget() { fulls = emptyList(); fullsAt = 0; followed = 0; checked = 0; lastFollowed = emptyList(); cacheRead = false }

    // ---- kept in the profile's folder (shows.json), so a fresh start doesn't refetch every show ----
    private var cacheRead = false

    private fun readCache() {
        cacheRead = true
        val o = Store.readJson("shows.json")
        val arr = o.optJSONArray("shows") ?: return
        if (fulls.isNotEmpty()) return
        fulls = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { MetaParse.full(it, it.optString("source")) } }
        fullsAt = o.optLong("at")
        lastFollowed = o.optJSONArray("followed")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } } } ?: emptyList()
        followed = lastFollowed.size
    }

    private fun writeCache() {
        val shows = fulls; val at = fullsAt; val fol = lastFollowed
        App.async({
            val arr = JSONArray(); shows.forEach { arr.put(MetaParse.compact(it)) }
            val fa = JSONArray(); fol.forEach { fa.put(JSONArray().put(it.first).put(it.second)) }
            Store.writeJson("shows.json", JSONObject().put("at", at).put("shows", arr).put("followed", fa))
        }) { }
    }

    /** Fetches the followed shows' episode lists (at most once an hour), then runs [done]. */
    fun load(force: Boolean = false, onProgress: (() -> Unit)? = null, done: () -> Unit) {
        if (!cacheRead) readCache()
        if (!force && fresh()) { done(); return }
        waiting += done
        if (onProgress != null) progress = onProgress
        if (loading) return
        loading = true
        checked = 0
        val mine = Follow.shows()
        App.async({ Follow.shows(app.stremiolite.Releases.fromPersonalRows(Home.all())).take(60) }) { r ->
            val shows = r.getOrDefault(mine)
            if (r.isSuccess) lastFollowed = shows
            followed = shows.size
            val got = mutableListOf<Full>()
            fun finish() {
                fulls = got.toList(); fullsAt = System.currentTimeMillis(); loading = false; failed.clear()
                writeCache()
                val l = waiting.toList(); waiting.clear(); l.forEach { it() }
            }
            if (shows.isEmpty()) { finish(); return@async }
            shows.forEach { (id, _) ->
                App.async({ MetaParse.fetch(Home.all(), "series", id) }) { rr ->
                    checked++
                    rr.getOrNull()?.let { got += it }
                    progress?.invoke()
                    if (checked >= shows.size) finish()
                }
            }
        }
    }

    fun shows(): List<Full> = fulls
    fun isLoading() = loading

    @Volatile private var lastFollowed: List<Pair<String, String>> = emptyList()

    /** Started and chosen shows (read now) plus list-row shows from the last check, minus stopped ones. */
    fun followedNow(): List<Pair<String, String>> {
        val stopped = Follow.stopped()
        return (Follow.shows() + lastFollowed).distinctBy { it.first }.filter { it.first !in stopped }
    }

    private val fetching = HashSet<String>()
    private val failed = HashSet<String>()

    /** Adds shows followed since the last check without waiting for the hourly refresh; returns those still coming. */
    fun ensure(shows: List<Pair<String, String>>, done: () -> Unit): Set<String> {
        if (loading) return emptySet()
        val have = fulls.map { it.id }.toSet()
        shows.filter { it.first !in have && it.first !in fetching && it.first !in failed }.forEach { (id, _) ->
            fetching += id
            App.async({ MetaParse.fetch(Home.all(), "series", id) }) { r ->
                fetching -= id
                val f = r.getOrNull()
                if (f == null) failed += id else if (fulls.none { it.id == f.id }) { fulls = fulls + f; writeCache() }
                done()
            }
        }
        return fetching.toSet()
    }

    fun recentCount(): Int? = if (fullsAt == 0L) null else fulls.sumOf { app.stremiolite.Releases.recent(it).size }
    fun weekCount(): Int? = if (fullsAt == 0L) null else fulls.sumOf { app.stremiolite.Releases.upcoming(it, 7).size }

    private fun epRow(f: Found, detail: String) = Row(
        f.show.name,
        listOf(MetaParse.epLabel(f.ep), detail, f.ep.title).filter { it.isNotBlank() }.joinToString(" · "),
        opens = { Details.screenFor(f.show.id, "series", f.show.name) },
        play = {
            val w = Details.watchFor(f.show, f.ep)
            App.push(Play.best("series", f.ep.id, "${f.show.name} ${MetaParse.epLabel(f.ep)}", w, Lib.resumeAt(f.show.id, f.ep.id)))
        },
    )

    fun screen(): Screen {
        val status = Row("Finding the shows you follow…")
        lateinit var s: Screen
        fun build() {
            val R = app.stremiolite.Releases
            val list = fulls.flatMap { sh -> R.recent(sh).map { Found(sh, it) } }
                .sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
            status.label = when {
                loading -> "Checking $followed shows… $checked done"
                followed == 0 -> "Start watching a show, or on a show choose 'Tell me about new episodes and seasons', to see its new episodes here."
                list.isEmpty() -> "No new episodes in the last ${R.DAYS} days ($followed shows followed)."
                else -> "Out in the last ${R.DAYS} days · $followed shows followed · p plays"
            }
            val rows = mutableListOf(status)
            list.forEach { f ->
                val watched = if (f.ep.id in Lib.watchedEpisodes(f.show.id, MetaParse.stremioOrder(f.show))) "✓ " else ""
                rows += epRow(f, watched + (if (R.opensSeason(f.ep)) "new season · " else "") + R.dayName(f.ep.released))
            }
            rows += Row("────────────────────")
            rows += Row("Calendar: coming up", "►", opens = { calendar() })
            s.rows = rows
            App.refresh()
        }
        s = Screen("New episodes", listOf(status))
        var started = false
        s.onShow = {
            if (!started) { started = true; load(onProgress = { if (App.isTop(s)) build() }) { build() } }
            build()
        }
        return s
    }

    fun calendar(): Screen {
        val status = Row("Finding the shows you follow…")
        lateinit var s: Screen
        fun build() {
            val R = app.stremiolite.Releases
            val list = fulls.flatMap { sh -> R.upcoming(sh).map { Found(sh, it) } }
                .sortedWith(compareBy<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.season }.thenBy { it.ep.episode })
            status.label = when {
                loading -> "Checking $followed shows… $checked done"
                followed == 0 -> "Start watching a show, or on a show choose 'Tell me about new episodes and seasons', to see what's coming."
                list.isEmpty() -> "Nothing dated in the next 30 days ($followed shows followed)."
                else -> "Next 30 days · $followed shows followed"
            }
            val rows = mutableListOf(status)
            var lastDay = ""
            list.forEach { f ->
                if (f.ep.released != lastDay) { lastDay = f.ep.released; rows += Row("──── ${R.dayName(lastDay)} ────") }
                rows += epRow(f, if (R.opensSeason(f.ep)) "new season" else "")
            }
            s.rows = rows
            App.refresh()
        }
        s = Screen("Calendar", listOf(status))
        var started = false
        s.onShow = {
            if (!started) { started = true; load(onProgress = { if (App.isTop(s)) build() }) { build() } }
            build()
        }
        return s
    }
}

/** Following shows for new episodes and seasons (rules in the shared Following), apart from the Library. */
object Follow {
    private fun record() = Lib.raw(app.stremiolite.Following.ID)
    fun shows(lists: List<Pair<String, String>> = emptyList()) = app.stremiolite.Following.shows(Lib.infos(), record(), lists)
    fun isFollowed(id: String) = app.stremiolite.Following.isFollowed(id, Lib.infos(), record())
    fun stopped() = app.stremiolite.Following.stopped(record())
    fun follow(id: String, name: String) = Lib.put(app.stremiolite.Following.follow(record(), id, name))
    fun unfollow(id: String) = Lib.put(app.stremiolite.Following.unfollow(record(), id))

    /** Stops following all of [ids] in one change. */
    fun unfollowAll(ids: List<String>) {
        var rec = record()
        ids.forEach { rec = app.stremiolite.Following.unfollow(rec, it) }
        rec?.let { Lib.put(it) }
    }
}
