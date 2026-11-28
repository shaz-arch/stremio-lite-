package app.stremiolite.linux

import app.stremiolite.KidsLock
import app.stremiolite.Stremio
import java.io.File

/** Linux side of [KidsLock]: PIN prompts, the per-profile lock screen, and the guard on Home's way out. */
object KidsGuard {
    private fun file() = File(Store.root, "kidslock.json")

    fun state(): KidsLock.State = KidsLock.read(runCatching { file().readText() }.getOrNull())

    private fun write(s: KidsLock.State, updated: Long) {
        file().writeText(org.json.JSONObject(s.json()).put("updated", updated).toString())
        runCatching { file().setReadable(false, false); file().setReadable(true, true) }
    }

    private fun save(s: KidsLock.State) {
        val now = System.currentTimeMillis()
        write(s, now)
        // Every profile's account gets it, so the other devices signed in to them follow ([app.stremiolite.KidsSync]).
        val keys = keys()
        App.async({ app.stremiolite.KidsSync.push(keys, s, now) }) { }
    }

    /** Each profile's sign-in, by profile id. */
    private fun keys(): Map<String, String> = Profiles.all().mapNotNull { p ->
        runCatching { org.json.JSONObject(File(Profiles.dirFor(p.id), "account.json").readText()).optString("authKey") }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { p.id to it }
    }.toMap()

    private var syncedAt = 0L

    /**
     * Takes a newer kids lock from the profiles' accounts (set on another device), at most every few
     * minutes unless [force]; [changed] runs only if something did.
     */
    fun syncIn(force: Boolean = false, changed: () -> Unit = {}) {
        if (!force && System.currentTimeMillis() - syncedAt < 5 * 60_000L) return
        syncedAt = System.currentTimeMillis()
        val keys = keys()
        if (keys.isEmpty()) return
        val local = state()
        val at = runCatching { org.json.JSONObject(file().readText()).optLong("updated") }.getOrDefault(0L)
        App.async({ app.stremiolite.KidsSync.merge(local, at, app.stremiolite.KidsSync.pull(keys)) }) { r ->
            r.getOrNull()?.let { (s, t) -> write(s, t); changed() }
        }
    }

    fun locked() = state().isLocked(Profiles.current()?.id)
    fun rawState(): String? = runCatching { file().readText() }.getOrNull()
    fun restoreRaw(raw: String) = save(KidsLock.read(raw))
    fun ageLimited() = state().let { it.isLocked(Profiles.current()?.id) && it.maxAge > 0 }
    fun allows(id: String) = state().allows(Profiles.current()?.id, app.stremiolite.AgeRatings.of(id.substringBefore(':')))
    fun hides() = state().let { it.isLocked(Profiles.current()?.id) && it.hideExtras }

    // ---- bedtime and the daily limit ([app.stremiolite.KidsTime]) --------------------------------

    private val T = app.stremiolite.KidsTime
    private fun timeFile() = File(Store.root, "kidstime.json")
    private fun timeRaw(): String? = runCatching { org.json.JSONObject(timeFile().readText()).optString(Profiles.current()?.id ?: "").ifEmpty { null } }.getOrNull()
    private fun putTime(v: String) {
        val o = runCatching { org.json.JSONObject(timeFile().readText()) }.getOrNull() ?: org.json.JSONObject()
        timeFile().writeText(o.put(Profiles.current()?.id ?: "", v).toString())
    }

    fun blockedNow() = T.blocked(state(), Profiles.current()?.id, timeRaw())
    fun timeSummary() = T.summary(state(), Profiles.current()?.id, timeRaw())
    fun leftNow() = T.leftMs(state(), Profiles.current()?.id, timeRaw())
    fun addTime(ms: Long) { if (locked()) putTime(T.add(timeRaw(), ms)) }

    /** Runs [then] now, or (bedtime, or the day's time used up) after the parent PIN, which allows the rest of today. */
    fun checkTime(then: () -> Unit) {
        val why = blockedNow() ?: return then()
        askPin("$why. Parent PIN to allow today") { putTime(T.withOverride(timeRaw())); then() }
    }

    /** Runs [then] straight away, or after the parent PIN if this profile is locked. */
    fun guard(then: () -> Unit) { if (!locked()) then() else askPin("Parent PIN (or type forgot)", then) }

    fun askPin(label: String, then: () -> Unit) {
        KidsLock.waitSeconds()?.let { App.toast("Too many wrong PINs. Try again in $it seconds."); return }
        App.ask(label, hidden = true) { v ->
            when {
                v == null -> Unit
                v.trim().equals("forgot", ignoreCase = true) -> forgot()
                KidsLock.attempt(state(), v.trim()) -> then()
                else -> App.toast(KidsLock.waitSeconds()?.let { "Wrong PIN. Wait $it seconds before trying again." } ?: "Wrong PIN")
            }
        }
    }

    private fun askNewPin(done: (String) -> Unit) {
        App.ask("New PIN (4 to 8 digits)", hidden = true) { a ->
            if (a == null) return@ask
            if (!KidsLock.validPin(a.trim())) { App.toast("A PIN is 4 to 8 digits"); return@ask }
            App.ask("Same PIN again", hidden = true) { b ->
                if (b == null) return@ask
                if (b.trim() != a.trim()) App.toast("The two PINs didn't match") else done(a.trim())
            }
        }
    }

    /** Signing in with the email and password of any account on this computer clears the lock. */
    private fun forgot() {
        App.ask("Email of an account on this computer") { e ->
            val email = e?.trim().orEmpty()
            if (email.isEmpty()) return@ask
            if (Profiles.all().none { it.email.equals(email, ignoreCase = true) }) { App.toast("That account isn't signed in here"); return@ask }
            App.ask("Its Stremio password", hidden = true) { p ->
                if (p.isNullOrEmpty()) return@ask
                App.toast("Checking…")
                App.async({ Stremio.login(email, p) }) { r ->
                    r.onSuccess { key ->
                        App.async({ runCatching { Stremio.logout(key) } }) {}
                        save(KidsLock.NONE)
                        App.toast("Kids lock cleared. Set a new PIN in Manage profiles.")
                        Home.redraw()
                    }.onFailure { App.toast("Couldn't sign in: ${if (it is Stremio.ApiError) it.message else Net.describe(it)}") }
                }
            }
        }
    }

    /**
     * A profile's kids lock. Changes are made freely and only kept with Save changes, which asks the
     * parent PIN once (locking needs no PIN; leaving unsaved drops them).
     */
    fun screen(p: Profiles.Profile): Screen {
        lateinit var screen: Screen
        var draft: app.stremiolite.KidsLock.State? = null
        fun build() {
            val saved = state()
            val s = draft ?: saved
            val rows = mutableListOf<Row>()
            fun edit(n: app.stremiolite.KidsLock.State?) { draft = n; build() }
            if (!saved.hasPin) {
                rows += Row("Choose a PIN and lock ${p.name}", action = {
                    askNewPin { pin -> save(saved.withPin(pin).lock(p.id)); App.toast("${p.name} is locked"); edit(null) }
                })
            } else {
                draft?.let { d ->
                    rows += Row("Save changes", "parent PIN  ►", action = {
                        askPin("Parent PIN to save") { save(d); App.toast("Kids lock saved"); edit(null) }
                    })
                    rows += Row("Discard changes", action = { edit(null) })
                    rows += Row("────────────────────")
                }
                rows += if (s.isLocked(p.id)) Row(info(p.name, "locked"), "unlock", action = { edit(s.unlock(p.id)) })
                else Row(info(p.name, "not locked"), "lock", action = {
                    // Locking is always allowed straight away (it only makes things stricter).
                    if (draft == null) { save(saved.lock(p.id)); App.toast("${p.name} is locked"); edit(null) } else edit(s.lock(p.id))
                })
                rows += Row("In locked profiles, hide Search, Live TV and News", if (s.hideExtras) "on" else "off", action = { edit(s.withHide(!s.hideExtras)) })
                rows += Row("Age limit in locked profiles", app.stremiolite.KidsLock.ageLabel(s.maxAge) + "  ►", action = {
                    val K = app.stremiolite.KidsLock
                    if (Prefs.tmdbKey.isBlank()) App.toast("The age limit needs a TMDB key (Settings) for the ratings")
                    else edit(s.withAge(K.AGES[(K.AGES.indexOf(s.maxAge).coerceAtLeast(0) + 1) % K.AGES.size]))
                })
                rows += Row("Bedtime in locked profiles", T.bedtimeLabel(s) + "  ►", action = { edit(s.withBedtime(T.next(T.BEDTIMES, s.bedtime))) })
                if (s.bedtime >= 0) rows += Row("Allowed again from", T.clock(s.wake) + "  ►", action = { edit(s.withWake(T.next(T.WAKES, s.wake))) })
                rows += Row("Daily watch limit in locked profiles", T.dailyLabel(s.dailyMins) + "  ►", action = { edit(s.withDaily(T.next(T.DAILY, s.dailyMins))) })
                rows += Row("Change the parent PIN", action = {
                    askPin("Current PIN") { askNewPin { pin -> save((draft ?: state()).withPin(pin)); App.toast("PIN changed"); edit(null) } }
                })
                val others = Profiles.all().filter { s.isLocked(it.id) && it.id != p.id }.map { it.name }
                if (others.isNotEmpty()) rows += Row("Also locked: " + others.joinToString(", "))
            }
            screen.rows = rows
            App.refresh()
        }
        screen = Screen("Kids lock", emptyList())
        build()
        // Changed on another device since: show that (unless changes are being made here).
        syncIn(force = true) { if (draft == null && App.inStack(screen)) build() }
        // Left with changes not saved: they're dropped. (Asking for the PIN may cover this screen for a
        // moment; that's not leaving it.)
        screen.onHide = { App.later(0) { if (draft != null && !App.inStack(screen)) { draft = null; App.toast("Kids lock changes not saved") } } }
        screen.pos = 3
        return screen
    }
}
