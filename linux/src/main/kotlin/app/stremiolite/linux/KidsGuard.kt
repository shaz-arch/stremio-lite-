package app.stremiolite.linux

import app.stremiolite.KidsLock
import app.stremiolite.Stremio
import java.io.File

/** Linux side of [KidsLock]: PIN prompts, the per-profile lock screen, and the guard on Home's way out. */
object KidsGuard {
    private fun file() = File(Store.root, "kidslock.json")

    fun state(): KidsLock.State = KidsLock.read(runCatching { file().readText() }.getOrNull())

    private fun save(s: KidsLock.State) {
        file().writeText(s.json())
        runCatching { file().setReadable(false, false); file().setReadable(true, true) }
    }

    fun locked() = state().isLocked(Profiles.current()?.id)
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

    fun screen(p: Profiles.Profile): Screen {
        lateinit var screen: Screen
        fun build() {
            val s = state()
            val rows = mutableListOf<Row>()
            if (!s.hasPin) {
                rows += Row("Choose a PIN and lock ${p.name}", action = {
                    askNewPin { pin -> save(s.withPin(pin).lock(p.id)); App.toast("${p.name} is locked"); build() }
                })
            } else {
                rows += if (s.isLocked(p.id)) Row(info(p.name, "locked"), "unlock", action = {
                    askPin("Parent PIN to unlock ${p.name}") { save(state().unlock(p.id)); App.toast("${p.name} is unlocked"); build() }
                }) else Row(info(p.name, "not locked"), "lock", action = { save(s.lock(p.id)); App.toast("${p.name} is locked"); build() })
                rows += Row("In locked profiles, hide Search, Live TV and News", if (s.hideExtras) "on" else "off", action = {
                    askPin("Parent PIN") { save(state().withHide(!state().hideExtras)); build() }
                })
                rows += Row("Age limit in locked profiles", app.stremiolite.KidsLock.ageLabel(s.maxAge) + "  ►", action = {
                    if (Prefs.tmdbKey.isBlank()) App.toast("The age limit needs a TMDB key (Settings) for the ratings")
                    else askPin("Parent PIN") {
                        val K = app.stremiolite.KidsLock
                        val next = K.AGES[(K.AGES.indexOf(state().maxAge).coerceAtLeast(0) + 1) % K.AGES.size]
                        save(state().withAge(next)); App.toast("Age limit: " + K.ageLabel(next)); build()
                    }
                })
                rows += Row("Bedtime in locked profiles", T.bedtimeLabel(s) + "  ►", action = {
                    askPin("Parent PIN") { save(state().withBedtime(T.next(T.BEDTIMES, state().bedtime))); App.toast("Bedtime: " + T.bedtimeLabel(state())); build() }
                })
                if (s.bedtime >= 0) rows += Row("Allowed again from", T.clock(s.wake) + "  ►", action = {
                    askPin("Parent PIN") { save(state().withWake(T.next(T.WAKES, state().wake))); build() }
                })
                rows += Row("Daily watch limit in locked profiles", T.dailyLabel(s.dailyMins) + "  ►", action = {
                    askPin("Parent PIN") { val n = T.next(T.DAILY, state().dailyMins); save(state().withDaily(n)); App.toast("Daily limit: " + T.dailyLabel(n)); build() }
                })
                rows += Row("Change the parent PIN", action = {
                    askPin("Current PIN") { askNewPin { pin -> save(state().withPin(pin)); App.toast("PIN changed") } }
                })
                val others = Profiles.all().filter { s.isLocked(it.id) && it.id != p.id }.map { it.name }
                if (others.isNotEmpty()) rows += Row("Also locked: " + others.joinToString(", "))
            }
            screen.rows = rows
            App.refresh()
        }
        screen = Screen("Kids lock", emptyList())
        build()
        screen.pos = 3
        return screen
    }
}
