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

    /** A locked profile with an age limit: titles over it (or not rated yet) are left out and won't play. */
    fun ageLimited(c: Context) = state(c).let { it.isLocked(Profiles.current(c)?.id) && it.maxAge > 0 }

    /** This profile may see and play [id] (always, unless locked with an age limit). */
    fun allows(c: Context, id: String) = state(c).allows(Profiles.current(c)?.id, AgeRatings.of(id.substringBefore(':')))

    /** Search, Live TV and News are hidden right now. */
    fun hides(c: Context) = state(c).let { it.isLocked(Profiles.current(c)?.id) && it.hideExtras }

    // ---- bedtime and the daily limit ([KidsTime]) -------------------------------------------------

    private fun timeKey(c: Context) = "time_" + (Profiles.current(c)?.id ?: "")
    private fun timeRaw(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(timeKey(c), null)
    private fun putTime(c: Context, v: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(timeKey(c), v).apply() }

    fun blockedNow(c: Context) = KidsTime.blocked(state(c), Profiles.current(c)?.id, timeRaw(c))
    fun timeSummary(c: Context) = KidsTime.summary(state(c), Profiles.current(c)?.id, timeRaw(c))
    fun addTime(c: Context, ms: Long) { if (locked(c)) putTime(c, KidsTime.add(timeRaw(c), ms)) }

    /** Plays [then] now, or (at bedtime, or the day's time used up) after the parent PIN, which allows the rest of today. */
    fun checkTime(app: MainActivity, then: () -> Unit) {
        val why = blockedNow(app) ?: return then()
        app.toast(why)
        askPin(app, "$why. Parent PIN to allow today") { putTime(app, KidsTime.withOverride(timeRaw(app))); then() }
    }

    private var stopper: Runnable? = null

    /** While the player is open: closes it when bedtime comes or the day's time runs out. */
    fun arm(app: MainActivity) {
        stopper?.let { Net.cancel(it) }; stopper = null
        val left = KidsTime.leftMs(state(app), Profiles.current(app)?.id, timeRaw(app)) ?: return
        val r = Runnable {
            stopper = null
            if (!app.playerOpen) return@Runnable
            app.toast(blockedNow(app) ?: "Time's up for today")
            runCatching { app.finishActivity(Player.REQUEST_PLAY) }
            Net.later(3_000, Runnable {
                if (app.playerOpen) runCatching {
                    app.startActivity(android.content.Intent(app, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
        stopper = r
        Net.later(left + 1_000, r)
    }

    fun disarm() { stopper?.let { Net.cancel(it) }; stopper = null }

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
            .setView(boxed(app, email, pass, Secret.toggle(app, pass)))
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

    /**
     * Manage profiles › a profile › Kids lock. Changes are made freely and only kept with Save
     * changes, which asks the parent PIN once (locking needs no PIN; leaving unsaved drops them).
     */
    fun screen(app: MainActivity, p: Profiles.Profile): Screen {
        lateinit var screen: Screen
        // Changes not saved yet (null = none).
        var draft: KidsLock.State? = null
        fun redraw(): Unit = run { screen.rows = build(app, p, draft) { draft = it; redraw() }; app.refresh(screen) }
        screen = Screen("Kids lock", emptyList())
        screen.rows = build(app, p, null) { draft = it; redraw() }
        screen.onHide = {
            if (draft != null) { draft = null; app.toast("Kids lock changes not saved") }
        }
        screen.position = 3
        return screen
    }

    private fun build(app: MainActivity, p: Profiles.Profile, draft: KidsLock.State?, edit: (KidsLock.State?) -> Unit): List<Row> {
        val saved = state(app)
        val s = draft ?: saved
        val rows = mutableListOf<Row>()
        if (!saved.hasPin) {
            rows += Row("Choose a PIN and lock ${p.name}", action = {
                askNewPin(app) { pin -> save(app, saved.withPin(pin).lock(p.id)); app.toast("${p.name} is locked"); edit(null) }
            })
            return rows
        }
        if (draft != null) {
            rows += Row("Save changes", "parent PIN  ►", action = {
                askPin(app, "Parent PIN to save") { save(app, draft); app.toast("Kids lock saved"); edit(null) }
            })
            rows += Row("Discard changes", action = { edit(null) })
            rows += Row("────────────────────")
        }
        rows += if (s.isLocked(p.id)) Row(info(p.name, "locked"), "unlock", action = { edit(s.unlock(p.id)) })
        else Row(info(p.name, "not locked"), "lock", action = {
            // Locking is always allowed straight away (it only makes things stricter).
            if (draft == null) { save(app, saved.lock(p.id)); app.toast("${p.name} is locked"); edit(null) } else edit(s.lock(p.id))
        })
        rows += Row("In locked profiles, hide Search, Live TV and News", if (s.hideExtras) "on" else "off", action = { edit(s.withHide(!s.hideExtras)) })
        rows += Row("Age limit in locked profiles", KidsLock.ageLabel(s.maxAge) + "  ►", action = {
            if (Settings.tmdbKey(app).isBlank()) { app.toast("The age limit needs a TMDB key (Settings) for the ratings"); return@Row }
            edit(s.withAge(KidsLock.AGES[(KidsLock.AGES.indexOf(s.maxAge).coerceAtLeast(0) + 1) % KidsLock.AGES.size]))
        })
        rows += Row("Bedtime in locked profiles", KidsTime.bedtimeLabel(s) + "  ►", action = { edit(s.withBedtime(KidsTime.next(KidsTime.BEDTIMES, s.bedtime))) })
        if (s.bedtime >= 0) rows += Row("Allowed again from", KidsTime.clock(s.wake) + "  ►", action = { edit(s.withWake(KidsTime.next(KidsTime.WAKES, s.wake))) })
        rows += Row("Daily watch limit in locked profiles", KidsTime.dailyLabel(s.dailyMins) + "  ►", action = { edit(s.withDaily(KidsTime.next(KidsTime.DAILY, s.dailyMins))) })
        rows += Row("Change the parent PIN", action = {
            askPin(app, "Current PIN") { askNewPin(app) { pin -> save(app, (draft ?: state(app)).withPin(pin)); app.toast("PIN changed"); edit(null) } }
        })
        val others = Profiles.all(app).filter { s.isLocked(it.id) && it.id != p.id }.map { it.name }
        if (others.isNotEmpty()) rows += Row("Also locked: " + others.joinToString(", "))
        return rows
    }
}
