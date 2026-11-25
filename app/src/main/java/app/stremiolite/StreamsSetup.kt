package app.stremiolite

import android.text.InputType
import android.widget.EditText

/** Settings › Your streams: create an AIOStreams install tuned for Stremonino, with your debrid services. */
object StreamsSetup {
    fun screen(app: MainActivity): Screen {
        val existing = Home.allAddons().filter { StreamSetup.isAioStreams(it) }
        var replace = existing.isNotEmpty()
        lateinit var s: Screen
        fun keys() = Settings.debridKeys(app)
        fun build(): List<Row> {
            val k = keys()
            val rows = mutableListOf(
                Row("Makes an AIOStreams install with the stick-tuned settings,"),
                Row("on ElfHosted's free server, with your debrid services,"),
                Row("and adds it to your Stremio account."),
                Row("──── Your debrid services ────"),
            )
            // Set ones first, in your order, then the rest.
            val order = k.mapNotNull { StreamSetup.service(it.first) } + StreamSetup.SERVICES.filter { svc -> k.none { it.first == svc.id } }
            order.forEach { svc ->
                val at = k.indexOfFirst { it.first == svc.id }
                val detail = when {
                    at < 0 -> "not set"
                    k.size == 1 -> "…" + k[at].second.takeLast(4)
                    at == 0 -> "…" + k[at].second.takeLast(4) + " · first"
                    else -> "…" + k[at].second.takeLast(4) + " · backup ${at}"
                }
                rows += Row(svc.name, "$detail  ►", opens = {
                    RowsSetup.keyScreen(app, "${svc.name} key", svc.keyPage, listOf(svc.hint),
                        { keys().firstOrNull { it.first == svc.id }?.second.orEmpty() },
                        { v -> Settings.setDebridKeys(app, StreamSetup.withKey(keys(), svc.id, v)) })
                })
            }
            if (k.size > 1) {
                rows += Row("Change the order", "►", opens = {
                    Reorder(app, head = { listOf(Row("The first is used first; the others are backups.")) },
                        items = keys().toMutableList(),
                        label = { StreamSetup.service(it.first)?.name ?: it.first },
                        detail = { "…" + it.second.takeLast(4) },
                        open = { null },
                        commit = { Settings.setDebridKeys(app, it) },
                    ).build("Order of your services")
                })
                rows += Row("   Every service is checked for ready-to-play copies; if a stream")
                rows += Row("   fails, the next one is tried, from any of them.")
            } else rows += Row("   Add a second service as a backup if you have one.")
            if (existing.isNotEmpty()) rows += Row((if (replace) "✓ " else "   ") + "Replace my current AIOStreams", existing.size.toString(), action = {
                replace = !replace; s.rows = build(); app.refresh(s)
            })
            rows += Row("────────────────────")
            rows += Row("Create my streams", "►", action = {
                if (keys().isEmpty()) app.toast("Add a debrid key first")
                else askPassword(app) { pw -> create(app, keys(), pw, replace) }
            })
            return rows
        }
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

    private fun create(app: MainActivity, keys: List<Pair<String, String>>, password: String, replace: Boolean) {
        val key = Account.authKey(app) ?: return
        val status = Screen("Creating your streams", listOf(Row("Creating your AIOStreams… (this can take a minute)")))
        app.push(status)
        Net.async({
            val url = StreamSetup.create(keys, password)
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
                    Row("Try again", action = { app.back(); create(app, keys, password, replace) }),
                )
            }
            app.refresh(status)
        }
    }
}
