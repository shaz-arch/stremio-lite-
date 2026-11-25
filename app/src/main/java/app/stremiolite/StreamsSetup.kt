package app.stremiolite

import android.text.InputType
import android.widget.EditText

/** Settings › Your streams: create an AIOStreams install tuned for Stremonino, with your TorBox key. */
object StreamsSetup {
    fun screen(app: MainActivity): Screen {
        var torbox = ""
        val existing = Home.allAddons().filter { StreamSetup.isAioStreams(it) }
        var replace = existing.isNotEmpty()
        lateinit var s: Screen
        fun build(): List<Row> = listOfNotNull(
            Row("Makes an AIOStreams install with the stick-tuned settings,"),
            Row("on ElfHosted's free server, using your TorBox account,"),
            Row("and adds it to your Stremio account."),
            Row("──── You need ────"),
            Row("TorBox key", (if (torbox.isBlank()) "not set" else "set ✓") + "  ►", opens = {
                RowsSetup.keyScreen(app, "TorBox key", StreamSetup.TORBOX_KEY_PAGE, listOf(
                    "On torbox.app: Settings, then copy your API key.",
                ), { torbox }, { torbox = it })
            }),
            if (existing.isNotEmpty()) Row((if (replace) "✓ " else "   ") + "Replace my current AIOStreams", existing.size.toString(), action = {
                replace = !replace; s.rows = build(); app.refresh(s)
            }) else null,
            Row("────────────────────"),
            Row("Create my streams", "►", action = {
                if (torbox.isBlank()) app.toast("Add your TorBox key first")
                else askPassword(app) { pw -> create(app, torbox, pw, replace) }
            }),
        )
        s = Screen("Your streams (AIOStreams)", emptyList())
        s.rows = build()
        s.onShow = { s.rows = build(); app.refresh(s) }
        return s
    }

    private fun askPassword(app: MainActivity, done: (String) -> Unit) {
        val pw = EditText(app).apply {
            hint = "Choose a password (6+ characters)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
        }
        android.app.AlertDialog.Builder(app)
            .setTitle("AIOStreams password")
            .setMessage("Needed to change these settings later on AIOStreams' page.")
            .setView(PhoneTyping.box(app, pw, Secret.toggle(app, pw)))
            .setPositiveButton("Create") { _, _ ->
                val v = pw.text.toString()
                if (v.length < 6) app.toast("Use at least 6 characters") else done(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun create(app: MainActivity, torbox: String, password: String, replace: Boolean) {
        val key = Account.authKey(app) ?: return
        val status = Screen("Creating your streams", listOf(Row("Creating your AIOStreams… (this can take a minute)")))
        app.push(status)
        Net.async({
            val url = StreamSetup.create(torbox, password)
            url to StreamSetup.install(key, url, replace)
        }) { r ->
            r.onSuccess { (_, removed) ->
                Home.forget()
                status.rows = listOfNotNull(
                    Row("Done. Your streams are on your Stremio account."),
                    if (removed > 0) Row("Your old AIOStreams was taken off the account.") else null,
                    Row("Every device signed in to it uses them."),
                    Row("Keep your password: it opens the settings on AIOStreams' page."),
                )
            }.onFailure { e ->
                status.rows = listOf(
                    Row("Couldn't create the streams: " + (if (e is AioRows.Failed) e.message else Net.describe(e))),
                    Row("Try again", action = { app.back(); create(app, torbox, password, replace) }),
                )
            }
            app.refresh(status)
        }
    }
}
