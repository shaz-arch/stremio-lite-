package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Kids lock: one parent PIN per device, and any profile can be locked with it. In a locked profile,
 * leaving it (Switch profile, Sign out) and Settings need the PIN, and Search, Live TV and News can
 * be hidden. Kept on this device only, never synced or shared: the PIN is stored as a salted hash.
 * A forgotten PIN is cleared by signing in with the locked profile's own Stremio password.
 */
object KidsLock {
    /** [maxAge]: the oldest age rating a locked profile may see and play (0 = no limit). */
    class State(val salt: String, val hash: String, val locked: Set<String>, val hideExtras: Boolean, val maxAge: Int = 0) {
        val hasPin get() = hash.isNotEmpty()
        fun isLocked(profileId: String?) = hasPin && profileId != null && profileId in locked
        fun matches(pin: String) = hasPin && hashOf(pin, salt) == hash
        fun lock(id: String) = State(salt, hash, locked + id, hideExtras, maxAge)
        fun unlock(id: String) = State(salt, hash, locked - id, hideExtras, maxAge)
        fun withHide(on: Boolean) = State(salt, hash, locked, on, maxAge)
        fun withAge(age: Int) = State(salt, hash, locked, hideExtras, age)
        fun withPin(pin: String): State { val s = newSalt(); return State(s, hashOf(pin, s), locked, hideExtras, maxAge) }
        fun json(): String = JSONObject().put("salt", salt).put("hash", hash)
            .put("locked", JSONArray(locked.toList())).put("hide", hideExtras).put("maxAge", maxAge).toString()

        /** Whether [profileId] may see and play a title rated [rating] ("" or null: not known yet). */
        fun allows(profileId: String?, rating: String?): Boolean {
            if (!isLocked(profileId) || maxAge <= 0) return true
            val age = ageOf(rating ?: return false) ?: return false
            return age <= maxAge
        }
    }

    val NONE = State("", "", emptySet(), true)

    fun read(raw: String?): State {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return NONE
        val ids = o.optJSONArray("locked")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()
        return State(o.optString("salt"), o.optString("hash"), ids, o.optBoolean("hide", true), o.optInt("maxAge", 0))
    }

    /** The age-limit choices, in order (0 = off). */
    val AGES = listOf(0, 7, 12, 15)

    fun ageLabel(age: Int) = when (age) {
        0 -> "no limit"
        7 -> "up to 7 (U, PG, TV-Y7)"
        12 -> "up to 12 (12A, PG-13)"
        15 -> "up to 15"
        else -> "up to $age"
    }

    /** The minimum age for a rating ("12A", "US PG-13", "TV-MA", "16"), or null if it can't be told. */
    fun ageOf(rating: String): Int? {
        val r = rating.trim().uppercase().removePrefix("US ").trim()
        if (r.isEmpty()) return null
        return when (r) {
            "U", "G", "TV-Y", "TV-G", "ALL", "A" -> 0
            "PG", "TV-PG" -> 8
            "TV-Y7", "TV-Y7-FV" -> 7
            "12A" -> 12
            "PG-13" -> 13
            "TV-14" -> 14
            "R", "TV-MA" -> 17
            "NC-17", "R18", "X" -> 18
            else -> Regex("\\d{1,2}").find(r)?.value?.toIntOrNull()
        }
    }

    /** 4 to 8 digits. */
    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun hashOf(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Tries after which the app waits before taking another PIN. */
    const val TRIES = 5
    const val WAIT_MS = 60_000L

    private var wrong = 0
    private var waitUntil = 0L

    /** Null if a PIN can be tried now, or how many seconds to wait. */
    fun waitSeconds(now: Long = System.currentTimeMillis()): Long? = if (now < waitUntil) (waitUntil - now + 999) / 1000 else null

    /** Checks [pin], counting wrong tries; true if it's right. */
    fun attempt(state: State, pin: String, now: Long = System.currentTimeMillis()): Boolean {
        if (waitSeconds(now) != null) return false
        if (state.matches(pin)) { wrong = 0; return true }
        if (++wrong >= TRIES) { wrong = 0; waitUntil = now + WAIT_MS }
        return false
    }
}
