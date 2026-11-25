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
    class State(val salt: String, val hash: String, val locked: Set<String>, val hideExtras: Boolean) {
        val hasPin get() = hash.isNotEmpty()
        fun isLocked(profileId: String?) = hasPin && profileId != null && profileId in locked
        fun matches(pin: String) = hasPin && hashOf(pin, salt) == hash
        fun lock(id: String) = State(salt, hash, locked + id, hideExtras)
        fun unlock(id: String) = State(salt, hash, locked - id, hideExtras)
        fun withHide(on: Boolean) = State(salt, hash, locked, on)
        fun withPin(pin: String): State { val s = newSalt(); return State(s, hashOf(pin, s), locked, hideExtras) }
        fun json(): String = JSONObject().put("salt", salt).put("hash", hash)
            .put("locked", JSONArray(locked.toList())).put("hide", hideExtras).toString()
    }

    val NONE = State("", "", emptySet(), true)

    fun read(raw: String?): State {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return NONE
        val ids = o.optJSONArray("locked")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()
        return State(o.optString("salt"), o.optString("hash"), ids, o.optBoolean("hide", true))
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
