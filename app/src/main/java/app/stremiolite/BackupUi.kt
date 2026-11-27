package app.stremiolite

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/** Settings › Backup ([Backup]): this device's own settings, locked with your passphrase, kept on your account. */
object BackupUi {
    private const val KIND = "android"
    /** The settings files backed up: the profile's own ones, and the device's kids lock. */
    private fun files(c: Context): List<Pair<String, android.content.SharedPreferences>> = listOf(
        "settings" to Profiles.prefsFor(c, "settings"),
        "aio" to Profiles.prefsFor(c, "aio"),
        "kidslock" to c.getSharedPreferences("kidslock", Context.MODE_PRIVATE),
        "speed" to c.getSharedPreferences("speed", Context.MODE_PRIVATE),
    )

    private fun saved(c: Context) = Backup.read(History.rawRemote(c, Backup.ID), KIND)

    fun row(app: MainActivity) = Row("Back up this device's settings", (saved(app)?.let { "saved " + day(it.at) + "  " } ?: "") + "►", opens = { screen(app) })

    private fun day(ms: Long) = java.text.SimpleDateFormat("d MMM", java.util.Locale.UK).format(java.util.Date(ms))

    fun screen(app: MainActivity): Screen {
        lateinit var s: Screen
        fun build(): List<Row> {
            val b = saved(app)
            val rows = mutableListOf<Row>()
            rows += Row(if (b == null) "Back up now" else "Back up again (replaces the last one)", action = { backUp(app) { s.rows = build(); app.refresh(s) } })
            if (b != null) {
                rows += Row(info("Last backup", day(b.at) + ", from " + b.device))
                rows += Row("Restore from the backup", action = { restore(app, b) })
                rows += Row("Delete the backup", action = {
                    History.putRemote(app, Backup.record(History.rawRemote(app, Backup.ID), KIND, null)); app.toast("Backup deleted"); s.rows = build(); app.refresh(s)
                })
            }
            rows += Row("────────────────────")
            rows += Row("Your keys, kids lock, drives, player and other settings only this device has.")
            rows += Row("Locked with a passphrase only you know: forget it and the backup can't be opened.")
            return rows
        }
        s = Screen("Backup", build())
        s.onShow = { History.pull(app) { s.rows = build(); app.refresh(s) } }
        return s
    }

    private fun passBox(app: MainActivity, hint: String) = EditText(app).apply { this.hint = hint; setSingleLine() }

    private fun backUp(app: MainActivity, done: () -> Unit) {
        if (Account.authKey(app) == null) { app.toast("Sign in first"); return }
        val a = passBox(app, "Passphrase (8 or more characters)")
        val b = passBox(app, "Same again")
        val box = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(a); addView(b); addView(Secret.toggle(app, a)); Secret.hide(b) }
        AlertDialog.Builder(app).setTitle("Lock the backup").setView(box)
            .setPositiveButton("Back up") { _, _ ->
                val p = a.text.toString()
                when {
                    p.length < Backup.MIN_PASS -> app.toast("At least ${Backup.MIN_PASS} characters")
                    p != b.text.toString() -> app.toast("The two didn't match")
                    else -> {
                        val all = JSONObject()
                        files(app).forEach { (name, prefs) -> all.put(name, Backup.prefsToJson(prefs.all, Backup.SKIP)) }
                        app.toast("Locking it…")
                        Net.async({ Backup.seal(all.toString(), p) }) { r ->
                            r.onSuccess { blob ->
                                History.putRemote(app, Backup.record(History.rawRemote(app, Backup.ID), KIND, Backup.Saved(System.currentTimeMillis(), Build.MODEL ?: "device", blob)))
                                app.toast("✓ Backed up to your account"); done()
                            }.onFailure { app.toast("Couldn't back up: ${it.message}") }
                        }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun restore(app: MainActivity, b: Backup.Saved) {
        val a = passBox(app, "Passphrase")
        val box = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(a); addView(Secret.toggle(app, a)) }
        AlertDialog.Builder(app).setTitle("Restore the backup").setView(box)
            .setPositiveButton("Restore") { _, _ ->
                val p = a.text.toString()
                app.toast("Unlocking…")
                Net.async({ Backup.open(b.blob, p) ?: throw IllegalArgumentException() }) { r ->
                    r.onSuccess { plain -> apply(app, JSONObject(plain)) }
                        .onFailure { app.toast("Wrong passphrase") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun apply(app: MainActivity, all: JSONObject) {
        for ((name, prefs) in files(app)) {
            val o = all.optJSONObject(name) ?: continue
            val e = prefs.edit()
            for (k in o.keys()) {
                val v = o.optJSONObject(k) ?: continue
                when (v.optString("t")) {
                    "s" -> e.putString(k, v.optString("v"))
                    "b" -> e.putBoolean(k, v.optBoolean("v"))
                    "i" -> e.putInt(k, v.optInt("v"))
                    "l" -> e.putLong(k, v.optLong("v"))
                    "f" -> e.putFloat(k, v.optDouble("v").toFloat())
                    "ss" -> v.optJSONArray("v")?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                }
            }
            e.apply()
        }
        app.toast("✓ Restored. Reloading…")
        app.applyLook()
        Home.reload()
        app.resetTo(Home.screen(app))
    }
}

/** Watched › Search my history: anything played on this account (any device), by name. */
object HistorySearch {
    fun start(app: MainActivity) {
        val input = EditText(app).apply { hint = "Title"; setSingleLine() }
        AlertDialog.Builder(app).setTitle("Search my history").setView(LinearLayout(app).apply { setPadding(48, 24, 48, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { app.push(results(app, it)) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun results(app: MainActivity, q: String): Screen {
        val day = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK)
        val found = HistoryMatch.find(History.infos(app), q)
        val rows = if (found.isEmpty()) listOf(Row("Nothing you've played matches \"$q\".")) else found.map { i ->
            val d = listOfNotNull(if (i.type == "series") "show" else "film", i.lastWatched.takeIf { it > 0 }?.let { day.format(java.util.Date(it)) }).joinToString("   ")
            Row(i.name, "$d  ►", opens = { Details.screenFor(app, i.id, i.type, i.name, "") ?: Screen(i.name, listOf(Row("Your add-ons are still loading. Try again in a moment."))) })
        }
        return Screen("History: $q", rows)
    }
}

/** Settings › About › What's new, and once after each update. */
object WhatsNewUi {
    fun screen(now: Int): Screen {
        val rows = mutableListOf<Row>()
        WhatsNew.upTo(now).forEach { n ->
            rows += Row("──── Build ${n.build} ────")
            n.lines.forEach { l -> wrapWords(l, 60).forEachIndexed { i, part -> rows += Row((if (i == 0) "• " else "  ") + part) } }
        }
        if (rows.isEmpty()) rows += Row("Nothing noted yet.")
        return Screen("What's new", rows)
    }

    /** After an update (not a first install): show the notes once. */
    fun maybeShow(app: MainActivity) {
        val p = app.getSharedPreferences("whatsnew", Context.MODE_PRIVATE)
        val now = BuildConfig.VERSION_CODE
        val seen = p.getInt("seen", 0)
        p.edit().putInt("seen", now).apply()
        if (seen == 0 || seen >= now || WhatsNew.since(seen, now).isEmpty()) return
        app.push(screen(now))
    }
}
