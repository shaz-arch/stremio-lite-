package app.stremiolite.linux

import app.stremiolite.AioRows
import app.stremiolite.StreamSetup

/** Settings › Your streams: create an AIOStreams install tuned for Stremonino, with your debrid services. */
object StreamsSetup {
    fun screen(): Screen {
        val existing = Home.all().filter { StreamSetup.isAioStreams(it) }
        var replace = existing.isNotEmpty()
        lateinit var s: Screen
        fun build(): List<Row> {
            val k = Prefs.debridKeys
            val rows = mutableListOf(
                Row("Makes an AIOStreams install with the stick-tuned settings, on ElfHosted's free server,"),
                Row("with your debrid services, and adds it to your Stremio account."),
                Row("──── Your debrid services ────"),
            )
            val order = k.mapNotNull { StreamSetup.service(it.first) } + StreamSetup.SERVICES.filter { svc -> k.none { it.first == svc.id } }
            order.forEach { svc ->
                val at = k.indexOfFirst { it.first == svc.id }
                val detail = when {
                    at < 0 -> "not set"
                    k.size == 1 -> "…" + k[at].second.takeLast(4)
                    at == 0 -> "…" + k[at].second.takeLast(4) + " · first"
                    else -> "…" + k[at].second.takeLast(4) + " · backup $at"
                }
                rows += Row(svc.name, "$detail  ►", opens = {
                    RowsSetup.keyScreen("${svc.name} key", svc.keyPage, listOf(svc.hint),
                        { Prefs.debridKeys.firstOrNull { it.first == svc.id }?.second.orEmpty() },
                        { v ->
                            Prefs.debridKeys = StreamSetup.withKey(Prefs.debridKeys, svc.id, v)
                            if (v.isNotBlank()) KeysCheck.quick("${svc.name} key") { app.stremiolite.KeyCheck.debrid(svc.id, v) }
                        })
                })
            }
            if (k.size > 1) {
                rows += Row("Change the order", "►", opens = {
                    Reorder(head = { listOf(Row("The first is used first; the others are backups.")) },
                        items = Prefs.debridKeys.toMutableList(),
                        label = { StreamSetup.service(it.first)?.name ?: it.first },
                        detail = { "…" + it.second.takeLast(4) },
                        open = { null },
                        commit = { Prefs.debridKeys = it },
                    ).build("Order of your services")
                })
                rows += Row("   Every service is checked for ready-to-play copies; if a stream fails, the next one is tried, from any of them.")
            } else rows += Row("   Add a second service as a backup if you have one.")
            val current = existing.firstOrNull { StreamSetup.installOf(it.transportUrl) != null }
            rows += Row("────────────────────")
            if (current != null) {
                rows += Row("Update my streams with these keys", "►", action = {
                    if (Prefs.debridKeys.isEmpty()) App.toast("Add a debrid key first")
                    else App.ask("Your AIOStreams password (the one you chose when your streams were made)", hidden = true) { v ->
                        if (!v.isNullOrEmpty()) update(current.transportUrl, Prefs.debridKeys, v)
                    }
                })
                rows += Row("   Same install: only the debrid services change. Needs its password.")
                rows += Row("────────────────────")
            }
            if (existing.isNotEmpty()) rows += Row((if (replace) "✓ " else "   ") + "Replace my current AIOStreams", existing.size.toString(), action = {
                replace = !replace; s.rows = build(); App.refresh()
            })
            rows += Row("Create my streams", if (current != null) "a new install  ►" else "►", action = {
                if (Prefs.debridKeys.isEmpty()) { App.toast("Add a debrid key first") }
                else if (SetupWizard.password != null) create(Prefs.debridKeys, SetupWizard.password!!, replace)
                else App.ask("Choose an AIOStreams password (6+ characters)", hidden = true) { v ->
                    val pw = v.orEmpty()
                    if (pw.isEmpty()) return@ask
                    if (pw.length < 6) App.toast("Use at least 6 characters") else { SetupWizard.offer(pw); create(Prefs.debridKeys, pw, replace) }
                }
            })
            return rows
        }
        s = Screen("Your streams (AIOStreams)", emptyList())
        s.rows = build()
        s.onShow = { s.rows = build(); App.refresh() }
        return s
    }

    private fun update(link: String, keys: List<Pair<String, String>>, password: String) {
        val status = Screen("Updating your streams", listOf(Row("Saving your debrid services into your AIOStreams…")))
        App.push(status)
        App.async({ StreamSetup.update(link, password, keys) }) { r ->
            status.rows = r.fold(
                { listOf(Row("Done: your streams now use ${StreamSetup.names(keys).joinToString(", then ")}."), Row("Nothing else changed. Every device signed in uses them.")) },
                { e -> listOf(Row("Couldn't update: " + (if (e is AioRows.Failed) e.message else Net.describe(e)))) },
            )
            App.refresh()
        }
    }

    private fun create(keys: List<Pair<String, String>>, password: String, replace: Boolean) {
        val key = Account.authKey ?: return
        val status = Screen("Creating your streams", listOf(Row("Creating your AIOStreams… (this can take a minute)")))
        App.push(status)
        App.async({
            val url = StreamSetup.create(keys, password)
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
                    Row("Try again", action = { App.back(); create(keys, password, replace) }),
                )
            }
            App.refresh()
        }
    }
}
