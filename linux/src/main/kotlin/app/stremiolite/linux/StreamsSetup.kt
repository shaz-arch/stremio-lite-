package app.stremiolite.linux

import app.stremiolite.AioRows
import app.stremiolite.StreamSetup

/** Settings › Your streams: create an AIOStreams install tuned for Stremonino, with your TorBox key. */
object StreamsSetup {
    fun screen(): Screen {
        var torbox = ""
        val existing = Home.all().filter { StreamSetup.isAioStreams(it) }
        var replace = existing.isNotEmpty()
        lateinit var s: Screen
        fun build(): List<Row> = listOfNotNull(
            Row("Makes an AIOStreams install with the stick-tuned settings,"),
            Row("on ElfHosted's free server, using your TorBox account,"),
            Row("and adds it to your Stremio account."),
            Row("──── You need ────"),
            Row("TorBox key", (if (torbox.isBlank()) "not set" else "set ✓") + "  ►", opens = {
                RowsSetup.keyScreen("TorBox key", StreamSetup.TORBOX_KEY_PAGE, listOf(
                    "On torbox.app: Settings, then copy your API key.",
                ), { torbox }, { torbox = it })
            }),
            if (existing.isNotEmpty()) Row((if (replace) "✓ " else "   ") + "Replace my current AIOStreams", existing.size.toString(), action = {
                replace = !replace; s.rows = build(); App.refresh()
            }) else null,
            Row("────────────────────"),
            Row("Create my streams", "►", action = {
                if (torbox.isBlank()) { App.toast("Add your TorBox key first") }
                else App.ask("Choose an AIOStreams password (6+ characters)", hidden = true) { v ->
                    val pw = v.orEmpty()
                    if (pw.isEmpty()) return@ask
                    if (pw.length < 6) App.toast("Use at least 6 characters") else create(torbox, pw, replace)
                }
            }),
        )
        s = Screen("Your streams (AIOStreams)", emptyList())
        s.rows = build()
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    private fun create(torbox: String, password: String, replace: Boolean) {
        val key = Account.authKey ?: return
        val status = Screen("Creating your streams", listOf(Row("Creating your AIOStreams… (this can take a minute)")))
        App.push(status)
        App.async({
            val url = StreamSetup.create(torbox, password)
            url to StreamSetup.install(key, url, replace)
        }) { r ->
            r.onSuccess { (_, removed) ->
                java.io.File(Store.dir, "addons.json").delete(); Home.forget(); Home.load(force = true)
                status.rows = listOfNotNull(
                    Row("Done. Your streams are on your Stremio account."),
                    if (removed > 0) Row("Your old AIOStreams was taken off the account.") else null,
                    Row("Every device signed in to it uses them."),
                    Row("Keep your password: it opens the settings on AIOStreams' page."),
                )
            }.onFailure { e ->
                status.rows = listOf(
                    Row("Couldn't create the streams: " + (if (e is AioRows.Failed) e.message else Net.describe(e))),
                    Row("Try again", action = { App.back(); create(torbox, password, replace) }),
                )
            }
            App.refresh()
        }
    }
}
