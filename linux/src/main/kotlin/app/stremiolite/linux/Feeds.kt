package app.stremiolite.linux

import app.stremiolite.ComingSoon
import app.stremiolite.LiveTv
import app.stremiolite.News

/** News (headlines from film and TV news feeds) and Coming soon (films out in the next weeks). */
object Feeds {
    private fun active(starter: List<News.Feed>): List<News.Feed> {
        val off = Prefs.newsHidden
        return starter.filter { it.url !in off } + Prefs.newsFeeds
    }

    private fun titles(): List<String> = Lib.library().map { it.name } + Follow.shows().map { it.second }

    fun news(): Screen {
        lateinit var s: Screen
        var starter: List<News.Feed>? = null
        val stories = HashMap<String, List<News.Item>>()
        var waiting = 0
        var pending = false

        fun build() {
            pending = false
            val feeds = starter?.let { active(it) }
            val rows = mutableListOf<Row>()
            val items = News.merge(feeds?.mapNotNull { stories[it.url] } ?: emptyList(), titles())
            when {
                feeds == null -> rows += Row("Reading the news…")
                feeds.isEmpty() -> rows += Row("No feeds switched on: see Choose feeds.")
                waiting > 0 -> rows += Row("Reading ${feeds.size - waiting} of ${feeds.size} feeds…")
                items.isEmpty() -> rows += Row("No stories: the feeds didn't answer. Try again later.")
            }
            val mine = items.filter { it.about.isNotEmpty() }.take(8)
            if (mine.isNotEmpty()) {
                rows += Row("──── About your titles ────")
                mine.forEach { rows += storyRow(it, "★ ") }
            }
            if (items.isNotEmpty()) {
                rows += Row("──── Latest ────")
                (items - mine.toSet()).take(60).forEach { rows += storyRow(it, "") }
            }
            rows += Row("────────────────────")
            rows += Row("Choose feeds", "►", opens = { chooseFeeds(starter ?: emptyList()) })
            rows += Row("Refresh", action = { News.forget(); stories.clear(); s.onShow?.invoke() })
            s.rows = rows
            App.refresh()
        }

        fun soon() { if (!pending) { pending = true; App.later(400) { build() } } }

        s = Screen("News", listOf(Row("Reading the news…")))
        s.key = "news"
        s.onShow = {
            App.async({ News.starter() }) { r ->
                starter = r.getOrDefault(emptyList())
                val feeds = active(starter!!).filter { it.url !in stories }
                waiting = feeds.size
                build()
                feeds.forEach { f ->
                    App.async({ News.load(f) }) { res ->
                        res.onSuccess { stories[f.url] = it }
                        waiting--
                        if (App.inStack(s)) soon()
                    }
                }
            }
        }
        return s
    }

    private fun storyRow(it: News.Item, mark: String) =
        Row(mark + it.title, listOf(News.ago(it.at), it.source).filter { s -> s.isNotEmpty() }.joinToString(" · "), opens = { story(it) })

    private fun story(it: News.Item): Screen {
        val width = (App.columns() - 6).coerceIn(30, 100)
        val rows = mutableListOf<Row>()
        wrap(it.title, width).forEach { l -> rows += Row(l) }
        rows += Row(listOf(it.source, News.ago(it.at).let { a -> if (a.isEmpty()) "" else "$a ago" }).filter { s -> s.isNotEmpty() }.joinToString(" · "))
        if (it.about.isNotEmpty()) rows += Row("★ About ${it.about}, in your library")
        rows += Row("")
        wrap(it.summary.ifBlank { "No summary in the feed." }, width).forEach { l -> rows += Row(l) }
        rows += Row("────────────────────")
        if (it.link.isNotEmpty()) {
            rows += Row("Read the full article (opens your browser)", action = { WebLink.open(it.link) })
            rows += Row("Copy the link", action = { Clipboard.copyOrShow(it.link, "Article link") })
        }
        return Screen(it.source, rows)
    }

    private fun chooseFeeds(starter: List<News.Feed>): Screen {
        lateinit var s: Screen
        fun build() {
            val off = Prefs.newsHidden
            val rows = mutableListOf(Row("──── Starter feeds: Enter switches one on or off ────"))
            starter.forEach { f ->
                val state = if (f.url in off) "off" else News.error(f)?.let { "on · not answering" } ?: News.count(f)?.let { "on · $it stories" } ?: "on"
                rows += Row(f.name, state, action = {
                    Prefs.newsHidden = if (f.url in off) off - f.url else off + f.url; Prefs.syncOut(); build()
                })
            }
            rows += Row("──── Your feeds ────")
            Prefs.newsFeeds.forEach { f ->
                rows += Row(f.name, (News.error(f)?.let { "not answering" } ?: News.count(f)?.let { "$it stories" } ?: "") + "  remove ►", action = {
                    Prefs.newsFeeds = Prefs.newsFeeds.filter { it.url != f.url }; Prefs.syncOut(); App.toast("Removed ${f.name}"); build()
                })
            }
            rows += Row("Add a feed (RSS link)", "►", action = {
                App.ask("News feed link (RSS or Atom)") { v ->
                    val link = v?.let { LiveTv.cleanLink(it) } ?: run { if (!v.isNullOrBlank()) App.toast("That isn't a link"); return@ask }
                    App.toast("Checking the feed…")
                    val f = News.Feed(LiveTv.nameFor(link), link)
                    App.async({ News.load(f, fresh = true) }) { r ->
                        r.onSuccess { items ->
                            Prefs.newsFeeds = Prefs.newsFeeds.filter { it.url != link } + f; Prefs.syncOut()
                            App.toast("Added ${f.name}: ${items.size} stories"); build()
                        }.onFailure { App.toast("Couldn't add it: ${News.error(f) ?: Net.describe(it)}") }
                    }
                }
            })
            s.rows = rows
            App.refresh()
        }
        s = Screen("Choose feeds", emptyList())
        s.onShow = { build() }
        return s
    }

    /** Coming soon: a menu of Films and Shows. */
    fun comingSoon(): Screen = Screen("Coming soon", listOf(
        Row("Films", "cinema releases, this week and later  ►", opens = { comingList("movie") }),
        Row("Shows", "new series starting soon  ►", opens = { comingList("series") }),
    )).also { it.key = "coming-soon" }

    private fun comingList(type: String): Screen {
        val films = type == "movie"
        lateinit var s: Screen
        var entries: List<ComingSoon.Entry>? = null
        var pending = false
        var loadedFrom: String? = null

        fun build() {
            pending = false
            val list = entries
            val used = ComingSoon.catalogs(Home.all(), comingFrom(type), type)
            val rows = mutableListOf<Row>()
            rows += Row("From", (if (used.isEmpty()) "none" else used.joinToString(" + ") { it.second.name }) + "  ►", opens = { chooseList(type) })
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
                        rows += Row(e.meta.name, (if (films && Waiting.has(e.meta.id)) "◷ " else "") + ComingSoon.detail(e), opens = { Details.screen(e.addon, e.meta) })
                    }
                }
            }
            s.rows = rows
            App.refresh()
        }

        fun soon() { if (!pending) { pending = true; App.later(500) { build() } } }

        s = Screen(if (films) "Coming soon: films" else "Coming soon: shows", listOf(Row("Looking at what's coming…")))
        s.key = "coming-soon-$type"
        s.onShow = {
            val addons = Home.all()
            val chosen = comingFrom(type)
            if (loadedFrom == chosen && entries != null) build()
            else {
                loadedFrom = chosen
                entries = null
                build()
                App.async({ ComingSoon.entries(addons, chosen, type) }) { r ->
                    entries = r.getOrDefault(emptyList())
                    build()
                    val key = Prefs.tmdbKey
                    val region = Prefs.watchRegion
                    val todo = ArrayDeque(entries!!.filter { it.date < 0 || (key.isNotBlank() && it.where == null) })
                    fun next() {
                        if (!App.inStack(s)) return
                        val e = todo.removeFirstOrNull() ?: return
                        App.async({
                            ComingSoon.lookUp(addons, e)
                            if (key.isNotBlank()) ComingSoon.lookWhere(key, region, e)
                        }) { soon(); next() }
                    }
                    // Linux can look up three at a time.
                    repeat(3) { next() }
                }
            }
        }
        return s
    }

    private fun comingFrom(type: String) = if (type == "movie") Prefs.comingFrom else Prefs.comingFromShows
    private fun setComingFrom(type: String, k: String) { if (type == "movie") Prefs.comingFrom = k else Prefs.comingFromShows = k }

    private fun chooseList(type: String): Screen {
        val chosen = comingFrom(type)
        val rows = mutableListOf(
            Row("Automatic: Releases" + if (type == "movie") " (or Upcoming and In Cinemas)" else "", if (chosen.isEmpty()) "✓" else "", action = { setComingFrom(type, ""); App.back() }),
            Row(if (type == "movie") "──── Or one of your film lists ────" else "──── Or one of your show lists ────"),
        )
        ComingSoon.choices(Home.all(), type).forEach { (a, c) ->
            val k = app.stremiolite.Folders.rowKey(a, c)
            rows += Row(c.name, if (k == chosen) "✓" else "", action = { setComingFrom(type, k); App.back() })
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
