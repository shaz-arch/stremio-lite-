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
    fun add(c: Context, name: String, authKey: String, email: String): Profile {
        val list = all(c).toMutableList()
        val same = list.firstOrNull { (email.isNotEmpty() && it.email.equals(email, true)) || it.authKey == authKey }
        val id = same?.id ?: "p" + System.currentTimeMillis().toString(36)
        val label = name.ifBlank { same?.name ?: email.substringBefore('@').ifBlank { "Profile ${list.size + 1}" } }
        list.removeAll { it.id == id }
        list += Profile(id, label, authKey, email)
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
                rows += Row(p.name, (if (p.id == cur?.id) "current  " else "") + p.email, action = { switchTo(app, p.id) })
            }
            rows += Row("────────────────────")
            rows += Row("Add a profile", "►", opens = { addScreen(app) })
            rows += Row("Manage profiles", "►", opens = { manageScreen(app) })
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
    private fun copyAddons(fromKey: String, toKey: String) {
        val addons = JSONObject(Stremio.addonsJson(fromKey)).optJSONArray("addons") ?: JSONArray()
        Stremio.setAddons(toKey, addons)
    }

    private fun manageScreen(app: MainActivity): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> = all(app).map { p -> Row(p.name, p.email + "  ►", opens = { profileScreen(app, p) }) }
            .ifEmpty { listOf(Row("No profiles yet.")) }
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
                            }.onFailure { app.toast("Couldn't copy: ${Net.describe(it)}") }
                        }
                    })
                })
            })
        }
        rows += Row("────────────────────")
        rows += Row("Remove ${p.name} from this stick", action = {
            val wasCurrent = current(app)?.id == p.id
            if (wasCurrent) { History.clear(app); Account.clear(app) } else remove(app, p.id)
            app.toast("${p.name} removed from this stick (the Stremio account still exists)")
            if (wasCurrent) {
                History.reset(); Home.forget()
                app.resetTo(if (current(app) != null) Home.screen(app) else SignIn.screen(app))
            } else app.back()
        })
        return Screen(p.name, rows)
    }
}
