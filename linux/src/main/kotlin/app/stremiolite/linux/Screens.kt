package app.stremiolite.linux

import app.stremiolite.Marks
import app.stremiolite.Addon
import app.stremiolite.Folders
import app.stremiolite.Layout
import app.stremiolite.DetailExtras
import app.stremiolite.RowSource
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
    fun loaded(): List<Addon>? = addons
    fun rowAddons() = Folders.rowAddons(all())
    fun forget() { addons = null; error = null }

    /** Reloads the add-ons from the account now (after rows or streams changed); [done] runs after. */
    fun refresh(done: () -> Unit = {}) {
        val key = Account.authKey ?: return done()
        App.async({
            val json = Stremio.withLiveManifests(Stremio.addonsJson(key))
            json to Stremio.parseAddons(json)
        }) { r ->
            r.onSuccess { (json, list) -> addons = list; error = null; runCatching { Store.file("addons.json").writeText(json) } }
            rebuild?.invoke()
            done()
        }
    }
    fun redraw() { rebuild?.invoke() }

    fun screen(): Screen {
        lateinit var s: Screen
        val status = Row("")
        var placed = false
        var notices = 0
        fun build() {
            val list = addons
            val rows = mutableListOf<Row>()
            if (Prefs.onHome("notices")) {
                Waiting.homeRow()?.let { rows += it }
                NewEpisodes.homeRow()?.let { rows += it }
            }
            Updates.homeRow()?.let { rows += it }
            StreamAlert.homeRow()?.let { rows.add(0, it) }
            // A notice you've just opened has gone: back to Search, not the line that moved up.
            if (rows.size < notices) placed = false
            notices = rows.size
            if (rows.isNotEmpty()) rows += Row("────────────────────")
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
            rows += Row("──── Watching ────")
            val searchRow = Row("Search", "/  ►", opens = null, action = { Search.start() })
            if (!KidsGuard.hides() && Prefs.onHome("search")) rows += searchRow
            NowPlaying.row()?.let { rows += it }
            if (Prefs.onHome("continue")) rows += Row("Continue watching", if (cw.isEmpty()) "nothing yet" else "${cw.size}  ►", opens = { continueScreen() })
            if (Prefs.onHome("next")) rows += Row("Next watch", NextList.summary(), opens = { NextList.screen() })
            if (Prefs.onHome("ready")) rows += Row("Ready", Ready.summary(), opens = { Ready.screen() })
            if (Prefs.onHome("waiting")) rows += Row("Waiting", Waiting.summary(), opens = { Waiting.screen() })
            if (Prefs.onHome("calendar")) rows += Row("Calendar", NewEpisodes.weekCount()?.let { if (it == 0) "nothing this week  ►" else "$it this week  ►" } ?: "", opens = { NewEpisodes.calendar() })
            if (Prefs.onHome("watched")) rows += Row("Watched", Watched.list().size.let { if (it == 0) "" else "$it  ►" }, opens = { watchedScreen() })
            if (Prefs.onHome("favourites")) rows += Row("Favourites", if (lib.isEmpty()) "empty" else "${lib.size}  ►", opens = { libraryScreen() })
            if (Prefs.onHome("basket")) rows += Row("Basket", BasketList.summary(), opens = { BasketList.screen() })
            rows += ForYouUi.homeRows()
            if (list != null) {
                rows += Row("──── Browse ────")
                val entries = Index.entries(list)
                val main = entries.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
                val front = Index.front(entries)
                val coming = if (Prefs.comingOn && !KidsGuard.hides()) app.stremiolite.ComingSoonPlace.after(front.map { it.name }) else -1
                front.forEachIndexed { n, e ->
                    if (n == coming) rows += Row("Coming soon", "films & shows  ►", opens = { Feeds.comingSoon() })
                    val tag = if (e.addon === main) "" else e.addon.name.substringBefore('|').trim().take(18) + " · "
                    rows += Row(e.name, tag + MetaParse.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(e.addon, e.catalog) },
                        play = null)
                }
                if (coming >= 0 && coming == front.size) rows += Row("Coming soon", "films & shows  ►", opens = { Feeds.comingSoon() })
                val folders = Index.folderRows(entries)
                if (folders.isNotEmpty()) {
                    rows += Row("──── Index ────")
                    rows += folders
                }
            }
            rows += Row("──── App ────")
            // Same order and wording as the phone and stick.
            if (Prefs.liveTv && !KidsGuard.hides()) rows += Row("Live TV", Live.homeDetail(), opens = { Live.screen() })
            if (Prefs.onHome("drives")) Drives.homeRow()?.let { rows += it }
            if (Prefs.onHome("downloads")) Offline.homeRow()?.let { rows += it }
            if (Prefs.onHome("playlink")) rows += PlayLink.homeRow()
            if (KidsGuard.locked()) rows += Row("Games", "►", opens = { GamesScreen.screen() })
            if (Prefs.newsOn && !KidsGuard.hides()) {
                rows += Row("News", "film & TV headlines  ►", opens = { Feeds.news() })
            }
            if (Prefs.sleepOnHome) rows += SleepTimer.row()
            // Setup items (add-ons, export/import, device check, about) live inside Settings.
            rows += Row("Settings", "►", action = { KidsGuard.guard { App.push(Settings.screen()) } })
            rows += Row("──── Profile ────")
            rows += Row(Profiles.current()?.name ?: "…", if (KidsGuard.locked()) "current · locked" + (KidsGuard.timeSummary()?.let { " · $it" } ?: "") else "current")
            rows += Row("Switch profile", "►", action = { KidsGuard.guard { App.push(Profiles.chooser()) } })
            rows += Row("Sign out of this profile", action = { KidsGuard.guard { App.push(signOut()) } })
            s.rows = rows
            // Open on Search, not on the "Watching" header above it.
            if (!placed) { placed = true; s.moveTo = rows.indexOf(searchRow).takeIf { it >= 0 } ?: (rows.indexOfFirst { it.label == "──── Watching ────" } + 1).takeIf { it in 1 until rows.size } ?: 0 }
            App.refresh()
        }
        rebuild = { build() }
        s = Screen("Stremonino", listOf(Row("Loading…")), tag = Profiles.current()?.name ?: "Linux")
        s.onShow = {
            Offline.kick()
            if (addons == null) {
                runCatching { Stremio.parseAddons(Store.file("addons.json").readText()) }.onSuccess { addons = it }
                load()
            }
            App.later(8_000) { if (App.isTop(s)) SpeedCheck.maybe() }
            Lib.pull { build() }
            Updates.check { build() }
            App.later(3_000) { if (App.isTop(s)) StreamAlert.maybe { build() } }
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
                if (force) { Lib.pull(full = true) { rebuild?.invoke() }; StreamAlert.maybe(force = true) { rebuild?.invoke() } }
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
            Row(Marks.title(Catalogs.mark(i.id, i.type) ?: Marks.FAVOURITE, i.name), MetaParse.typeLabel(i.type) + "  ►", opens = { Details.screenFor(i.id, i.type, i.name) })
        }
        val clear = if (rows.isEmpty()) emptyList() else listOf(
            Row("────────────────────"),
            Row("Clear Favourites", "►", opens = {
                confirmScreen("Clear Favourites?", "Removes all ${rows.size} from your Favourites on every device (Stremio's own apps call it the Library). History and watched marks stay.", "Yes, clear all ${rows.size}") {
                    Lib.clearLibrary(); App.toast("Favourites cleared"); App.resetTo(Home.screen())
                }
            }),
        )
        return Screen("Favourites", rows.ifEmpty { listOf(Row("No favourites yet. Use \"Add to favourites\" on any film or show.")) } + clear)
    }

    private fun watchedScreen(): Screen {
        lateinit var s: Screen
        val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        fun build() {
            val list = Watched.list()
            val stats = Row("Your stats", "►", opens = { statsScreen() })
            s.rows = if (list.isEmpty()) listOf(stats, Row("Empty."))
            else listOf(stats) + list.map { e ->
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

    /** Watched › Your stats: time and counts from the account (all devices), and your most-watched. */
    private fun statsScreen(): Screen {
        val st = app.stremiolite.WatchStats.compute(Lib.infos())
        val rows = st.lines.map { Row(info(it.first, it.second)) }.toMutableList()
        if (st.shows.isNotEmpty()) { rows += Row("── Most-watched shows ──"); st.shows.forEach { t -> rows += Row(t.name, t.detail + "  ►", opens = { Details.screenFor(t.id, t.type, t.name) }) } }
        if (st.films.isNotEmpty()) { rows += Row("── Films watched again ──"); st.films.forEach { t -> rows += Row(t.name, t.detail + "  ►", opens = { Details.screenFor(t.id, t.type, t.name) }) } }
        return Screen("Your stats", rows)
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

    fun about() = Screen(
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
    fun entries(list: List<Addon>): List<Folders.Entry> = Folders.entries(list, Prefs.hiddenRows, Layout.parse(Prefs.layout))

    fun front(entries: List<Folders.Entry>) = Layout.front(entries, Layout.parse(Prefs.layout))

    fun folderRows(entries: List<Folders.Entry>): List<Row> =
        Layout.tree(entries, Layout.parse(Prefs.layout)).folders.map { n -> Row(n.name, "${n.count}  ►", opens = { folder(n) }) }

    private fun folder(n: Layout.Node): Screen {
        val main = n.rows.groupingBy { it.addon }.eachCount().maxByOrNull { it.value }?.key
        val subs = n.folders.map { f -> Row(f.name, "${f.count}  ►", opens = { folder(f) }) }
        val direct = n.rows.map { e ->
            val tag = if (e.addon === main) "" else e.addon.name.substringBefore('|').trim().take(18) + " · "
            Row(e.name, tag + MetaParse.typeLabel(e.catalog.type) + "  ►", opens = { Catalogs.screen(e.addon, e.catalog, e.name) })
        }
        val title = Layout.parts(n.path).joinToString(" · ")
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
        return "${m.year.take(9).padEnd(9)} ${rating.padEnd(6)} ${app.stremiolite.AgeRatings.label(m.id).padEnd(8)} ${kind.padEnd(6)}"
    }

    fun mark(m: Meta): String? = mark(m.id, m.type)

    /** The status shape for a title ([Marks]): watched, continuing, its stream, or saved. */
    fun mark(id: String, type: String): String? {
        val info = Lib.info(id)
        val w = Waiting.list().firstOrNull { it.id == id }
        return Marks.pick(
            watched = type == "movie" && Lib.filmWatched(id), continuing = info?.continuing == true,
            ready = w != null && w.ready > 0, better = w != null && w.better, waiting = w != null || Follow.followsFilm(id),
            library = info?.inLibrary == true, next = NextList.has(id),
            following = type == "series" && Follow.isFollowed(id), basket = BasketList.has(id),
        )
    }

    fun row(a: Addon?, m: Meta): Row = Row(
        Marks.title(mark(m), m.name), detail(m),
        opens = { Details.screen(a, m) },
        // p (Play) puts a title in the basket while browsing; Enter opens it.
        play = PlayKind.basket { BasketList.toggle(m.id, m.type, m.name, m.year) },
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
        var pending: Int? = App.place(placeKey)?.takeIf { it in 2..400 }
        // Row 0 is "About this row"; titles start at row 1.
        val about = Row("About this row", "►", opens = { aboutRow(a, c) })

        // "Watched films in lists: Hide them" leaves out films marked watched.
        fun visible(): List<Meta> = (if (Prefs.hideWatched) items.filter { !(it.type == "movie" && Lib.filmWatched(it.id)) } else items)
            .let { l -> if (KidsGuard.ageLimited()) l.filter { KidsGuard.allows(it.id) } else l }

        fun rebuild() {
            val shown = visible()
            val rows = shown.mapTo(ArrayList<Row>(shown.size + 3).apply { add(about) }) { row(a, it) }
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
                    want <= shown.size -> { s.moveTo = want; pending = null }
                    ended || error != null -> { s.moveTo = shown.size.coerceAtLeast(1); pending = null }
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
                    RatingFill.fill(s, items) { if (App.inStack(s)) rebuild() }
                }
            }
        }

        s = Screen(title, listOf(about, Row("Loading…")), tag = MetaParse.typeLabel(c.type))
        s.key = placeKey
        s.pos = 1
        s.onShow = { if (items.isEmpty() && !loading && error == null && !ended) loadMore() else if (items.isNotEmpty()) rebuild() }
        s.onSelect = { pos -> if (pos >= visible().size + 1 - LOAD_AHEAD && error == null) loadMore() }
        return s
    }

    /** Which add-on a row comes from and a guess at the list behind it. */
    private fun aboutRow(a: Addon, c: Addon.Catalog): Screen {
        val r = RowSource.about(a.name, c)
        return Screen("About: ${c.name}", listOf(
            Row(info("From", r.addon)),
            Row(info("Row ID", r.id)),
            Row(info("Source", r.source)),
            Row(info("Type", r.type)),
        ))
    }
}

// ============================================================================================
// Search
// ============================================================================================

object Search {
    fun start() {
        if (Account.authKey == null) return
        val ai = Prefs.geminiKey.isNotBlank()
        App.ask(if (ai) "Search (start with \"ai\" to ask AI, e.g. ai 90s heist films)" else "Search") { q ->
            val t = q?.trim().orEmpty()
            when {
                t.isBlank() -> Unit
                ai && Regex("^ai\\s+", RegexOption.IGNORE_CASE).containsMatchIn(t) -> App.push(aiResults(t.replace(Regex("^ai\\s+", RegexOption.IGNORE_CASE), "")))
                else -> App.push(results(t))
            }
        }
    }

    /** "Ask AI": AIOMetadata's AI Search with your Gemini key. */
    private fun aiResults(query: String): Screen {
        val s = Screen("Ask AI", listOf(Row("\"$query\""), Row("Asking AI… (this can take a few seconds)")))
        fun show(list: List<Pair<Addon, Meta>>?, error: String?) {
            val rows = mutableListOf(Row("\"$query\""), Row("────────────────────"))
            when {
                error != null -> { rows += Row("Couldn't ask: $error"); rows += Row("Check my AI setup", "►", opens = { AiCheck.screen() }) }
                list.isNullOrEmpty() -> rows += Row("AI found nothing for that. Try describing it differently.")
                else -> list.forEach { (a, m) -> rows += Catalogs.row(a, m) }
            }
            s.rows = rows
            App.refresh()
        }
        App.async({ app.stremiolite.AiSearch.ask(Home.all(), query) }) { r ->
            r.onSuccess { list ->
                show(list, null)
                RatingFill.fill(s, list.map { it.second }) { if (App.inStack(s)) show(list, null) }
            }.onFailure { show(null, it.message ?: Net.describe(it)) }
        }
        return s
    }

    private fun results(q: String): Screen {
        lateinit var s: Screen
        val sources = Folders.rowAddons(Home.all()).filter { !it.isLocal }.flatMap { a -> a.catalogs.filter { it.searchable && !app.stremiolite.AiSearch.isAiCatalog(it) }.map { a to it } }
        val found = LinkedHashMap<String, Pair<Addon, Meta>>()
        val collections = HashSet<String>()
        var answered = 0
        var channels: List<app.stremiolite.LiveTv.Channel> = emptyList()
        var rated = false
        fun row(p: Pair<Addon, Meta>): Row {
            val (a, m) = p
            return if (m.id in collections) Row(m.name, listOf(m.year, "Collection").filter { it.isNotBlank() }.joinToString("  ") + "  ►", opens = { Details.screen(a, m) })
            else Catalogs.row(a, m)
        }
        fun build() {
            val rows = mutableListOf<Row>()
            rows += Row(if (answered < sources.size) "Searching… ${answered} of ${sources.size}" else "${found.size} results for \"$q\"")
            if (channels.isNotEmpty()) {
                rows += Row("──── Live TV ────")
                channels.take(5).forEach { rows += Live.searchRow(it) }
                if (channels.size > 5) rows += Row("All matching channels", "${channels.size}  ►", opens = { Screen("Channels: $q", channels.map { Live.searchRow(it) }) })
            }
            val g = SearchRank.group(found.values.toList().filter { KidsGuard.allows(it.second.id) }, q, { it.second }) { it.second.id in collections }
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
            if (answered >= sources.size && found.isEmpty() && channels.isEmpty() && sources.isNotEmpty()) rows += Row("Nothing found.")
            if (answered >= sources.size && !rated) { rated = true; RatingFill.fill(s, found.values.map { it.second }) { if (App.inStack(s)) build() } }
            s.rows = rows
            App.refresh()
        }
        s = Screen("Search: $q", listOf(Row("Searching…")))
        if (sources.isEmpty()) s.rows = listOf(Row("None of your add-ons can search."))
        Live.search(q) { channels = it; build() }
        sources.forEach { (a, c) ->
            val url = a.base + "/catalog/" + Net.enc(c.type) + "/" + Net.enc(c.id) + "/search=" + Net.enc(q) + ".json"
            App.async({ MetaParse.parse(Http.get(url)) }) { r ->
                answered++
                r.getOrNull()?.forEach { m ->
                    if (found.putIfAbsent(m.type + ":" + m.id, a to m) == null && SearchRank.isCollection(m, a.name, c.type)) collections += m.id
                }
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
        val what = if (v != null) " ${MetaParse.epLabel(v)}" + (if (v.title.isNotBlank()) "  ${v.title}" else "") else ""
        if (at > 0) {
            rows += Row("Resume$what from ${clock(at)}", "►", opens = { Play.best(f.type, w.videoId, t, w, at) })
            rows += Row("Play$what from the start", opens = { Play.best(f.type, w.videoId, t, w, 0) })
        } else {
            rows += Row("Play best$what", "►", opens = { Play.best(f.type, w.videoId, t, w, 0) })
        }
        rows += Row("Choose a stream", opens = { Play.list(f.type, w.videoId, t, w, at) })
        TvRemote.sendRow(f, v)?.let { rows += it }
        rows += Row("Download", if (Offline.has(w.videoId)) "in Downloads  ►" else "►", opens = { if (Offline.has(w.videoId)) Offline.screen() else Offline.chooseScreen(w, t) })
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
            rows += Row("Download episodes", "choose  ►", opens = { Offline.episodesScreen(f, { v -> watchFor(f, v) }, { v -> upcoming(v) }) })
        } else if (f.videos.isNotEmpty()) {
            // A film collection: each part is a film of its own, in release order, with ✓ on the ones watched.
            rows += Row("Films in this collection: ${f.videos.size}")
            rows += Row("────────────────────")
            SearchRank.parts(f.videos).forEach { v ->
                val name = v.title.ifEmpty { v.id }
                val own = v.id.startsWith("tt") || v.id.startsWith("tmdb:")
                rows += Row(Marks.title(Catalogs.mark(v.id, "movie"), name), v.released.take(4) + "  ►",
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
        // Your own history with it first, then the details.
        DetailExtras.history(Lib.info(f.id)).let { h ->
            h.forEach { (k, v) -> rows += Row(info(k, v)) }
            if (h.isNotEmpty()) rows += Row("────────────────────")
        }
        val cols = App.columns()
        rows += Row(info("Type", if (collection) "Collection" else if (f.type == "movie") "Movie" else if (f.type == "series") "Series" else f.type))
        if (f.year.isNotBlank()) rows += Row(info("Year", f.year))
        val scores = app.stremiolite.Ratings.known(f.id)
        if (scores != null && (scores.scores.isNotEmpty() || scores.overall > 0)) app.stremiolite.Ratings.lines(scores, Prefs.ratingsConfig(), maxOf(16, cols - 20)).forEachIndexed { i, l -> rows += Row(info(if (i == 0) "Ratings" else "", l)) }
        else if (f.rating.isNotBlank()) rows += Row(info("IMDb rating", "★ ${f.rating}"))
        app.stremiolite.Ratings.ageLine(f.certification, scores).takeIf { it.isNotEmpty() }?.let { rows += Row(info("Age rating", it)) }
        if (f.runtime.isNotBlank()) rows += Row(info("Runtime", f.runtime))
        if (f.genres.isNotEmpty()) rows += infoRows("Genres", f.genres.joinToString(", "), cols)
        if (f.director.isNotEmpty()) rows += infoRows("Director", f.director.joinToString(", "), cols)
        if (f.cast.isNotEmpty()) rows += infoRows("Cast", f.cast.take(5).joinToString(", "), cols)
        DetailExtras.facts(f, DetailExtras.today()).filter { it.first != "Age rating" }.forEach { (k, v) -> rows += infoRows(k, v, cols) }
        if (f.imdbUrl.isNotBlank()) rows += Row("IMDb page", "opens browser  ►", action = { WebLink.open(f.imdbUrl) })
        Trailers.row(f)?.let { rows += it }
        rows += watchRows(f)
        if (f.description.isNotBlank()) {
            rows += Row("────────────────────")
            MetaParse.wrap(f.description, App.columns()).forEach { rows += Row(it) }
        }
        rows += Row("────────────────────")
        rows += Row(info("Details from", f.source.substringBefore('|').trim()))
        rows += moreLikeThis(f)
        return rows
    }

    /** "More like this" at the bottom of a title's page (TMDB's recommendations; needs the key). */
    private fun moreLikeThis(f: Full): List<Row> {
        if ((f.type != "movie" && f.type != "series") || Prefs.tmdbKey.isBlank()) return emptyList()
        val head = Row("──── More like this ────")
        val list = app.stremiolite.ForYou.knownSimilar(f.id) ?: return listOf(head, Row("Finding similar titles…"))
        val allowed = list.filter { KidsGuard.allows(it.id) }
        if (allowed.isEmpty()) return listOf(head, Row("Nothing similar found."))
        return listOf(head) + allowed.map { p ->
            Row(Marks.title(Catalogs.mark(p.id, p.type), p.name) + if (p.year.isNotEmpty()) " (${p.year})" else "", MetaParse.typeLabel(p.type) + "  ►",
                opens = { screenFor(p.id, p.type, p.name) }, play = PlayKind.basket { BasketList.toggle(p.id, p.type, p.name, p.year) })
        }
    }

    private fun lookUpRatings(f: Full, s: Screen) {
        val key = Prefs.mdblistKey
        if (key.isBlank() || !f.id.startsWith("tt") || app.stremiolite.Ratings.known(f.id) != null) return
        App.async({ app.stremiolite.Ratings.load(key, f.type, f.id) }) { r ->
            if (r.getOrNull() != null && App.inStack(s)) { s.rows = rows(f); if (App.isTop(s)) App.refresh() }
        }
    }

    private fun lookUpSimilar(f: Full, s: Screen) {
        val key = Prefs.tmdbKey
        if (key.isBlank() || (f.type != "movie" && f.type != "series") || app.stremiolite.ForYou.knownSimilar(f.id) != null) return
        App.async({ app.stremiolite.ForYou.findSimilar(key, f.type, f.id, f.name) }) { if (App.inStack(s)) { s.rows = rows(f); if (App.isTop(s)) App.refresh() } }
    }

    private fun libraryRows(f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Ready.rowsFor(f)
        lateinit var lib: Row
        lib = Row(if (Lib.inLibrary(f.id)) "♡ Remove from favourites" else "Add to favourites", action = {
            val added = Lib.toggleLibrary(f.id, f.type, f.name)
            lib.label = if (added) "♡ Remove from favourites" else "Add to favourites"
            App.toast(if (added) "Added to favourites" + (if (f.type == "series") ": following new episodes" else "") else "Removed from favourites")
        })
        rows += BasketList.row(f)
        rows += lib
        NextList.row(f)?.let { rows += it }
        if (f.type == "series") {
            lateinit var fol: Row
            val FO = app.stremiolite.Following
            fun folLabel() = when {
                !Follow.isFollowed(f.id) -> "Follow: tell me about new episodes or seasons"
                Follow.mode(f.id) == FO.EPISODES -> "Following: new episodes and seasons ✓ (Enter: seasons only)"
                else -> "Following: new seasons only ✓ (Enter: stop)"
            }
            fol = Row(folLabel(), action = {
                when {
                    !Follow.isFollowed(f.id) -> { Follow.follow(f.id, f.name, FO.EPISODES); App.toast("Following ${f.name}: new episodes and seasons come to Ready") }
                    Follow.mode(f.id) == FO.EPISODES -> { Follow.follow(f.id, f.name, FO.SEASONS); App.toast("Following ${f.name}: only a new season comes to Ready") }
                    else -> { Follow.unfollow(f.id); App.toast("Stopped following ${f.name}") }
                }
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
            fun label() = when {
                Follow.followsFilm(f.id) -> "Waiting until it's out to stream ✓ (stop)"
                Waiting.has(f.id) -> "Stop waiting for a stream"
                else -> "Tell me when a stream is available"
            }
            wait = Row(label(), action = {
                when {
                    Follow.followsFilm(f.id) -> { Follow.unfollowFilms(listOf(f.id)); App.toast("Stopped following ${f.name}") }
                    Waiting.has(f.id) -> Waiting.remove(f.id)
                    else -> {
                        App.toast("Checking…")
                        App.async({ app.stremiolite.FilmOut.check(Home.all(), Prefs.tmdbKey, Prefs.watchRegion, f.id) }) { r ->
                            val (out, note) = r.getOrDefault(true to "")
                            if (out) Waiting.add(f.id, f.type, f.name, f.year)
                            else {
                                Follow.followFilms(listOf(app.stremiolite.Following.Film(f.id, f.name, f.year, note)))
                                App.toast("Not out to stream yet ($note): added to Waiting")
                            }
                            wait.label = label(); App.refresh()
                        }
                    }
                }
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
            if (f != null) { s.rows = rows(f); App.refresh(); lookUpWatch(f, s); lookUpSimilar(f, s); lookUpRatings(f, s) }
            else if (!started) {
                started = true
                App.async({ fetch(from, item.type, item.id) }) { r ->
                    r.onSuccess { loaded ->
                        full = loaded
                        s.rows = rows(loaded)
                        lookUpWatch(loaded, s); lookUpSimilar(loaded, s); lookUpRatings(loaded, s)
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

    /** Where to watch: how it was first released and where it's on now (needs your TMDB key). */
    private fun watchRows(f: Full): List<Row> {
        val W = app.stremiolite.WhereToWatch
        if (f.type != "movie" && f.type != "series") return emptyList()
        val region = Prefs.watchRegion
        val where = W.regionName(region)
        val head = Row("──── Where to watch ($where) ────")
        if (Prefs.tmdbKey.isBlank()) return listOf(head, Row("Add a TMDB key to see this", "►", opens = { whereToWatchSettings() }))
        val w = W.known(f.type, f.id, region)
            ?: return listOf(head, Row(W.error(f.type, f.id, region)?.let { "Couldn't look it up: $it" } ?: "Looking it up…"))
        val rows = mutableListOf(head)
        val cols = App.columns()
        // "UK cinemas  14 Aug 2026": the label is where, so it lines up with the others.
        w.released.forEach { (k, v) -> rows += infoRows(k, v, cols) }
        fun line(label: String, names: List<String>) { if (names.isNotEmpty()) rows += infoRows(label, names.joinToString(", "), cols) }
        line("Included with", w.included)
        line("Free", w.free)
        line("Rent", w.rent)
        line("Buy", w.buy)
        if (w.included.isEmpty() && w.free.isEmpty() && w.rent.isEmpty() && w.buy.isEmpty()) rows += Row("Not on any $where service just now.")
        if (w.link.isNotBlank()) rows += Row("All the options", "opens browser  ►", action = { WebLink.open(w.link) })
        MetaParse.wrap("Where-to-watch data from JustWatch, via TMDB.", cols).forEach { rows += Row(it) }
        return rows
    }

    private fun lookUpWatch(f: Full, s: Screen) {
        val W = app.stremiolite.WhereToWatch
        val key = Prefs.tmdbKey
        val region = Prefs.watchRegion
        if (key.isBlank() || (f.type != "movie" && f.type != "series") || W.known(f.type, f.id, region) != null) return
        App.async({ W.load(key, f.type, f.id, f.imdbUrl, region) }) { if (App.isTop(s)) { s.rows = rows(f); App.refresh() } }
    }

    /** Settings → Where to watch: your TMDB key and the country. */
    fun whereToWatchSettings(): Screen {
        val W = app.stremiolite.WhereToWatch
        val region = Row("Country", "◄ ${W.regionName(Prefs.watchRegion)} ►")
        region.action = {
            Prefs.watchRegion = if (Prefs.watchRegion == "GB") "US" else "GB"; Prefs.syncOut()
            region.detail = "◄ ${W.regionName(Prefs.watchRegion)} ►"; App.refresh()
        }
        return RowsSetup.keyScreen("TMDB key", W.KEY_PAGE, listOf(
            "Shows how a title was first released (cinemas, Netflix, TV…) and where to watch it now,",
            "on its details page. Free TMDB account, then Settings › API: copy the API key",
            "(or the longer read access token). It follows your account.",
        ), { Prefs.tmdbKey }, { Prefs.tmdbKey = it; Prefs.syncOut() }, top = listOf(region, Row("")))
    }

    private fun episodes(f: Full, season: Int, eps: List<Video>): Screen {
        val s = Screen("${f.name} · Season $season", emptyList())
        fun build() {
            val done = Lib.watchedEpisodes(f.id, MetaParse.stremioOrder(f))
            val info = Lib.info(f.id)
            s.rows = eps.map { v ->
                val mark = when {
                    v.id in done -> "✓ "
                    info?.continuing == true && info.videoId == v.id -> "► "
                    else -> "  "
                }
                val when_ = listOf(if (upcoming(v)) "out ${v.released}" else v.released, app.stremiolite.Ratings.episodeShort(v.id, Prefs.ratingsConfig())).filter { it.isNotEmpty() }.joinToString("   ")
                Row(mark + MetaParse.epLabel(v) + "  " + v.title, when_ + "  ►", opens = { episode(f, v) }, play = {
                    val w = watchFor(f, v)
                    App.push(Play.best(f.type, w.videoId, title(f, v), w, Lib.resumeAt(f.id, w.videoId)))
                })
            }
        }
        build()
        s.onShow = { build(); App.refresh() }
        s.key = "eps:${f.id}:$season"
        val tmdb = Prefs.tmdbKey
        if (tmdb.isNotBlank() && f.id.startsWith("tt") && !app.stremiolite.Ratings.triedSeason(f.id, season))
            App.async({ app.stremiolite.Ratings.season(tmdb, f.id, season) }) { r -> if (r.getOrNull() == true) { build(); if (App.isTop(s)) App.refresh() } }
        return s
    }

    fun episode(f: Full, v: Video): Screen {
        val s = Screen("${f.name} ${MetaParse.epLabel(v)}", emptyList())
        s.onShow = {
            val rows = mutableListOf<Row>()
            if (upcoming(v)) rows += Row("Not out yet: ${v.released}")
            rows += playRows(f, v)
            rows += Row("────────────────────")
            if (v.title.isNotBlank()) rows += Row(v.title)
            if (v.released.isNotBlank()) rows += Row(info("Released", v.released))
            app.stremiolite.Ratings.knownEpisode(v.id)?.let { rows += Row(info("Rating", app.stremiolite.Ratings.episodeLine(it, Prefs.ratingsConfig()))) }
            if (Prefs.tmdbKey.isNotBlank() && !app.stremiolite.Ratings.triedEpisode(v.id)) {
                val key = Prefs.tmdbKey
                App.async({ app.stremiolite.Ratings.episode(key, v.id) }) { r -> if (r.getOrNull() != null && App.isTop(s)) s.onShow?.invoke() }
            }
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
                Row("──── Playback ────"),
                Row("Player", Prefs.player, action = {
                    App.ask("Player command (e.g. mpv)", Prefs.player) { v -> if (!v.isNullOrBlank()) { Prefs.player = v.trim(); build() } }
                }),
                Row("Player options", Prefs.playerArgs.ifEmpty { "none" }, action = {
                    App.ask("Extra options (e.g. --fs --volume=80)", Prefs.playerArgs) { v -> if (v != null) { Prefs.playerArgs = v.trim(); build() } }
                }),
                Row("Subtitles from", Stremio.subtitleAddons(Home.all()).map { it.name.substringBefore('|').trim() }.ifEmpty { null }?.joinToString(", ") ?: "none: add OpenSubtitles (free)  ►", action = {
                    if (Stremio.subtitleAddons(Home.all()).isNotEmpty()) App.toast("Subtitles come from your subtitle add-ons")
                    else {
                        val key = Account.authKey
                        if (key == null) App.toast("Sign in first")
                        else { App.toast("Adding OpenSubtitles…"); App.async({ Stremio.installAddon(key, Stremio.OPENSUBTITLES) }) { r ->
                            if (r.isFailure) App.toast("Couldn't add it") else Home.refresh { App.toast("OpenSubtitles added"); build() }
                        } }
                    }
                }),
                Row("Subtitle language", "◄ ${Prefs.language().name} ►", action = {
                    Prefs.subLang = (Prefs.subLang + 1) % app.stremiolite.Languages.all.size; Prefs.syncOut(); build()
                }),
                Row("Auto-play next episode", "◄ ${if (Prefs.autoNext) "On" else "Off"} ►", action = {
                    Prefs.autoNext = !Prefs.autoNext; Prefs.syncOut(); build()
                }),
                Row("Skip intros and recaps (mpv)", "◄ ${if (Prefs.skipIntros) "On" else "Off"} ►", action = {
                    Prefs.skipIntros = !Prefs.skipIntros; build()
                }),
                SleepTimer.row(),
                Row("Play on a TV on my Wi-Fi (DLNA)", "◄ ${if (Prefs.castDlna) "On" else "Off: mpv"} ►", action = { Prefs.castDlna = !Prefs.castDlna; build() }),
                Row("Play what's sent to the TV", "◄ ${if (Prefs.receiveSends) "On: this computer too" else "Off"} ►", action = {
                    Prefs.receiveSends = !Prefs.receiveSends; build()
                }),
                Row("──── Alerts ────"),
                Row("Follow shows I start watching", "◄ ${if (Prefs.autoFollow) "On" else "Off: I choose"} ►", action = {
                    Prefs.autoFollow = !Prefs.autoFollow; Prefs.syncOut(); build()
                }),
                Row("──── Drives ────"),
                Drives.settingsRow(),
                Offline.settingsRow(),
                Row("──── Home ────"),
                Row("Show on Home", "►", opens = { homeLines() }),
                Row("Arrange your Index", "►", opens = { Arrange.screen() }),
                Row("Hide rows", "►", opens = { rowsScreen() }),
                Row("──── Look ────"),
                Row("Ratings", "which scores, order  ►", opens = { ratingsScreen() }),
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
                Row("Watched films in lists", "◄ ${if (Prefs.hideWatched) "Hide them" else "Show with ●"} ►", action = {
                    Prefs.hideWatched = !Prefs.hideWatched; Prefs.syncOut(); build()
                }),
                Row("──── Configuration ────"),
                Row("Set up Stremonino", "step by step  ►", opens = { SetupWizard.screen() }),
                Row("Reload my rows and add-ons", action = { Home.load(force = true); App.toast("Reloading your rows and add-ons…") }),
                Row("Your rows (AIOMetadata)", "►", opens = { RowsSetup.screen() }),
                Row("Your streams (AIOStreams)", "►", opens = { StreamsSetup.screen() }),
                Row(info("Stream speed", SpeedCheck.summary()), "►", opens = { SpeedScreen.screen() }),
                Row("Your add-ons", (Home.all().size.takeIf { it > 0 }?.let { "$it  " } ?: "") + "►", opens = { Addons.screen() }),
                Row("Your keys", "TMDB, debrid, MDBList, Gemini  ►", opens = { keysScreen() }),
                Row("──── Share ────"),
                Row("Share Stremonino", "download links  ►", opens = { AppShare.screen() }),
                Row("Export my setup to another account", "►", opens = { Share.screen() }),
                Row("Import a setup from another account", "►", opens = { Share.importScreen() }),
                Row("──── About ────"),
                Row(info("App version", "build ${Updates.current}")),
                Row("Check for updates", action = { Updates.checkNow() }),
                Row("Checks", "►", opens = { Screen("Checks", listOf(
                    Row("Is everything working?", "check it all  ►", opens = { HealthScreen.screen() }),
                    Row("Device check", "►", opens = { DeviceCheck.screen() }),
                )) }),
                Row("About Stremonino", "►", opens = { Home.about() }),
            )
            App.refresh()
        }
        s = Screen("Settings", emptyList())
        build()
        s.pos = 1
        return s
    }

    private fun rowsScreen(): Screen {
        lateinit var s: Screen
        fun build() {
            val pairs = Folders.rowAddons(Home.all()).filter { !it.isLocal }.flatMap { a -> a.catalogs.filter { Folders.openable(it) }.map { a to it } }
            val hidden = Prefs.hiddenRows
            val rows = mutableListOf(
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

    /** The list without films that are done with ([app.stremiolite.WaitRules]); saved back if any left. */
    fun current(): List<Entry> {
        val all = list()
        val keep = all.filter { e ->
            val i = Lib.info(e.id)
            !app.stremiolite.WaitRules.done(e.better, e.ready, i?.continuing == true, i?.filmWatched == true,
                i?.let { maxOf(it.lastWatched, it.updated) } ?: 0L)
        }
        if (keep.size != all.size) save(keep)
        return keep
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

    /** Play best found nothing that plays for a Next watch film: back into Waiting, not ready. */
    fun unplayable(id: String, name: String) {
        val have = list()
        val e = have.firstOrNull { it.id == id }
        save(if (e == null) have + Entry(id, "movie", name, "", 0)
             else have.map { if (it.id == id) Entry(it.id, it.type, it.name, it.year, 0, it.better, it.bar, it.have, "") else it })
        app.stremiolite.NextStatus.forget(id)
    }

    /** Followed films now out to stream move here (the phone then checks them); the rest get a fresh note. */
    fun checkFollowedFilms(done: () -> Unit) {
        val films = Follow.films()
        if (films.isEmpty()) return
        val addons = Home.all(); val key = Prefs.tmdbKey; val region = Prefs.watchRegion
        App.async({ films.take(20).mapNotNull { f -> runCatching { f to app.stremiolite.FilmOut.check(addons, key, region, f.id) }.getOrNull() } }) { r ->
            val res = r.getOrNull() ?: return@async
            val out = res.filter { it.second.first }.map { it.first }
            val notes = res.filter { !it.second.first && it.second.second != it.first.note }.map { (f, n) -> app.stremiolite.Following.Film(f.id, f.name, f.year, n.second) }
            if (out.isEmpty() && notes.isEmpty()) return@async
            Follow.followFilms(notes)
            Follow.unfollowFilms(out.map { it.id })
            val have = list()
            val add = out.filter { o -> have.none { it.id == o.id && !it.better } }
            if (add.isNotEmpty()) save(have + add.map { Entry(it.id, "movie", it.name, it.year, 0) })
            done()
        }
    }

    fun add(id: String, type: String, name: String, year: String) {
        save(list().filter { it.id != id } + Entry(id, type, name, year, 0))
        App.toast("Added to Waiting (your phone checks it)")
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        App.toast("Removed from Waiting")
    }

    /** The same followed list New episodes and the Calendar use (with your list rows). */
    fun following() = NewEpisodes.followedNow()
    private var askedShows = emptySet<String>()

    fun summary(): String {
        val films = current().count { it.ready == 0L } + Follow.films().size
        val shows = if (NewEpisodes.shows().isEmpty()) following().size else shownShows().count { !app.stremiolite.Following.ended(it) }
        if (films == 0 && shows == 0) return "empty"
        return listOfNotNull(
            if (films > 0) "$films film" + (if (films == 1) "" else "s") else null,
            if (shows > 0) "$shows show" + (if (shows == 1) "" else "s") else null,
        ).joinToString(" · ") + "  ►"
    }

    /** Shows that matter in Following: not ended ones you've finished, nor ended favourites you never chose. */
    private fun shownShows(): List<Full> {
        val ids = following().map { it.first }.toSet()
        val infos = Lib.infos().associateBy { it.id }
        val chosen = Follow.chosen()
        return NewEpisodes.shows().filter { sh ->
            if (sh.id !in ids) return@filter false
            val sec = app.stremiolite.Following.section(sh, Lib.watchedEpisodes(sh.id, MetaParse.stremioOrder(sh)), infos[sh.id])
            sec != app.stremiolite.Following.Section.FINISHED &&
                !(sec == app.stremiolite.Following.Section.NOT_STARTED && app.stremiolite.Following.ended(sh) && sh.id !in chosen)
        }
    }

    fun followingSummary(): String {
        val shows = if (NewEpisodes.shows().isEmpty()) following().size else shownShows().size
        val films = Follow.films().size
        if (shows == 0 && films == 0) return "empty"
        return listOfNotNull(
            if (shows > 0) "$shows show" + (if (shows == 1) "" else "s") else null,
            if (films > 0) "$films film" + (if (films == 1) "" else "s") else null,
        ).joinToString(" · ") + "  ►"
    }

    /** The line for the top of Home when a film you're waiting on became ready, or null. */
    fun homeRow(): Row? {
        val new = current().filter { it.ready > Prefs.readySeen }.sortedByDescending { it.ready }
        val label = app.stremiolite.ReadyNotice.label(new.map { it.name }) ?: return null
        return Row(label, "►", action = {
            Prefs.readySeen = System.currentTimeMillis()
            App.push(if (new.size == 1) Details.screenFor(new[0].id, new[0].type, new[0].name) else Ready.screen())
        })
    }

    fun screen(): Screen {
        val F = app.stremiolite.Following
        val R = app.stremiolite.Releases
        val rows = mutableListOf<Row>()
        val now = current()
        fun filmLabel(e: Entry) = Marks.title(if (e.better) Marks.BETTER else Marks.WAITING, e.name) + if (e.year.isNotEmpty()) " (${e.year})" else ""
        val notOut = Follow.films()
        val plain = now.filter { !it.better && it.ready == 0L && notOut.none { n -> n.id == it.id } }
        rows += Row("──── Films: waiting for a stream ────")
        if (plain.isEmpty() && notOut.isEmpty()) rows += Row("None.")
        plain.sortedBy { it.name }.forEach { e -> rows += Row(filmLabel(e), "no stream yet  ►", opens = { Details.screenFor(e.id, e.type, e.name) }) }
        notOut.sortedBy { it.name }.forEach { f ->
            rows += Row(Marks.title(Marks.WAITING, f.name) + if (f.year.isNotEmpty()) " (${f.year})" else "", f.note.ifBlank { "not out yet" } + "  ►",
                opens = { Details.screenFor(f.id, "movie", f.name) })
        }
        val upgrades = now.filter { it.better && it.ready == 0L }
        rows += Row("──── Films: waiting for a better version ────")
        if (upgrades.isEmpty()) rows += Row("None.")
        upgrades.sortedBy { it.name }.forEach { e -> rows += Row(filmLabel(e), "have: ${e.have}  ►", opens = { Details.screenFor(e.id, e.type, e.name) }) }
        val shows = shownShows().filter { !F.ended(it) }
        val rec = Follow.raw()
        val (season, episodes) = shows.partition { sh -> F.mode(rec, sh.id) == F.SEASONS }
        fun showRow(sh: Full) = Row(Marks.title(Marks.FOLLOWING, sh.name), F.nextText(sh) + "  ►", opens = { Details.screenFor(sh.id, "series", sh.name) })
        val loadingShows = NewEpisodes.isLoading()
        val unknown = following().filter { (id, _) -> NewEpisodes.shows().none { it.id == id } }
        rows += Row("──── Shows: waiting for a new season ────")
        if (loadingShows) rows += Row("Checking the shows you follow…")
        else if (season.isEmpty() && unknown.isEmpty()) rows += Row("None.")
        season.sortedBy { it.name }.forEach { rows += showRow(it) }
        if (!loadingShows) unknown.sortedBy { it.second }.forEach { (id, name) ->
            rows += Row(Marks.title(Marks.FOLLOWING, name), "couldn't load its dates yet  ►", opens = { Details.screenFor(id, "series", name) })
        }
        rows += Row("──── Shows: waiting for new episodes ────")
        if (!loadingShows && episodes.isEmpty()) rows += Row("None right now.")
        episodes.sortedBy { R.upcoming(it, 3650).firstOrNull()?.released ?: "9" }.forEach { rows += showRow(it) }
        rows += Row("────────────────────")
        rows += Row("Ready", Ready.summary(), opens = { Ready.screen() })
        val followed = following()
        if (followed.isNotEmpty()) rows += Row("Stop following all shows", "►", opens = {
            confirmScreen("Stop following all shows?", "Stops all ${followed.size} shows, on every device. They stay stopped even if you watch more or they're favourites; follow one again from its page.",
                "Yes, stop all ${followed.size}") { Follow.unfollowAll(followed.map { it.first }); App.toast("Stopped following all shows"); App.back(); App.replaceTop(screen()) }
        })
        val filmCount = plain.size + upgrades.size + notOut.size
        if (filmCount > 0) rows += Row("Stop waiting for all films", "►", opens = {
            confirmScreen("Stop waiting for all films?", "Stops all $filmCount films, on every device.", "Yes, stop all $filmCount") {
                save(list().filter { it.ready > 0 }); Follow.unfollowFilms(notOut.map { it.id }); App.toast("Stopped waiting for the films"); App.back(); App.replaceTop(screen())
            }
        })
        val s = Screen("Waiting", rows)
        s.onShow = {
            if (System.currentTimeMillis() - Lib.lastPull > 15_000) Lib.pull(force = true) {
                if (App.top()?.title == "Waiting") App.replaceTop(screen())
            }
            // Once per run: the redrawn screen must not ask again (load can answer at once).
            val missing = following().map { it.first }.filter { id -> NewEpisodes.shows().none { it.id == id } }.toSet()
            // Ask once for each show missing (a redrawn screen mustn't ask again: load can answer at once).
            if (missing.isNotEmpty() && !askedShows.containsAll(missing)) { askedShows = askedShows + missing; NewEpisodes.load(force = true) { if (App.top()?.title == "Waiting") App.replaceTop(screen()) } }
            checkFollowedFilms { if (App.top()?.title == "Waiting") App.replaceTop(screen()) }
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
        val cont = Lib.continueWatching().filter { it.type == "series" }.map { it.id to it.name }
        App.async({ Follow.shows(app.stremiolite.Releases.fromPersonalRows(Home.all())).take(60) }) { r ->
            val mineNow = r.getOrDefault(mine)
            if (r.isSuccess) lastFollowed = mineNow
            followed = mineNow.size
            // Shows in Continue watching are checked too: their new episodes come to Ready.
            val shows = (mineNow + cont).distinctBy { it.first }
            val got = mutableListOf<Full>()
            fun finish() {
                fulls = got.toList(); fullsAt = System.currentTimeMillis(); loading = false; failed.clear()
                writeCache()
                refreshFilms()
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
    fun weekCount(): Int? = if (fullsAt == 0L) null else fulls.sumOf { app.stremiolite.Releases.upcoming(it, 7).size } +
        filmEvents.orEmpty().count { it.day <= app.stremiolite.Releases.day(-7) }

    private var filmEvents: List<app.stremiolite.FilmDates.Event>? = null
    private var filmsAt = 0L
    private var filmsLoading = false

    private fun refreshFilms(force: Boolean = false, done: () -> Unit = {}) {
        if (filmsLoading || (!force && System.currentTimeMillis() - filmsAt < 3_600_000L)) { done(); return }
        filmsLoading = true
        val want = calendarFilms(); val addons = Home.all(); val key = Prefs.tmdbKey; val region = Prefs.watchRegion
        App.async({ app.stremiolite.FilmDates.events(addons, key, region, want) }) { r ->
            filmsLoading = false
            r.getOrNull()?.let { filmEvents = it; filmsAt = System.currentTimeMillis() }
            done()
        }
    }

    private fun epRow(f: Found, detail: String) = Row(
        f.show.name,
        listOf(MetaParse.epLabel(f.ep), detail, f.ep.title).filter { it.isNotBlank() }.joinToString(" · "),
        opens = { Details.screenFor(f.show.id, "series", f.show.name) },
        play = {
            val w = Details.watchFor(f.show, f.ep)
            App.push(Play.best("series", f.ep.id, "${f.show.name} ${MetaParse.epLabel(f.ep)}", w, Lib.resumeAt(f.show.id, f.ep.id)))
        },
    )

    /**
     * Whether a new episode goes to Ready and the Home line ([app.stremiolite.Releases.isReady]):
     * Continue watching and every-episode follows get each that's news; season follows, a new season.
     */
    fun isNews(): (Full, Video) -> Boolean {
        val cont = Lib.continueWatching().filter { it.type == "series" }.mapTo(HashSet()) { it.id }
        val followed = followedNow().mapTo(HashSet()) { it.first }
        val next = NextList.ids()
        val rec = Follow.raw()
        return { sh, ep ->
            if (sh.id in next || (sh.id !in cont && sh.id !in followed)) false
            else app.stremiolite.Releases.isReady(sh, ep, Lib.watchedEpisodes(sh.id, MetaParse.stremioOrder(sh)), Lib.info(sh.id)?.lastWatched ?: 0L,
                sh.id in cont, app.stremiolite.Following.mode(rec, sh.id), app.stremiolite.Following.since(rec, sh.id))
        }
    }

    /** Episodes out in the last few days, not watched and not yet seen here, newest first. */
    private fun unseen(): List<Found> {
        val seen = app.stremiolite.ReadyNotice.readSeen(Prefs.episodesSeen)
        val news = isNews()
        return fulls.flatMap { sh ->
            val done = Lib.watchedEpisodes(sh.id, MetaParse.stremioOrder(sh))
            app.stremiolite.Releases.recent(sh).filter { it.id !in seen && it.id !in done && news(sh, it) }.map { Found(sh, it) }
        }.sortedWith(compareByDescending<Found> { it.ep.released }.thenBy { it.show.name }.thenBy { it.ep.episode })
    }

    fun markSeen() {
        val now = fulls.flatMap { sh -> app.stremiolite.Releases.recent(sh).map { it.id } }
        if (now.isEmpty()) return
        Prefs.episodesSeen = app.stremiolite.ReadyNotice.writeSeen(app.stremiolite.ReadyNotice.readSeen(Prefs.episodesSeen), now)
    }

    /** The "New episode" line at the top of Home, or null. */
    fun homeRow(): Row? {
        if (!cacheRead) readCache()
        val new = unseen()
        val label = app.stremiolite.ReadyNotice.episodesLabel(new.map { it.show.name to MetaParse.epLabel(it.ep) }) ?: return null
        return Row(label, "►", action = {
            markSeen()
            App.push(if (new.size == 1) Details.screenFor(new[0].show.id, "series", new[0].show.name) else Ready.screen())
        })
    }

    private fun calendarFilms(): List<app.stremiolite.FilmDates.Film> {
        return (Waiting.list().filter { it.type == "movie" }.map { app.stremiolite.FilmDates.Film(it.id, it.name, it.year) } +
            Follow.films().map { app.stremiolite.FilmDates.Film(it.id, it.name, it.year) } +
            NextList.all().filter { it.type == "movie" }.map { app.stremiolite.FilmDates.Film(it.id, it.name, it.year) } +
            BasketList.all().filter { it.type == "movie" }.map { app.stremiolite.FilmDates.Film(it.id, it.name, it.year) }).distinctBy { it.id }
    }

    /** Episodes of shows you follow and dates of films you care about, next 30 days, by day. */
    fun calendar(): Screen {
        val status = Row("Finding the shows you follow…")
        lateinit var s: Screen
        fun build() {
            val films = filmEvents
            val R = app.stremiolite.Releases
            val eps = fulls.flatMap { sh -> R.upcoming(sh).map { Found(sh, it) } }
            val days = (eps.map { it.ep.released } + films.orEmpty().map { it.day }).distinct().sorted()
            val n = eps.size + films.orEmpty().size
            status.label = when {
                loading -> "Checking $followed shows… $checked done"
                n == 0 && films == null -> "Finding film dates…"
                n == 0 -> "Nothing dated in the next 30 days. Shows you follow and films you're waiting on, lining up or have in the basket show here."
                else -> "Next 30 days · $followed shows followed" + (films?.map { it.film.id }?.distinct()?.size?.takeIf { it > 0 }?.let { " · $it films" } ?: "")
            }
            val rows = mutableListOf(status)
            if (n > 0) rows += Row("Save these dates as a calendar file (.ics)", "►", action = {
                CalendarSave.save(eps.map { app.stremiolite.CalendarFile.Item(it.ep.released, "${it.show.name} ${MetaParse.epLabel(it.ep)}", it.ep.title) } +
                    films.orEmpty().map { app.stremiolite.CalendarFile.Item(it.day, "${it.film.name} (${it.what})", "Film") })
            })
            days.forEach { day ->
                rows += Row("──── ${R.dayName(day)} ────")
                eps.filter { it.ep.released == day }.sortedWith(compareBy<Found> { it.show.name }.thenBy { it.ep.season }.thenBy { it.ep.episode })
                    .forEach { f -> rows += epRow(f, if (R.opensSeason(f.ep)) "new season" else "") }
                films.orEmpty().filter { it.day == day }.forEach { e ->
                    rows += Row(Marks.title(Catalogs.mark(e.film.id, "movie"), e.film.name) + if (e.film.year.isNotEmpty()) " (${e.film.year})" else "",
                        "film · ${e.what}  ►", opens = { Details.screenFor(e.film.id, "movie", e.film.name) })
                }
            }
            s.rows = rows
            App.refresh()
        }
        s = Screen("Calendar", listOf(status))
        var started = false
        s.onShow = {
            if (!started) {
                started = true
                load(onProgress = { if (App.isTop(s)) build() }) { build() }
                refreshFilms(force = true) { if (filmEvents == null) filmEvents = emptyList(); if (App.isTop(s)) build() }
            }
            build()
        }
        return s
    }
}

/** Following shows for new episodes and seasons (rules in the shared Following), apart from the Library. */
object Follow {
    private fun record() = Lib.raw(app.stremiolite.Following.ID)
    fun shows(lists: List<Pair<String, String>> = emptyList()) = app.stremiolite.Following.shows(Lib.infos(), record(), lists, Prefs.autoFollow)
    fun isFollowed(id: String) = app.stremiolite.Following.isFollowed(id, Lib.infos(), record(), Prefs.autoFollow)
    fun stopped() = app.stremiolite.Following.stopped(record())
    fun follow(id: String, name: String, mode: String = app.stremiolite.Following.EPISODES) = Lib.put(app.stremiolite.Following.follow(record(), id, name, mode))
    fun mode(id: String) = app.stremiolite.Following.mode(record(), id)
    fun unfollow(id: String) = Lib.put(app.stremiolite.Following.unfollow(record(), id))
    fun chosen(): Set<String> = app.stremiolite.Following.read(record()).follow.keys
    fun films() = app.stremiolite.Following.films(record())
    fun followsFilm(id: String) = films().any { it.id == id }
    fun followFilms(films: List<app.stremiolite.Following.Film>) { if (films.isNotEmpty()) Lib.put(app.stremiolite.Following.followFilms(record(), films)) }
    fun unfollowFilms(ids: Collection<String>) { if (ids.isNotEmpty()) Lib.put(app.stremiolite.Following.unfollowFilms(record(), ids)) }
    fun raw() = record()

    /** Stops following all of [ids] in one change. */
    fun unfollowAll(ids: List<String>) {
        var rec = record()
        ids.forEach { rec = app.stremiolite.Following.unfollow(rec, it) }
        rec?.let { Lib.put(it) }
    }
}


/** Settings › Your keys: see, change or remove each key this profile uses. */
fun keysScreen(): Screen {
    lateinit var s: Screen
    fun set(k: String) = if (k.isBlank()) "not set" else "…" + k.takeLast(4)
    fun build(): List<Row> = listOf(
        Row("Check all my keys", "►", opens = { KeysCheck.screen() }),
    ) + KeyDropUi.rows() + listOf(
        Row("────────────────────"),
        Row("TMDB key", set(Prefs.tmdbKey) + "  ►", opens = { Details.whereToWatchSettings() }),
        Row("Debrid keys", Prefs.debridKeys.let { if (it.isEmpty()) "not set" else app.stremiolite.StreamSetup.names(it).joinToString(", ") } + "  ►", opens = { StreamsSetup.screen(keysOnly = true) }),
        Row("MDBList key", set(Prefs.mdblistKey) + "  ►", opens = {
            RowsSetup.keyScreen("MDBList key", app.stremiolite.RowSetup.MDBLIST_KEY_PAGE, listOf(
                "Your MDBList account: its lists, and your own Watchlist row.",
                "Your rows keep the key they were made with: after changing it, use 'Put this key in my rows' below.",
            ), { Prefs.mdblistKey }, { Prefs.mdblistKey = it; if (it.isNotBlank()) KeysCheck.quick("MDBList key") { app.stremiolite.KeyCheck.mdblist(it) } }, after = { listOf(Row("Put this key in my rows", "►", action = { App.push(RowsSetup.useMdblistKey(Prefs.mdblistKey)) })) })
        }),
        Row("Gemini key", set(Prefs.geminiKey) + "  ►", opens = {
            RowsSetup.keyScreen("Gemini key", app.stremiolite.RowSetup.GEMINI_KEY_PAGE, listOf(
                "For asking AI in Search: start a search with \"ai\" and describe what you want.",
                "Free from Google AI Studio (aistudio.google.com): Get API key.",
                "Your rows keep the key they were made with: after changing it, use 'Put this key in my rows' below.",
            ), { Prefs.geminiKey }, { Prefs.geminiKey = it; if (it.isNotBlank()) KeysCheck.quick("Gemini key") { app.stremiolite.KeyCheck.gemini(it) } }, after = { listOf(Row("Put this key in my rows", "►", action = { App.push(RowsSetup.useAiKey(Prefs.geminiKey)) }), Row("Check my AI setup", "►", opens = { AiCheck.screen() })) })
        }),
    )
    s = Screen("Your keys", build())
    s.onShow = { s.rows = build(); App.refresh() }
    s.pos = 2
    return s
}

/** Every line on Home you can show or hide (Settings › Home › Show on Home), by section. */
private val HOME_LINES = listOf(
    "Watching" to listOf("notices" to "Notices at the top", "search" to "Search", "continue" to "Continue watching", "next" to "Next watch",
        "ready" to "Ready", "waiting" to "Waiting", "calendar" to "Calendar", "watched" to "Watched", "favourites" to "Favourites", "basket" to "Basket"),
    "For you" to listOf("foryou.top" to "Top picks for you", "foryou.because" to "Because you watched…", "foryou.favs" to "More like your favourites"),
    "Browse" to listOf("coming" to "Coming soon"),
    "App" to listOf("live" to "Live TV", "drives" to "Drives", "downloads" to "Downloads", "playlink" to "Play a link", "news" to "News", "sleep" to "Sleep timer"),
)

fun homeLines(): Screen {
    lateinit var s: Screen
    fun build() {
        s.rows = HOME_LINES.flatMap { (section, lines) ->
            listOf(Row("──── $section ────")) + lines.map { (key, label) ->
                Row(label, "◄ ${if (Prefs.onHome(key)) "Shown" else "Hidden"} ►", action = { Prefs.setOnHome(key, !Prefs.onHome(key)); Home.redraw(); build() })
            }
        }
        App.refresh()
    }
    s = Screen("Show on Home", emptyList())
    s.onShow = { build() }
    return s
}

/** Settings › Ratings: which scores, in what order, vote counts, percentages. */
fun ratingsScreen(): Screen {
    lateinit var s: Screen
    fun name(k: String) = app.stremiolite.Ratings.SOURCES.firstOrNull { it.first == k }?.second ?: k
    fun build() {
        val c = Prefs.ratingsConfig()
        val rows = mutableListOf(
            Row("Vote counts", "◄ ${if (c.votes) "On" else "Off"} ►", action = { Prefs.setRatings(votes = !c.votes); build() }),
            Row("Show as percentages", "◄ ${if (c.percent) "On" else "Off"} ►", action = { Prefs.setRatings(percent = !c.percent); build() }),
            Row("──── Shown, in this order ────"),
        )
        c.order.forEachIndexed { i, k ->
            rows += Row("${i + 1}. ${name(k)}", "►", opens = {
                Screen(name(k), listOfNotNull(
                    if (i > 0) Row("Move up", action = { Prefs.setRatings(order = c.order.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }); App.back() }) else null,
                    if (i < c.order.size - 1) Row("Move down", action = { Prefs.setRatings(order = c.order.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }); App.back() }) else null,
                    Row("Hide", action = { Prefs.setRatings(order = c.order - k); App.back() }),
                ))
            })
        }
        val hidden = app.stremiolite.Ratings.SOURCES.map { it.first }.filter { it !in c.order }
        if (hidden.isNotEmpty()) {
            rows += Row("──── Not shown ────")
            hidden.forEach { k -> rows += Row(name(k), "add  ►", action = { Prefs.setRatings(order = c.order + k); build() }) }
        }
        s.rows = rows; App.refresh()
    }
    s = Screen("Ratings", emptyList())
    s.onShow = { build() }
    return s
}
