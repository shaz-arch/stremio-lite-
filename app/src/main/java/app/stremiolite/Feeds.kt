package app.stremiolite

/** News (headlines from film and TV news feeds) and Coming soon (films out in the next weeks). */
object Feeds {
    // ---- News ------------------------------------------------------------------------------------

    /** The feeds in use: the starter list (less any you've switched off) plus your own. */
    private fun active(app: MainActivity, starter: List<News.Feed>): List<News.Feed> {
        val off = Settings.newsHidden(app)
        return starter.filter { it.url !in off } + Settings.newsFeeds(app)
    }

    /** Your library and followed shows: headlines naming one of these get a ★ and go to the top. */
    private fun titles(app: MainActivity): List<String> =
        History.library(app).map { it.name } + Follow.shows(app).map { it.second }

    fun news(app: MainActivity): Screen {
        lateinit var s: Screen
        var starter: List<News.Feed>? = null
        val stories = HashMap<String, List<News.Item>>()
        var waiting = 0
        var pending = false

        fun build() {
            pending = false
            val feeds = starter?.let { active(app, it) }
            val rows = mutableListOf<Row>()
            val items = News.merge(feeds?.mapNotNull { stories[it.url] } ?: emptyList(), titles(app))
            when {
                feeds == null -> rows += Row("Reading the news…")
                feeds.isEmpty() -> rows += Row("No feeds switched on: see Choose feeds.")
                waiting > 0 -> rows += Row("Reading ${feeds.size - waiting} of ${feeds.size} feeds…")
                items.isEmpty() -> rows += Row("No stories: the feeds didn't answer. Try again later.")
            }
            val mine = items.filter { it.about.isNotEmpty() }.take(8)
            if (mine.isNotEmpty()) {
                rows += Row("──── About your titles ────")
                mine.forEach { rows += storyRow(app, it, "★ ") }
            }
            if (items.isNotEmpty()) {
                rows += Row("──── Latest ────")
                (items - mine.toSet()).take(60).forEach { rows += storyRow(app, it, "") }
            }
            rows += Row("────────────────────")
            rows += Row("Choose feeds", "►", opens = { chooseFeeds(app, starter ?: emptyList()) })
            rows += Row("Refresh", action = { News.forget(); stories.clear(); s.onShow?.invoke() })
            s.rows = rows
            app.refresh(s)
        }

        fun soon() { if (!pending) { pending = true; Net.later(600L) { build() } } }

        s = Screen("News", listOf(Row("Reading the news…")))
        s.key = "news"
        s.onShow = {
            Net.async({ News.starter() }) { r ->
                starter = r.getOrDefault(emptyList())
                val feeds = active(app, starter!!).filter { it.url !in stories }
                waiting = feeds.size
                build()
                feeds.forEach { f ->
                    Net.async({ News.load(f) }) { res ->
                        res.onSuccess { stories[f.url] = it }
                        waiting--
                        if (app.inStack(s)) soon()
                    }
                }
            }
        }
        return s
    }

    private fun storyRow(app: MainActivity, it: News.Item, mark: String) =
        Row(mark + it.title, listOf(News.ago(it.at), it.source).filter { s -> s.isNotEmpty() }.joinToString(" · "), opens = { story(app, it) })

    private fun story(app: MainActivity, it: News.Item): Screen {
        val width = if (narrow) 34 else 60
        val rows = mutableListOf<Row>()
        wrap(it.title, width).forEach { l -> rows += Row(l) }
        rows += Row(listOf(it.source, News.ago(it.at).let { a -> if (a.isEmpty()) "" else "$a ago" }).filter { s -> s.isNotEmpty() }.joinToString(" · "))
        if (it.about.isNotEmpty()) rows += Row("★ About ${it.about}, in your library")
        rows += Row("")
        wrap(it.summary.ifBlank { "No summary in the feed." }, width).forEach { l -> rows += Row(l) }
        rows += Row("────────────────────")
        if (it.link.isNotEmpty()) rows += LinkOut.row(app, if (app.isPhone) "Read the full article" else "Read the full article on your phone", it.source, it.link)
        return Screen(it.source, rows)
    }

    private fun chooseFeeds(app: MainActivity, starter: List<News.Feed>): Screen {
        lateinit var s: Screen
        fun build() {
            val off = Settings.newsHidden(app)
            val rows = mutableListOf(Row("──── Starter feeds ────"))
            starter.forEach { f ->
                val state = if (f.url in off) "off" else News.error(f)?.let { "on · not answering" } ?: News.count(f)?.let { "on · $it stories" } ?: "on"
                rows += Row(f.name, state, action = {
                    Settings.setNewsHidden(app, if (f.url in off) off - f.url else off + f.url); build()
                })
            }
            rows += Row("──── Your feeds ────")
            Settings.newsFeeds(app).forEach { f ->
                rows += Row(f.name, (News.error(f)?.let { "not answering" } ?: News.count(f)?.let { "$it stories" } ?: "") + "  remove ►", action = {
                    Settings.setNewsFeeds(app, Settings.newsFeeds(app).filter { it.url != f.url }); app.toast("Removed ${f.name}"); build()
                })
            }
            rows += Row("Add a feed (RSS link)", "►", action = { addFeed(app) { build() } })
            s.rows = rows
            app.refresh(s)
        }
        s = Screen("Choose feeds", emptyList())
        s.onShow = { build() }
        return s
    }

    private fun addFeed(app: MainActivity, done: () -> Unit) {
        val input = android.widget.EditText(app).apply { hint = "https://…/feed"; setSingleLine() }
        PhoneTyping.show(app, android.app.AlertDialog.Builder(app)
            .setTitle("News feed link (RSS or Atom)")
            .setPositiveButton("Add") { _, _ ->
                val link = LiveTv.cleanLink(input.text.toString()) ?: run { app.toast("That isn't a link"); return@setPositiveButton }
                app.toast("Checking the feed…")
                val f = News.Feed(LiveTv.nameFor(link), link)
                Net.async({ News.load(f, fresh = true) }) { r ->
                    r.onSuccess { items ->
                        Settings.setNewsFeeds(app, Settings.newsFeeds(app).filter { it.url != link } + f)
                        app.toast("Added ${f.name}: ${items.size} stories")
                        done()
                    }.onFailure { app.toast("Couldn't add it: ${News.error(f) ?: Net.describe(it)}") }
                }
            }
            .setNegativeButton("Cancel", null), input, "News feed link")
    }

    // ---- Coming soon -------------------------------------------------------------------------

    /** Coming soon: a menu of Films and Shows. */
    fun comingSoon(app: MainActivity): Screen = Screen("Coming soon", listOf(
        Row("Films", "cinema releases, this week and later  ►", opens = { comingList(app, "movie") }),
        Row("Shows", "new series starting soon  ►", opens = { comingList(app, "series") }),
    )).also { it.key = "coming-soon" }

    private fun comingList(app: MainActivity, type: String): Screen {
        val films = type == "movie"
        lateinit var s: Screen
        var entries: List<ComingSoon.Entry>? = null
        var pending = false
        var loadedFrom: String? = null

        fun build() {
            pending = false
            val list = entries
            val addons = Home.allAddons()
            val chosen = Settings.comingFrom(app, type)
            val used = ComingSoon.catalogs(addons, chosen, type)
            val rows = mutableListOf<Row>()
            rows += Row("From", (if (used.isEmpty()) "none" else used.joinToString(" + ") { it.second.name }) + "  ►", opens = { chooseList(app, type) })
            when {
                list == null -> rows += Row("Looking at what's coming…")
                used.isEmpty() -> {
                    rows += Row(if (films) "None of your catalogues has an Upcoming or In Cinemas list." else "None of your catalogues has a list of new or coming series.")
                    rows += Row("Pick any film list with From, or tick Releases in Your rows (AIOMetadata).")
                }
                list.isEmpty() -> rows += Row("Nothing in that list just now.")
            }
            if (list != null && list.isNotEmpty()) {
                val left = list.count { it.date < 0 }
                if (left > 0) rows += Row("Looking up release dates: ${list.size - left} of ${list.size}…")
                ComingSoon.groups(list).forEach { (name, group) ->
                    rows += Row("──── $name ────")
                    group.forEach { e ->
                        rows += Row(e.meta.name, (if (films && Waiting.has(app, e.meta.id)) "◷ " else "") + ComingSoon.detail(e), opens = { Details.screen(app, e.addon, e.meta) })
                    }
                }
            }
            s.rows = rows
            app.refresh(s)
        }

        fun soon() { if (!pending) { pending = true; Net.later(if (app.isPhone) 600L else 1200L) { build() } } }

        s = Screen(if (films) "Coming soon: films" else "Coming soon: shows", listOf(Row("Looking at what's coming…")))
        s.key = "coming-soon-$type"
        s.onShow = {
            val addons = Home.allAddons()
            val chosen = Settings.comingFrom(app, type)
            if (loadedFrom == chosen && entries != null) build()
            else {
                loadedFrom = chosen
                entries = null
                build()
                Net.async({ ComingSoon.entries(addons, chosen, type) }) { r ->
                    entries = r.getOrDefault(emptyList())
                    build()
                    // Dates (and, with a TMDB key, where it'll be shown) one at a time, so the stick stays responsive.
                    val key = Settings.tmdbKey(app)
                    val region = Settings.watchRegion(app)
                    val todo = entries!!.filter { it.date < 0 || (key.isNotBlank() && it.where == null) }
                    fun next(i: Int) {
                        if (i >= todo.size || !app.inStack(s)) return
                        if (app.playerOpen) { Net.later(10_000) { next(i) }; return }   // playback first
                        Net.async({
                            ComingSoon.lookUp(addons, todo[i])
                            if (key.isNotBlank()) ComingSoon.lookWhere(key, region, todo[i])
                        }) { soon(); next(i + 1) }
                    }
                    next(0)
                }
            }
        }
        return s
    }

    /** Which film list Coming soon reads: automatic (Upcoming and In Cinemas), or any one you pick. */
    private fun chooseList(app: MainActivity, type: String): Screen {
        val chosen = Settings.comingFrom(app, type)
        val rows = mutableListOf(
            Row("Automatic: Releases" + if (type == "movie") " (or Upcoming and In Cinemas)" else "", if (chosen.isEmpty()) "✓" else "", action = { Settings.setComingFrom(app, type, ""); app.back() }),
            Row(if (type == "movie") "──── Or one of your film lists ────" else "──── Or one of your show lists ────"),
        )
        ComingSoon.choices(Home.allAddons(), type).forEach { (a, c) ->
            val k = Folders.rowKey(a, c)
            rows += Row(c.name, if (k == chosen) "✓" else "", action = { Settings.setComingFrom(app, type, k); app.back() })
        }
        return Screen("Coming soon from", rows)
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (w in text.split(' ')) {
            if (line.isEmpty()) line = w
            else if (line.length + 1 + w.length <= width) line += " $w"
            else { out += line; line = w }
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
