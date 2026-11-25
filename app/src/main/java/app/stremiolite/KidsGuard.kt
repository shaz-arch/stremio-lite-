package app.stremiolite

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

/** Android side of [KidsLock]: the PIN prompts, the per-profile lock screen, and the guard on Home's way out. */
object KidsGuard {
    private const val PREFS = "kidslock"

    fun state(c: Context): KidsLock.State = KidsLock.read(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null))

    private fun save(c: Context, s: KidsLock.State) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", s.json()).apply()
    }

    /** The profile in use is locked. */
    fun locked(c: Context) = state(c).isLocked(Profiles.current(c)?.id)

    /** Search, Live TV and News are hidden right now. */
    fun hides(c: Context) = state(c).let { it.isLocked(Profiles.current(c)?.id) && it.hideExtras }

    /** Runs [then] straight away, or after the parent PIN if this profile is locked. */
    fun guard(app: MainActivity, then: () -> Unit) {
        if (!locked(app)) then() else askPin(app, "Parent PIN", then)
    }

    private fun pinBox(app: MainActivity, hint: String) = EditText(app).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine()
    }

    private fun boxed(app: MainActivity, vararg views: android.view.View) = LinearLayout(app).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 24, 48, 0)
        views.forEach { addView(it) }
    }

    /** Asks for the parent PIN, then runs [then] if it's right. */
    fun askPin(app: MainActivity, title: String, then: () -> Unit) {
        KidsLock.waitSeconds()?.let { app.toast("Too many wrong PINs. Try again in $it seconds."); return }
        val pin = pinBox(app, "PIN")
        AlertDialog.Builder(app)
            .setTitle(title)
            .setView(boxed(app, pin))
            .setPositiveButton("OK") { _, _ ->
                if (KidsLock.attempt(state(app), pin.text.toString())) then()
                else app.toast(KidsLock.waitSeconds()?.let { "Wrong PIN. Wait $it seconds before trying again." } ?: "Wrong PIN")
            }
            .setNeutralButton("Forgot PIN") { _, _ -> forgot(app) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks for a new PIN twice, then calls [done] with it. */
    private fun askNewPin(app: MainActivity, done: (String) -> Unit) {
        val a = pinBox(app, "New PIN (4 to 8 digits)")
        val b = pinBox(app, "Same PIN again")
        AlertDialog.Builder(app)
            .setTitle("Choose a parent PIN")
            .setView(boxed(app, a, b))
            .setPositiveButton("Save") { _, _ ->
                val p = a.text.toString()
                when {
                    !KidsLock.validPin(p) -> app.toast("A PIN is 4 to 8 digits")
                    p != b.text.toString() -> app.toast("The two PINs didn't match")
                    else -> done(p)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * A forgotten PIN: signing in with the email and password of any Stremio account on this device
     * clears the lock (a child wouldn't know them).
     */
    private fun forgot(app: MainActivity) {
        val email = EditText(app).apply { hint = "Email of an account on this device"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setSingleLine() }
        val pass = EditText(app).apply { hint = "Its Stremio password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine() }
        AlertDialog.Builder(app)
            .setTitle("Clear the kids lock")
            .setView(boxed(app, email, pass))
            .setPositiveButton("Clear it") { _, _ ->
                val e = email.text.toString().trim()
                if (Profiles.all(app).none { it.email.equals(e, ignoreCase = true) }) { app.toast("That account isn't signed in on this device"); return@setPositiveButton }
                app.toast("Checking…")
                Net.async({ Stremio.login(e, pass.text.toString()) }) { r ->
                    // A fresh sign-in key we don't need: end it again.
                    r.onSuccess { key ->
                        Net.async({ runCatching { Stremio.logout(key) } }) {}
                        save(app, KidsLock.NONE)
                        app.toast("Kids lock cleared. Set a new PIN in Manage profiles.")
                        app.resetTo(Home.screen(app))
                    }.onFailure { app.toast("Couldn't sign in: ${if (it is Stremio.ApiError) it.message else Net.describe(it)}") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Manage profiles › a profile › Kids lock. */
    fun screen(app: MainActivity, p: Profiles.Profile): Screen {
        lateinit var screen: Screen
        fun build(): List<Row> {
            val s = state(app)
            val rows = mutableListOf(
                Row("A locked profile needs the parent PIN to leave it"),
                Row("(Switch profile, Sign out) and to open Settings."),
                Row("────────────────────"),
            )
            if (!s.hasPin) {
                rows += Row("Choose a PIN and lock ${p.name}", action = {
                    askNewPin(app) { pin -> save(app, s.withPin(pin).lock(p.id)); app.toast("${p.name} is locked"); screen.rows = build(); app.refresh(screen) }
                })
            } else {
                rows += if (s.isLocked(p.id)) Row(info(p.name, "locked"), "unlock", action = {
                    askPin(app, "Parent PIN to unlock ${p.name}") { save(app, state(app).unlock(p.id)); app.toast("${p.name} is unlocked"); screen.rows = build(); app.refresh(screen) }
                }) else Row(info(p.name, "not locked"), "lock", action = {
                    save(app, s.lock(p.id)); app.toast("${p.name} is locked"); screen.rows = build(); app.refresh(screen)
                })
                rows += Row("In locked profiles, hide Search, Live TV and News", if (s.hideExtras) "on" else "off", action = {
                    askPin(app, "Parent PIN") { save(app, state(app).withHide(!state(app).hideExtras)); screen.rows = build(); app.refresh(screen) }
                })
                rows += Row("Change the parent PIN", action = {
                    askPin(app, "Current PIN") { askNewPin(app) { pin -> save(app, state(app).withPin(pin)); app.toast("PIN changed") } }
                })
                val others = Profiles.all(app).filter { s.isLocked(it.id) && it.id != p.id }.map { it.name }
                if (others.isNotEmpty()) rows += Row("Also locked: " + others.joinToString(", "))
            }
            rows += Row("────────────────────")
            rows += Row("What a locked profile can find depends on its own add-ons and rows,")
            rows += Row("so give it a kids' rows config. The lock is on this device only.")
            return rows
        }
        screen = Screen("Kids lock", build())
        screen.position = 3
        return screen
    }
}
