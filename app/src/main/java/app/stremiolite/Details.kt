package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Full details for a title from the add-ons' /meta endpoint, then seasons and episodes for shows.
 * The catalogue's own info shows straight away while the full details load.
 */
object Details {
    private const val CACHE_MS = 24 * 60 * 60_000L

    private class Cached(val time: Long, val full: Full)
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 12
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Ask the add-on the title came from first (if it can describe it), then any other that can. */
    private fun fetch(from: Addon, type: String, id: String): Full {
        val key = "$type/$id"
        synchronized(cache) {
            cache[key]?.let { if (System.currentTimeMillis() - it.time < CACHE_MS) return it.full }
        }
        val candidates = (listOf(from) + Home.allAddons()).distinct().filter { it.supports("meta", type, id) }
        if (candidates.isEmpty()) throw IllegalStateException("none of your add-ons can describe this title")
        var last: Throwable? = null
        for (a in candidates) {
            try {
                val json = JSONObject(Net.get(a.base + "/meta/" + enc(type) + "/" + enc(id) + ".json"))
                val m = json.optJSONObject("meta") ?: continue
                val full = MetaParse.full(m, a.name)
                synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), full) }
                return full
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no details found")
    }

    /** [autoPlay]: go straight on to playing (Play/Pause on a list), via the same resume/next logic. */
    fun screen(app: MainActivity, from: Addon, item: Meta, autoPlay: Boolean = false): Screen {
        // Start with what the catalogue already knows, so there's something to read at once.
        val quick = Catalogs.preview(app, item)
        val loadingRow = Row("Loading full details…")
        quick.rows = quick.rows.dropLast(1) + loadingRow
        var started = false
        var full: Full? = null

        quick.onShow = {
            // Picks up where you got to on another device (resume point, watched marks).
            History.pullFresh(app) { full?.let { f -> if (app.isTop(quick)) { quick.rows = rows(app, f); app.refresh(quick) } } }
            val f = full
            if (f != null) {
                // Back from the player or an episode: redraw so resume points and marks are current.
                quick.rows = rows(app, f)
                app.refresh(quick)
                lookUpWatch(app, f, quick); lookUpSimilar(app, f, quick); lookUpRatings(app, f, quick)
            } else if (!started) {
                started = true
                Net.async({ fetch(from, item.type, item.id) }) { r ->
                    r.onSuccess { loaded ->
                        full = loaded
                        if (loaded.videos.isNotEmpty()) History.applyAccountWatched(app, loaded.id, stremioOrder(loaded))
                        quick.rows = rows(app, loaded)
                        lookUpWatch(app, loaded, quick); lookUpSimilar(app, loaded, quick); lookUpRatings(app, loaded, quick)
                        val target = mainVideo(app, loaded)
                        if (autoPlay && target != null && app.isTop(quick)) {
                            app.refresh(quick)
                            app.push(target.second)
                            return@async
                        }
                        // Fetch the streams you'd most likely play next, so Play best is quick,
                        // but only once you've stayed on this title (not while browsing past it).
                        target?.first?.let { vid ->
                            Net.later(1_500, Runnable { if (app.isTop(quick)) Streams.prefetch(app, loaded.type, vid) })
                        }
                    }.onFailure { e ->
                        loadingRow.label = "Couldn't load full details: ${Net.describe(e).let { d -> if (d == e.javaClass.simpleName) e.message ?: d else d }}"
                    }
                    app.refresh(quick)
                }
            }
        }
        return quick
    }

    /** Opens a title from Continue watching or the Library, where only the basics were saved. */
    fun screenFor(app: MainActivity, id: String, type: String, name: String, year: String, autoPlay: Boolean = false): Screen? {
        val from = Home.allAddons().firstOrNull { it.supports("meta", type, id) } ?: Home.allAddons().firstOrNull() ?: return null
        return screen(app, from, Meta(id, type, name, year, "", "", emptyList(), ""), autoPlay)
    }

    /**
     * What "play this title" means: a film (resuming if started); for a show, the episode in progress,
     * the next one, or the first one not yet watched. Returns its video id and the Play best screen.
     */
    private fun mainVideo(app: MainActivity, f: Full): Pair<String, Screen>? {
        val isShow = f.videos.any { it.season > 0 || it.episode > 0 } && f.type != "movie"
        if (!isShow) {
            if (f.videos.isNotEmpty()) return null   // a collection: pick a part yourself
            val w = watchFor(f, null)
            val at = History.resumeAt(app, f.id)
            return f.id to Streams.playBest(app, f.type, f.id, f.name, f.runtime, w, at)
        }
        val list = ordered(f).filter { !upcoming(it.released) }
        val v = History.item(app, f.id)?.let { item -> list.firstOrNull { it.id == item.videoId } }
            ?: list.firstOrNull { History.progress(app, it.id)?.done != true }
            ?: list.firstOrNull()
            ?: return null
        val at = History.resumeAt(app, v.id)
        return v.id to Streams.playBest(app, f.type, v.id, "${f.name} ${epLabel(v)}", f.runtime, watchFor(f, v), at)
    }

    /** Full details for a title from whichever add-on can describe it (background threads only). */
    fun loadFull(type: String, id: String): Full {
        val from = Home.allAddons().firstOrNull { it.supports("meta", type, id) }
            ?: throw IllegalStateException("none of your add-ons can describe this title")
        return fetch(from, type, id)
    }

    /** One episode's screen, and playing it, for lists outside the show's own screens (New episodes). */
    fun episodeScreen(app: MainActivity, f: Full, v: Video): Screen =
        episode(app, f, if (v.season > 0) "Season ${v.season}" else "Specials", v)

    fun playEpisode(app: MainActivity, f: Full, v: Video): Screen =
        Streams.playBest(app, f.type, v.id, "${f.name} ${epLabel(v)}", f.runtime, watchFor(f, v), History.resumeAt(app, v.id))

    fun isUpcoming(date: String) = upcoming(date)

    fun epLabel(v: Video) = MetaParse.epLabel(v)

    private fun ordered(f: Full) = MetaParse.ordered(f)

    /** Every video in Stremio's order (season, episode, release date), as its watched marks expect. */
    private fun stremioOrder(f: Full) = MetaParse.stremioOrder(f)

    /** What to file the playback under; for an episode, also which episode comes next. */
    private fun watchFor(f: Full, v: Video?): History.Watch {
        if (v == null) return History.Watch(f.id, f.type, f.name, f.year, f.id, "", f.runtime, null, null, emptyList())
        val list = ordered(f)
        val next = list.getOrNull(list.indexOfFirst { it.id == v.id }.let { if (it < 0) -1 else it + 1 })
            ?.takeIf { !upcoming(it.released) }
        return History.Watch(
            f.id, f.type, f.name, f.year, v.id, (epLabel(v) + " " + v.title).trim(), f.runtime,
            next?.id, next?.let { (epLabel(it) + " " + it.title).trim() },
            stremioOrder(f),
        )
    }

    /** "Resume from …" (when there's a saved position), "Play best", "Choose a stream". */
    private fun playRows(app: MainActivity, f: Full, v: Video?, title: String): List<Row> {
        val w = watchFor(f, v)
        val at = History.resumeAt(app, w.videoId)
        val rows = mutableListOf<Row>()
        if (at > 0) {
            rows += Row("Resume from ${History.clock(at)}", "►", opens = { Streams.playBest(app, f.type, w.videoId, title, f.runtime, w, at) })
            rows += Row("Play best from the start", opens = { Streams.playBest(app, f.type, w.videoId, title, f.runtime, w, 0) })
        } else {
            rows += Row("Play best", "►", opens = { Streams.playBest(app, f.type, w.videoId, title, f.runtime, w, 0) })
        }
        rows += Row("Choose a stream", opens = { Streams.screen(app, f.type, w.videoId, title, w, at) })
        if (app.isPhone) rows += Row("Download", if (Offline.has(app, w.videoId)) "in Downloads  ►" else "►", opens = { if (Offline.has(app, w.videoId)) Offline.screen(app) else Offline.chooseScreen(app, w, title) })
        return rows
    }

    private fun libraryRows(app: MainActivity, f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Ready.rowsFor(app, f)
        lateinit var lib: Row
        lib = Row(if (History.inLibrary(app, f.id)) "♡ Remove from favourites" else "Add to favourites", action = {
            val added = History.toggleLibrary(app, History.Saved(f.id, f.type, f.name, f.year))
            lib.label = if (added) "♡ Remove from favourites" else "Add to favourites"
            app.toast(if (added) "Added to favourites" + (if (f.type == "series") ": following new episodes" else "") else "Removed from favourites")
            app.refreshTop()
        })
        rows += BasketList.row(app, f)
        TvRemote.sendRow(app, f)?.let { rows += it }
        // The next dated thing (a coming episode, or the film's release) into the phone's calendar.
        val nextEp = if (f.type == "series") Releases.upcoming(f, 3650).firstOrNull() else null
        when {
            nextEp != null -> CalendarExport.row(app, "${f.name} ${epLabel(nextEp)}", nextEp.released, "Stremonino: ${f.name}")
            f.type == "movie" && f.releasedDay > Releases.day() -> CalendarExport.row(app, "${f.name} out", f.releasedDay, "Stremonino: ${f.name}")
            else -> null
        }?.let { rows += it }
        rows += lib
        NextList.row(app, f)?.let { rows += it }
        if (f.type == "series") {
            // Following is separate from the Library: shows you've started are followed automatically.
            lateinit var fol: Row
            // OK goes round: every new episode → new seasons only → not following.
            fun folLabel() = when {
                !Follow.isFollowed(app, f.id) -> "Follow: tell me about new episodes or seasons"
                Follow.mode(app, f.id) == Following.EPISODES -> "Following: new episodes and seasons ✓ (OK: seasons only)"
                else -> "Following: new seasons only ✓ (OK: stop)"
            }
            fol = Row(folLabel(), action = {
                when {
                    !Follow.isFollowed(app, f.id) -> {
                        Follow.follow(app, f.id, f.name, Following.EPISODES); Waiting.askToNotify(app)
                        app.toast("Following ${f.name}: new episodes and seasons come to Ready")
                    }
                    Follow.mode(app, f.id) == Following.EPISODES -> {
                        Follow.follow(app, f.id, f.name, Following.SEASONS)
                        app.toast("Following ${f.name}: only a new season comes to Ready")
                    }
                    else -> { Follow.unfollow(app, f.id); app.toast("Stopped following ${f.name}") }
                }
                fol.label = folLabel()
                app.refreshTop()
            })
            rows += fol
        }
        if (f.type == "movie" && History.isWatched(app, f.id)) rows += Row("Remove from watched", action = {
            History.unwatch(app, f.id, f.type, f.id, "", emptyList())
            app.toast("${f.name} is no longer marked as watched")
            app.refreshTop()
        })
        if (f.type == "movie") {
            lateinit var wait: Row
            fun waitLabel() = when {
                Follow.followsFilm(app, f.id) -> "Waiting until it's out to stream ✓ (stop)"
                Waiting.has(app, f.id) -> "Stop waiting for a stream"
                else -> "Tell me when a stream is available"
            }
            wait = Row(waitLabel(), action = {
                when {
                    Follow.followsFilm(app, f.id) -> { Follow.unfollowFilms(app, listOf(f.id)); app.toast("Stopped following ${f.name}") }
                    Waiting.has(app, f.id) -> { Waiting.remove(app, f.id); app.toast("Removed from Waiting") }
                    else -> {
                        // Not out to stream yet: it's followed until it is, then waits for a good stream.
                        app.toast("Checking…")
                        Net.async({ FilmOut.check(Home.allAddons(), Settings.tmdbKey(app), Settings.watchRegion(app), f.id) }) { r ->
                            val (out, note) = r.getOrDefault(true to "")
                            if (out) { Waiting.add(app, Waiting.Entry(f.id, f.type, f.name, f.year, 0)); app.toast("Added to Waiting") }
                            else {
                                Follow.followFilms(app, listOf(Following.Film(f.id, f.name, f.year, note)))
                                Waiting.askToNotify(app)
                                app.toast("Not out to stream yet ($note): added to Waiting")
                            }
                            wait.label = waitLabel(); app.refreshTop()
                        }
                    }
                }
                wait.label = waitLabel()
                app.refreshTop()
            })
            rows += wait
            // Played it but the picture wasn't great? Keep an eye out for a better release.
            lateinit var better: Row
            fun betterLabel() = if (Waiting.watchingForBetter(app, f.id)) "Watching for a better version ✓ (stop)" else "Tell me when a better version appears"
            better = Row(betterLabel(), action = {
                if (Waiting.watchingForBetter(app, f.id)) {
                    Waiting.remove(app, f.id); app.toast("Stopped watching for a better version")
                    better.label = betterLabel(); app.refreshTop()
                } else {
                    app.toast("Checking what's there now…")
                    Waiting.addBetter(app, f.id, f.type, f.name, f.year) { have ->
                        app.toast("I'll tell you when something better than $have appears")
                        better.label = betterLabel(); wait.label = waitLabel(); app.refreshTop()
                    }
                }
            })
            rows += better
        }
        if (History.item(app, f.id) != null) {
            lateinit var cw: Row
            cw = Row("Remove from Continue watching", action = {
                History.removeItem(app, f.id)
                cw.label = "Removed from Continue watching"
                app.refreshTop()
            })
            rows += cw
        }
        return rows
    }

    private fun rows(app: MainActivity, f: Full): List<Row> {
        val rows = mutableListOf<Row>()
        val isShow = f.videos.any { it.season > 0 || it.episode > 0 } && f.type != "movie"

        // What you'll want first goes at the top: the way in to watch.
        if (isShow) {
            History.item(app, f.id)?.let { item ->
                val v = f.videos.firstOrNull { it.id == item.videoId }
                if (v != null && !upcoming(v.released)) {
                    val title = "${f.name} ${epLabel(v)}"
                    val at = History.resumeAt(app, v.id)
                    val label = when {
                        item.upNext -> "Next: ${epLabel(v)}  ${v.title}"
                        at > 0 -> "Resume ${epLabel(v)} from ${History.clock(at)}"
                        else -> "Continue: ${epLabel(v)}  ${v.title}"
                    }
                    rows += Row(label, "►", opens = { Streams.playBest(app, f.type, v.id, title, f.runtime, watchFor(f, v), at) })
                    rows += Row("────────────────────")
                }
            }
            val bySeason = f.videos.groupBy { it.season }
            val seasons = bySeason.keys.filter { it > 0 }.sorted() + bySeason.keys.filter { it <= 0 }
            for (s in seasons) {
                val eps = bySeason.getValue(s).sortedBy { it.episode }
                val name = if (s > 0) "Season $s" else "Specials"
                val aired = eps.count { !upcoming(it.released) }
                val watched = eps.count { History.progress(app, it.id)?.done == true }
                val count = when {
                    watched == eps.size -> "all ${eps.size} watched ✓"
                    watched > 0 -> "$watched of ${eps.size} watched"
                    aired == eps.size -> "${eps.size} episodes"
                    else -> "$aired of ${eps.size} aired"
                }
                rows += Row(name, "$count  ►", opens = { episodes(app, f, name, eps) })
            }
            if (app.isPhone) rows += Row("Download episodes", "choose  ►", opens = { Offline.episodesScreen(app, f) { v -> watchFor(f, v) } })
        } else if (f.videos.isNotEmpty()) {
            // A film collection: each part is a film of its own, in release order, with ✓ on the ones watched.
            val marks = History.marks(app)
            rows += Row("Films in this collection: ${f.videos.size}")
            SearchRank.parts(f.videos).forEach { v ->
                val name = v.title.ifEmpty { v.id }
                val own = v.id.startsWith("tt") || v.id.startsWith("tmdb:")
                rows += Row(Marks.title(marks[v.id], name), v.released.take(4), opens = {
                    if (own) screenFor(app, v.id, "movie", name, v.released.take(4)) ?: Streams.screen(app, "movie", v.id, name)
                    else Streams.screen(app, "movie", v.id, name)
                }, play = {
                    if (own) screenFor(app, v.id, "movie", name, v.released.take(4), autoPlay = true)?.let { app.push(it) }
                    else app.push(Streams.screen(app, "movie", v.id, name))
                })
            }
        } else {
            rows += playRows(app, f, null, f.name)
        }
        val collection = !isShow && f.videos.isNotEmpty()
        rows += Row("────────────────────")
        if (!collection) {
            rows += libraryRows(app, f)
            rows += Row("────────────────────")
        }

        rows += Row(info("Type", if (isShow) "Series" else if (collection) "Collection" else Catalogs.typeLabel(f.type).removeSuffix("s")))
        if (f.year.isNotBlank()) rows += Row(info("Year", f.year))
        val scores = Ratings.known(f.id)
        if (scores != null && scores.line.isNotEmpty()) Ratings.lines(scores, maxOf(24, app.columns() - 20)).forEachIndexed { i, l -> rows += Row(info(if (i == 0) "Ratings" else "", l)) }
        else if (f.rating.isNotBlank()) rows += Row(info("IMDb rating", "★ ${f.rating}"))
        if (scores != null && scores.age.isNotEmpty()) rows += Row(info("Age rating", scores.age))
        if (f.runtime.isNotBlank()) rows += Row(info("Runtime", f.runtime))
        if (f.genres.isNotEmpty()) rows += Row(info("Genres", f.genres.joinToString(", ")))
        if (f.director.isNotEmpty()) rows += Row(info("Director", f.director.take(3).joinToString(", ")))
        if (f.cast.isNotEmpty()) rows += Row(info("Cast", f.cast.take(4).joinToString(", ")))
        DetailExtras.facts(f, DetailExtras.today()).filter { !(it.first == "Age rating" && scores?.age?.isNotEmpty() == true) }.forEach { (k, v) -> rows += Row(info(k, v)) }
        DetailExtras.history(History.infos(app).firstOrNull { it.id == f.id }).forEach { (k, v) -> rows += Row(info(k, v)) }
        if (f.imdbUrl.isNotBlank()) rows += LinkOut.row(app, "IMDb page", "IMDb: ${f.name}", f.imdbUrl)
        Trailers.row(app, f)?.let { rows += it }
        rows += watchRows(app, f)
        if (f.description.isNotBlank()) {
            rows += Row("────────────────────")
            Catalogs.wrap(f.description, app.columns()).forEach { rows += Row(it) }
        }
        rows += Row("────────────────────")
        rows += Row(info("Details from", f.source))
        rows += moreLikeThis(app, f)
        return rows
    }

    /** "More like this" at the bottom of a title's page (TMDB's recommendations; needs the key). */
    private fun moreLikeThis(app: MainActivity, f: Full): List<Row> {
        if ((f.type != "movie" && f.type != "series") || Settings.tmdbKey(app).isBlank()) return emptyList()
        val head = Row("──── More like this ────")
        val list = ForYou.knownSimilar(f.id) ?: return listOf(head, Row("Finding similar titles…"))
        val marks = History.marks(app)
        val allowed = list.filter { KidsGuard.allows(app, it.id) }
        if (allowed.isEmpty()) return listOf(head, Row("Nothing similar found."))
        return listOf(head) + allowed.map { p ->
            lateinit var r: Row
            r = Row(Marks.title(marks[p.id], p.name) + if (p.year.isNotEmpty()) " (${p.year})" else "", Catalogs.typeLabel(p.type).removeSuffix("s") + "  ►",
                opens = { screenFor(app, p.id, p.type, p.name, p.year) ?: Screen(p.name, listOf(Row("Your add-ons are still loading."))) },
                play = { BasketList.toggle(app, p.id, p.type, p.name, p.year); r.label = Marks.title(History.marks(app)[p.id], p.name) + if (p.year.isNotEmpty()) " (${p.year})" else ""; app.refreshTop() })
            r
        }
    }

    private fun lookUpSimilar(app: MainActivity, f: Full, screen: Screen) {
        val key = Settings.tmdbKey(app)
        if (key.isBlank() || (f.type != "movie" && f.type != "series") || ForYou.knownSimilar(f.id) != null) return
        Net.async({ ForYou.findSimilar(key, f.type, f.id, f.name) }) {
            // Keeps your place: only the rows change.
            if (app.inStack(screen)) { screen.rows = rows(app, f); if (app.isTop(screen)) app.refresh(screen) }
        }
    }

    /** Where to watch: how it was first released and where it's on now (needs your TMDB key). */
    private fun watchRows(app: MainActivity, f: Full): List<Row> {
        if (f.type != "movie" && f.type != "series") return emptyList()
        val region = Settings.watchRegion(app)
        val where = WhereToWatch.regionName(region)
        val head = Row("──── Where to watch ($where) ────")
        if (Settings.tmdbKey(app).isBlank()) return listOf(head, Row("Add a TMDB key to see this", "►", opens = { Settings.whereToWatch(app) }))
        val w = WhereToWatch.known(f.type, f.id, region)
            ?: return listOf(head, Row(WhereToWatch.error(f.type, f.id, region)?.let { "Couldn't look it up: $it" } ?: "Looking it up…"))
        val rows = mutableListOf(head)
        w.released.forEach { (k, v) -> rows += Row(info(if (f.type == "movie") "Released: $k" else k, v)) }
        fun line(label: String, names: List<String>) { if (names.isNotEmpty()) rows += Row(info(label, names.joinToString(", "))) }
        line("Included with", w.included)
        line("Free", w.free)
        line("Rent", w.rent)
        line("Buy", w.buy)
        if (w.included.isEmpty() && w.free.isEmpty() && w.rent.isEmpty() && w.buy.isEmpty()) rows += Row("Not on any $where service just now.")
        if (w.link.isNotBlank()) rows += LinkOut.row(app, "All the options", "Where to watch: ${f.name}", w.link)
        rows += Row("Where-to-watch data from JustWatch, via TMDB.")
        return rows
    }

    /** Review scores and the age rating, from MDBList with your key (kept 12 hours). */
    private fun lookUpRatings(app: MainActivity, f: Full, screen: Screen) {
        val key = Settings.mdblistKey(app)
        if (key.isBlank() || !f.id.startsWith("tt") || Ratings.known(f.id) != null) return
        Net.async({ Ratings.load(key, f.type, f.id) }) { r ->
            if (r.getOrNull() != null && app.isTop(screen)) { screen.rows = rows(app, f); app.refresh(screen) }
        }
    }

    /** Looks up Where to watch once the details are in (a quick TMDB call; kept a day). */
    private fun lookUpWatch(app: MainActivity, f: Full, screen: Screen) {
        val key = Settings.tmdbKey(app)
        val region = Settings.watchRegion(app)
        if (key.isBlank() || (f.type != "movie" && f.type != "series") || WhereToWatch.known(f.type, f.id, region) != null) return
        Net.async({ WhereToWatch.load(key, f.type, f.id, f.imdbUrl, region) }) {
            if (app.isTop(screen)) { screen.rows = rows(app, f); app.refresh(screen) }
        }
    }

    /** ✓ for watched, time left for started. */
    private fun mark(app: MainActivity, v: Video): String {
        val p = History.progress(app, v.id) ?: return ""
        return when {
            p.done -> "✓"
            p.position > 60_000 && p.duration > 0 -> "${History.clock(p.duration - p.position)} left"
            p.position > 60_000 -> "started"
            else -> ""
        }
    }

    private fun episodes(app: MainActivity, f: Full, seasonName: String, eps: List<Video>): Screen {
        fun build() = eps.map { v ->
            val num = "E" + v.episode.toString().padStart(2, '0')
            val title = v.title.ifEmpty { "Episode ${v.episode}" }
            val date = when {
                v.released.isBlank() -> ""
                upcoming(v.released) -> "${v.released}  upcoming"
                else -> v.released
            }
            Row(
                "$num  $title", listOf(mark(app, v), date).filter { it.isNotEmpty() }.joinToString("   "),
                opens = { episode(app, f, seasonName, v) },
                play = {
                    app.push(Streams.playBest(app, f.type, v.id, "${f.name} ${epLabel(v)}", f.runtime, watchFor(f, v), History.resumeAt(app, v.id)))
                },
            )
        }
        val screen = Screen("${f.name} › $seasonName", build())
        // Start on the first episode not yet watched.
        screen.position = eps.indexOfFirst { History.progress(app, it.id)?.done != true }.coerceAtLeast(0)
        screen.onShow = {
            screen.rows = build()
            app.refresh(screen)
        }
        return screen
    }

    private fun episode(app: MainActivity, f: Full, seasonName: String, v: Video): Screen {
        val label = epLabel(v)
        fun build(): List<Row> {
            val rows = playRows(app, f, v, "${f.name} $label").toMutableList()
            TvRemote.sendRow(app, f, v)?.let { rows += it }
            if (upcoming(v.released)) CalendarExport.row(app, "${f.name} $label", v.released, "Stremonino: ${f.name}")?.let { rows += it }
            rows += Row("────────────────────")
            rows += Row(info("Episode", "$seasonName, episode ${v.episode}"))
            if (v.released.isNotBlank()) rows += Row(info("Released", v.released + if (upcoming(v.released)) " (upcoming)" else ""))
            mark(app, v).takeIf { it.isNotEmpty() }?.let { rows += Row(info("Watched", if (it == "✓") "yes ✓" else it)) }
            if (mark(app, v) == "✓") rows += Row("Remove from watched", action = {
                History.unwatch(app, f.id, f.type, v.id, label, stremioOrder(f))
                app.toast("$label is no longer marked as watched")
                app.refreshTop()
            })
            if (v.overview.isNotBlank()) {
                rows += Row("────────────────────")
                Catalogs.wrap(v.overview, app.columns()).forEach { rows += Row(it) }
            }
            return rows
        }
        val screen = Screen("${f.name} › $label  ${v.title}", build())
        screen.onShow = {
            screen.rows = build()
            app.refresh(screen)
            Net.later(1_500, Runnable { if (app.isTop(screen)) Streams.prefetch(app, f.type, v.id) })
        }
        return screen
    }

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun upcoming(date: String): Boolean =
        date.length == 10 && date > day.format(Date())
}
