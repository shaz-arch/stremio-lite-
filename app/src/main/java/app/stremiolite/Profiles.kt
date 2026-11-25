package app.stremiolite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profiles: each one is a Stremio account signed in on this device, with its own add-ons,
 * Continue watching, Library and watched marks (all synced by Stremio). No server of our own.
 */
object Profiles {
    private const val PREFS = "profiles"

    class Profile(val id: String, val name: String, val authKey: String, val email: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Profile> {
        migrate(c)
        val arr = runCatching { JSONArray(prefs(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Profile(it.optString("id"), it.optString("name"), it.optString("key"), it.optString("email")) }
        }
    }

    private fun write(c: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("key", it.authKey).put("email", it.email)) }
        prefs(c).edit().putString("list", arr.toString()).apply()
    }

    fun current(c: Context): Profile? {
        val list = all(c)
        val id = prefs(c).getString("current", null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun select(c: Context, id: String) {
        prefs(c).edit().putString("current", id).apply()
    }

    /** Adds a profile (or updates one already signed in to the same account) and makes it current. */
    /** Set by "Sign in again": the next sign-in renews this profile instead of making a new one. */
    var renewing: String? = null

    /** Stremio's reply when a saved sign-in has been ended (signed out elsewhere, password changed). */
    fun isExpired(e: Throwable) = e is Stremio.ApiError && e.message?.contains("session", ignoreCase = true) == true

    /** Opens the sign-in screen to renew profile [id]'s sign-in. */
    fun signInAgain(app: MainActivity, id: String): Screen {
        renewing = id
        return SignIn.screen(app, addingProfile = true)
    }

    fun add(c: Context, name: String, authKey: String, email: String): Profile {
        val list = all(c).toMutableList()
        val renew = renewing?.let { r -> list.firstOrNull { it.id == r } }
        renewing = null
        val same = renew ?: list.firstOrNull { (email.isNotEmpty() && it.email.equals(email, true)) || it.authKey == authKey }
        val id = same?.id ?: "p" + System.currentTimeMillis().toString(36)
        if (same == null) current(c)?.let { copySettings(c, it.id, id) }
        val label = name.ifBlank { same?.name ?: email.substringBefore('@').ifBlank { "Profile ${list.size + 1}" } }
        list.removeAll { it.id == id }
        list += Profile(id, label, authKey, email.ifEmpty { same?.email ?: "" })
        write(c, list)
        select(c, id)
        return list.last()
    }

    fun rename(c: Context, id: String, name: String) {
        write(c, all(c).map { if (it.id == id) Profile(it.id, name, it.authKey, it.email) else it })
    }

    fun updateEmail(c: Context, email: String) {
        val cur = current(c) ?: return
        write(c, all(c).map { if (it.id == cur.id) Profile(it.id, it.name.ifBlank { email.substringBefore('@') }, it.authKey, email) else it })
    }

    fun remove(c: Context, id: String) {
        write(c, all(c).filter { it.id != id })
        if (prefs(c).getString("current", null) == id) prefs(c).edit().remove("current").apply()
    }

    /** A profile's own settings store; the first profile keeps the original one from before profiles existed. */
    fun prefsFor(c: Context, base: String): android.content.SharedPreferences {
        val id = current(c)?.id ?: "main"
        return c.getSharedPreferences(if (id == "main") base else "$base-$id", Context.MODE_PRIVATE)
    }

    /** A profile's own settings (not just the current one's). */
    fun settingsOf(c: Context, id: String): android.content.SharedPreferences =
        c.getSharedPreferences(if (id == "main") "settings" else "settings-$id", Context.MODE_PRIVATE)

    /** A new profile starts with a copy of the settings of the profile it was made from. */
    private fun copySettings(c: Context, fromId: String, toId: String) {
        for (base in listOf("settings", "search")) {
            val from = c.getSharedPreferences(if (fromId == "main") base else "$base-$fromId", Context.MODE_PRIVATE)
            val to = c.getSharedPreferences(if (toId == "main") base else "$base-$toId", Context.MODE_PRIVATE).edit().clear()
            from.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> to.putBoolean(k, v)
                    is Int -> to.putInt(k, v)
                    is Long -> to.putLong(k, v)
                    is Float -> to.putFloat(k, v)
                    is String -> to.putString(k, v)
                    is Set<*> -> to.putStringSet(k, v.filterIsInstance<String>().toSet())
                }
            }
            to.apply()
        }
    }

    /** Each profile's own files; the first profile keeps the original names from before profiles existed. */
    fun fileName(c: Context, base: String): String {
        val id = current(c)?.id ?: "main"
        return if (id == "main") "$base.json" else "$base-$id.json"
    }

    /** The single account from before profiles becomes profile "main". */
    private fun migrate(c: Context) {
        val p = prefs(c)
        if (p.getBoolean("migrated", false)) return
        val old = c.getSharedPreferences("account", Context.MODE_PRIVATE)
        val key = old.getString("authKey", null)
        if (key != null) {
            val email = old.getString("email", "") ?: ""
            val arr = JSONArray().put(JSONObject().put("id", "main").put("name", email.substringBefore('@').ifBlank { "Me" })
                .put("key", key).put("email", email))
            p.edit().putString("list", arr.toString()).putString("current", "main").apply()
        }
        p.edit().putBoolean("migrated", true).apply()
    }

    // ---- screens ----------------------------------------------------------------------------

    /** "Who's watching?" (shown at start-up when there's more than one profile, and from Home). */
    fun chooser(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val cur = current(app)
            val rows = mutableListOf<Row>()
            all(app).forEach { p ->
                rows += Row(p.name, if (p.id == cur?.id) "current" else "", action = { switchTo(app, p.id) })
            }
            rows += Row("────────────────────")
            rows += Row("Add a profile", "►", opens = { addScreen(app) })
            rows += Row("Manage profiles", "►", opens = { manageScreen(app) })
            rows += Row("────────────────────")
            rows += Row("Games", "2048, Minesweeper, noughts & crosses, film quiz  ►", opens = { GamesScreen.screen(app) })
            return rows
        }
        screen = Screen("Who's watching?", build())
        screen.position = all(app).indexOfFirst { it.id == current(app)?.id }.coerceAtLeast(0)
        screen.onShow = { screen.rows = build(); app.refresh(screen) }
        return screen
    }

    fun switchTo(app: MainActivity, id: String) {
        History.flush()
        select(app, id)
        History.reset()
        Home.forget()
        app.applyLook()   // this profile's theme and text size
        app.resetTo(Home.screen(app))
    }

    private fun addScreen(app: MainActivity) = Screen(
        "Add a profile",
        listOf(
            Row("Create a new profile here", "►", opens = { createScreen(app) }),
            Row("   (makes a new free Stremio account, and can copy your add-ons into it)"),
            Row("Add an existing Stremio account (code on your phone)", "►", opens = { SignIn.screen(app, addingProfile = true) }),
            Row("Add an existing account with email and password", action = { app.askEmailAndPassword() }),
        ),
    )

    /** New Stremio account from the stick: agree to the terms, type the details, then optionally copy add-ons. */
    private fun createScreen(app: MainActivity): Screen = Screen(
        "New profile",
        listOf(
            Row("A profile is a free Stremio account of its own."),
            Row("Creating one means agreeing to Stremio's Terms of Service"),
            Row("and Privacy Policy (stremio.com/tos and stremio.com/privacy)."),
            Row("────────────────────"),
            Row("I agree. Create the account", "►", action = { askNewAccount(app) }),
            Row("Cancel", action = { app.back() }),
        ),
    ).also { it.position = 4 }

    private fun askNewAccount(app: MainActivity) {
        val name = android.widget.EditText(app).apply { hint = "Profile name (e.g. Kids)"; setSingleLine() }
        val email = android.widget.EditText(app).apply {
            hint = "Email for the new account"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine()
        }
        val password = android.widget.EditText(app).apply {
            hint = "Password (8+ characters)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val box = android.widget.LinearLayout(app).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(name); addView(email); addView(password)
        }
        android.app.AlertDialog.Builder(app)
            .setTitle("New profile")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val n = name.text.toString().trim()
                val e = email.text.toString().trim()
                val pw = password.text.toString()
                if (e.isEmpty() || pw.length < 8) {
                    app.toast("Enter an email and a password of at least 8 characters.")
                    return@setPositiveButton
                }
                val from = current(app)
                app.toast("Creating the account…")
                Net.async({ Stremio.register(e, pw) }) { r ->
                    r.onSuccess { key ->
                        if (from != null) app.push(copyOffer(app, from, n, key, e))
                        else app.signedIn(key, e, n)
                    }.onFailure { err ->
                        app.toast("Couldn't create it: " + (if (err is Stremio.ApiError) err.message else Net.describe(err)))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** After creating an account: copy the current profile's add-ons into it, or start empty. */
    private fun copyOffer(app: MainActivity, from: Profile, name: String, key: String, email: String) = Screen(
        "Account created",
        listOf(
            Row("Copy add-ons from ${from.name} into ${name.ifBlank { "the new profile" }}?"),
            Row("────────────────────"),
            Row("Yes, copy them (recommended)", action = {
                app.toast("Copying add-ons…")
                Net.async({ copyAddons(from.authKey, key) }) { r ->
                    r.onFailure { app.toast("Couldn't copy add-ons: ${Net.describe(it)}. You can try again in Manage profiles.") }
                    app.signedIn(key, email, name)
                }
            }),
            Row("No, start with Stremio's defaults", action = { app.signedIn(key, email, name) }),
        ),
    ).also { it.position = 2 }

    /** Copies one account's add-on list (in order, with their settings) onto another. */
    /**
     * Copies one account's add-ons to another. If Stremio says the list is too big (large
     * AIOMetadata setups), the big add-ons are copied in slimmed form; the app still reads
     * their full row lists live from the add-on.
     */
    /** Says which side of a copy failed: "from" (reading) or "to" (writing). */
    private class CopyError(val profileName: String, cause: Throwable) : Exception(cause.message, cause)

    private fun copyAddons(fromKey: String, toKey: String) {
        val addons = try {
            JSONObject(Stremio.addonsJson(fromKey)).optJSONArray("addons") ?: JSONArray()
        } catch (e: Exception) { throw CopyError("from", e) }
        try {
            Stremio.setAddons(toKey, addons)
        } catch (e: Stremio.ApiError) {
            if (isExpired(e)) throw CopyError("to", e)
            if (e.message?.contains("size", ignoreCase = true) != true) throw e
            for (i in 0 until addons.length()) {
                val o = addons.optJSONObject(i) ?: continue
                o.optJSONObject("manifest")?.let { o.put("manifest", Stremio.slim(it)) }
            }
            Stremio.setAddons(toKey, addons)
        }
    }

    /** Shown when a profile's saved sign-in no longer works. */
    fun expiredScreen(app: MainActivity, p: Profile) = Screen(
        "Sign-in expired",
        listOf(
            Row("${p.name}'s sign-in has ended on Stremio's side."),
            Row("This happens after signing out elsewhere or changing the password."),
            Row("Signing in again keeps the profile, its settings and history."),
            Row("────────────────────"),
            Row("Sign ${p.name} in again", "►", opens = { signInAgain(app, p.id) }),
            Row("Not now", action = { app.back() }),
        ),
    )

    private fun manageScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> = all(app).map { p -> Row(p.name, p.email + "  ►", opens = { profileScreen(app, p) }) }
            .ifEmpty { listOf(Row("No profiles yet.")) } + listOf(
            Row("────────────────────"),
            Row("Copy my add-ons to other accounts", "►", opens = { CloneScreen.screen(app) }),
        )
        screen = Screen("Manage profiles", build())
        screen.onShow = { screen.rows = build(); app.refresh(screen) }
        return screen
    }

    private fun profileScreen(app: MainActivity, p: Profile): Screen {
        val others = all(app).filter { it.id != p.id }
        val rows = mutableListOf(
            Row("Switch to ${p.name}", action = { switchTo(app, p.id) }),
            Row("Rename", action = {
                val input = android.widget.EditText(app).apply { setText(p.name); setSingleLine() }
                android.app.AlertDialog.Builder(app).setTitle("Rename profile").setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { rename(app, p.id, it); app.toast("Renamed"); app.back() }
                    }
                    .setNegativeButton("Cancel", null).show()
            }),
        )
        if (others.isNotEmpty()) {
            rows += Row("Copy add-ons from another profile", "►", opens = {
                Screen("Copy add-ons into ${p.name}", others.map { o ->
                    Row("From ${o.name}", "replaces ${p.name}'s add-ons", action = {
                        app.toast("Copying add-ons…")
                        Net.async({ copyAddons(o.authKey, p.authKey) }) { r ->
                            r.onSuccess {
                                app.toast("Copied ${o.name}'s add-ons into ${p.name}")
                                if (current(app)?.id == p.id) { Home.forget(); runCatching { Account.addonCache(app).delete() } }
                            }.onFailure { e ->
                                val who = (e as? CopyError)?.profileName?.let { side -> if (side == "from") o else p }
                                if (who != null && isExpired(e.cause ?: e)) {
                                    app.push(expiredScreen(app, who))
                                } else app.toast("Couldn't copy: ${Net.describe(e.cause ?: e)}")
                            }
                        }
                    })
                })
            })
        }
        if (others.isNotEmpty()) {
            rows += Row("Copy Live TV from another profile", "►", opens = {
                Screen("Copy Live TV into ${p.name}", others.map { o ->
                    val theirs = Settings.liveOf(settingsOf(app, o.id))
                    Row("From ${o.name}", theirs.summary() + if (theirs.isEmpty) "" else "  ►", action = {
                        if (theirs.isEmpty) { app.toast("${o.name} has no Live TV playlists or favourites"); return@Row }
                        val target = settingsOf(app, p.id)
                        val isCurrent = current(app)?.id == p.id
                        Settings.putLive(target, theirs.into(Settings.liveOf(target)), app, send = isCurrent)
                        app.toast("Added ${o.name}'s Live TV to ${p.name}: ${theirs.summary()}")
                        app.back()
                    })
                } + listOf(Row("────────────────────"), Row("Adds their playlists, favourites and guide link."), Row("${p.name} keeps everything it already has.")))
            })
        }
        rows += Row("Sign ${p.name} in again", "►", opens = { signInAgain(app, p.id) })
        rows += Row("Kids lock (parent PIN)", (if (KidsGuard.state(app).isLocked(p.id)) "locked" else "off") + "  ►", opens = { KidsGuard.screen(app, p) })
        rows += Row("────────────────────")
        rows += Row("Remove ${p.name} from this stick", action = {
            val wasCurrent = current(app)?.id == p.id
            if (wasCurrent) { History.clear(app); Account.clear(app) } else remove(app, p.id)
            app.toast("${p.name} removed from this stick (the Stremio account still exists)")
            if (wasCurrent) {
                History.reset(); Home.forget(); app.applyLook()
                app.resetTo(if (current(app) != null) Home.screen(app) else SignIn.screen(app))
            } else app.back()
        })
        return Screen(p.name, rows)
    }
}
