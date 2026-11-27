package app.stremiolite

import android.net.Uri
import android.widget.EditText

/** Settings › Your add-ons › Find add-ons: browse add-on lists, set one up on its page, install it. */
object FinderUi {
    private const val SITE_ADDON = "https://stremio-addons.net/addons/stremio-addons.net"

    fun row(app: MainActivity) = Row("Find add-ons", "community lists  ►", opens = { screen(app) })

    fun screen(app: MainActivity): Screen {
        val sources = AddonFinder.sources(Home.allAddons())
        val rows = mutableListOf<Row>()
        if (sources.isEmpty()) {
            rows += Row("None of your add-ons offer an add-on list.")
        } else {
            rows += Row("Search every list", "►", action = { ask(app, "Search add-ons") { q -> app.push(results(app, sources, q)) } })
            rows += Row("──── Lists ────")
            sources.forEach { s -> rows += Row(s.label, "►", opens = { results(app, listOf(s), "") }) }
        }
        rows += Row("────────────────────")
        rows += LinkOut.row(app, "Add the stremio-addons.net list", "stremio-addons.net", SITE_ADDON)
        return Screen("Find add-ons", rows)
    }

    private fun installed(id: String) = Home.allAddons().any { it.id == id }

    private fun results(app: MainActivity, sources: List<AddonFinder.Source>, query: String): Screen {
        lateinit var s: Screen
        s = Screen(if (query.isBlank()) sources.singleOrNull()?.label ?: "Add-ons" else "Add-ons: $query", listOf(Row("Loading…")))
        var started = false
        var all: List<AddonFinder.Entry> = emptyList()
        var failed = false
        fun build() {
            val sort = Profiles.prefsFor(app, "settings").getInt("finderSort", 0)
            val rows = mutableListOf<Row>()
            rows += Row("Sort by", "◄ ${AddonFinder.SORTS[sort]} ►", action = {
                Profiles.prefsFor(app, "settings").edit().putInt("finderSort", (sort + 1) % AddonFinder.SORTS.size).apply(); build()
            })
            if (sources.size == 1 && query.isBlank()) rows += Row("Search this list", "►", action = { ask(app, "Search add-ons") { q -> app.push(results(app, sources, q)) } })
            if (all.isEmpty()) rows += Row(if (failed) "Couldn't load the list." else "Nothing found.")
            AddonFinder.sorted(all, sort).forEach { (heading, list) ->
                heading?.let { rows += Row("──── $it (${list.size}) ────") }
                list.forEach { e -> rows += Row((if (installed(e.id)) "✓ " else "") + e.name, e.what.ifEmpty { "►" }, opens = { entry(app, e) }) }
            }
            s.rows = rows
            if (app.isTop(s)) app.refresh(s)
        }
        s.onShow = {
            if (!started) {
                started = true
                Net.async({ sources.flatMap { runCatching { AddonFinder.list(it) }.getOrDefault(emptyList()) }.distinctBy { it.transportUrl } }) { r ->
                    failed = r.isFailure
                    all = r.getOrDefault(emptyList()).filter { AddonFinder.matches(it, query) }
                    build()
                }
            }
        }
        return s
    }

    private fun entry(app: MainActivity, e: AddonFinder.Entry): Screen {
        val rows = mutableListOf<Row>()
        val cols = app.columns()
        if (installed(e.id)) rows += Row("✓ Installed on your account")
        if (e.configurable || e.configurationRequired) {
            rows += LinkOut.row(app, (if (e.configurationRequired) "1. " else "") + "Set it up on its page", "Set up ${e.name}", e.configureUrl)
            rows += if (app.isPhone) Row("   Its Install button there comes back here to add it.")
                else Row("   Install it from your phone, then Reload my rows and add-ons.")
            rows += Row((if (e.configurationRequired) "2. " else "") + "Or paste its install link", "►", action = { Home.askInstall(app, false) })
        }
        if (!e.configurationRequired) rows += Row(if (e.configurable) "Install as it is" else "Install", "►", action = { install(app, e.transportUrl) })
        rows += Row("────────────────────")
        if (e.what.isNotEmpty()) rows += infoRows("Gives", e.what, cols)
        if (e.version.isNotEmpty()) rows += Row(info("Version", e.version))
        rows += Row(info("Server", Uri.parse(e.transportUrl).host ?: ""))
        if (e.description.isNotEmpty()) { rows += Row("────────────────────"); Catalogs.wrap(e.description, cols).forEach { rows += Row(it) } }
        return Screen(e.name, rows)
    }

    /** Installs [link] on the account and reloads (also used for stremio:// links opened on the phone). */
    fun install(app: MainActivity, link: String) {
        val key = Account.authKey(app) ?: run { app.toast("Sign in first"); return }
        app.toast("Installing…")
        Net.async({ Stremio.installAddon(key, link) }) { r ->
            r.onSuccess { name -> app.toast("Installed: $name"); Home.reload() }
                .onFailure { e -> app.toast("Couldn't install: " + if (e is Stremio.ApiError) e.message else Net.describe(e)) }
        }
    }

    /** A stremio://…/manifest.json link (an add-on page's Install button): ask, then install. */
    fun open(app: MainActivity, data: Uri?) {
        if (data?.scheme?.lowercase() != "stremio") return
        val link = data.toString()
        if (!link.substringBefore('?').endsWith("manifest.json")) return
        val shown = link.replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "").substringBefore('/')
        app.push(Screen("Install an add-on?", listOf(
            Row("An add-on page asked to install an add-on from $shown."),
            Row("Install it on your Stremio account", "►", action = { app.back(); install(app, link) }),
            Row("Don't install", action = { app.back() }),
        )))
    }

    private fun ask(app: MainActivity, title: String, done: (String) -> Unit) {
        val input = EditText(app).apply { setSingleLine() }
        PhoneTyping.show(app, android.app.AlertDialog.Builder(app).setTitle(title)
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null), input, title)
    }
}
