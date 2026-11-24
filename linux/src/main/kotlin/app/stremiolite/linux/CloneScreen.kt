package app.stremiolite.linux

import app.stremiolite.Clone
import app.stremiolite.Stremio

/** "Copy my add-ons to other accounts": your profiles and/or accounts you sign into just for this. */
object CloneScreen {
    /** An account signed into only for this copy: the password stays in memory until the copy, then is dropped. */
    private class Extra(val email: String, var password: String)

    fun screen(): Screen {
        val me = Profiles.current()
        val fromKey = Account.authKey ?: return Screen("Copy my add-ons", listOf(Row("Sign in first.")))
        val others = Profiles.all().filter { it.id != me?.id }
        val picked = HashSet<String>()
        val extras = mutableListOf<Extra>()
        var mode = Clone.Mode.ADD
        var withStreams = false
        lateinit var s: Screen

        fun build() {
            val rows = mutableListOf(
                Row(info("From", (me?.name ?: "this account") + " (in use now)")),
                Row("──── Send to ────"),
            )
            others.forEach { p ->
                rows += Row(p.name, (if (p.id in picked) "✓ yes" else "no") + "  " + p.email, action = {
                    if (!picked.remove(p.id)) picked += p.id
                    build()
                })
            }
            extras.forEach { e -> rows += Row(e.email, "✓ yes · signed in once", action = { extras.remove(e); build() }) }
            rows += Row("Another Stremio account (sign in once)", "►", action = {
                App.ask("Their Stremio email") { email ->
                    if (email.isNullOrBlank()) return@ask
                    App.ask("Password for ${email.trim()} (used once, never saved)", hidden = true) { pw ->
                        if (pw.isNullOrEmpty()) return@ask
                        extras += Extra(email.trim(), pw)
                        build()
                    }
                }
            })
            rows += Row("──── How ────")
            rows += Row("Their add-ons", "◄ ${if (mode == Clone.Mode.ADD) "Keep them, add mine" else "Replace with mine"} ►", action = {
                mode = if (mode == Clone.Mode.ADD) Clone.Mode.REPLACE else Clone.Mode.ADD; build()
            })
            rows += Row("Stream add-ons", "◄ ${if (withStreams) "Send mine" else "Leave out"} ►", action = { withStreams = !withStreams; build() })
            if (withStreams) rows += Row("   Careful: yours hold your debrid key. They'd be using your account and its limits.")
            else if (mode == Clone.Mode.REPLACE) rows += Row("   Their own stream add-ons are kept.")
            val n = picked.size + extras.size
            rows += Row("────────────────────")
            rows += Row(if (n == 0) "Pick at least one account above" else "Copy to $n account${if (n == 1) "" else "s"}", if (n == 0) "" else "►", action = {
                if (n == 0) return@Row
                run(fromKey, others.filter { it.id in picked }, extras.toList(), mode, withStreams)
                extras.forEach { it.password = "" }
                extras.clear()
            })
            s.rows = rows
            App.refresh()
        }
        s = Screen("Copy my add-ons", emptyList())
        s.onShow = { build() }
        return s
    }

    private fun run(fromKey: String, profiles: List<Profiles.Profile>, extras: List<Extra>, mode: Clone.Mode, withStreams: Boolean) {
        val lines = mutableListOf<Row>()
        val result = Screen("Copying add-ons", listOf(Row("Working… this takes a few seconds per account.")))
        App.push(result)
        val jobs: List<Pair<String, () -> String?>> = profiles.map { p -> p.name to { Profiles.keyOf(p) } } +
            extras.map { e -> val pw = e.password; e.email to { Stremio.login(e.email, pw) } }
        fun next(i: Int) {
            if (i >= jobs.size) {
                result.rows = lines + listOf(Row("────────────────────"), Row("Done. Their devices pick it up next time Stremonino or Stremio opens."))
                App.refresh()
                return
            }
            val (name, key) = jobs[i]
            val isExtra = i >= profiles.size
            App.async({
                val k = key() ?: throw IllegalStateException("not signed in on this computer")
                try { Clone.send(fromKey, k, mode, withStreams) } finally { if (isExtra) Stremio.logout(k) }
            }) { r ->
                lines += r.fold(
                    { res ->
                        Row(name, (if (mode == Clone.Mode.ADD) "${res.sent} added" else "${res.sent} of yours") + " · ${res.total} in all" +
                            if (res.skippedStreams > 0) " · stream add-ons left out" else "")
                    },
                    { e -> Row(name, "couldn't: " + if (e is Stremio.ApiError) (e.message ?: "refused") else (e.message ?: Net.describe(e))) },
                )
                result.rows = lines + Row("Working…")
                App.refresh()
                next(i + 1)
            }
        }
        next(0)
    }
}
