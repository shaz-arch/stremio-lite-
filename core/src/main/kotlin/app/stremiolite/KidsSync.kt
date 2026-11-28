package app.stremiolite

import org.json.JSONObject

/**
 * Kids lock across devices. Every profile is a Stremio account, so each profile's account carries a
 * hidden record saying whether that profile is locked, with the parent PIN (as its salted hash, never
 * the PIN itself) and the rules (age limit, bedtime, daily limit, hidden extras). A device that signs
 * in to the same accounts takes the newest of them, so locking a profile on the phone locks it on the
 * stick and the computer too, with the same PIN. Blocking calls: run them in the background.
 */
object KidsSync {
    const val ID = "stremiolite:kidslock"

    /** One account's copy: is that profile locked, and the device-wide PIN and rules as of [updated]. */
    class Remote(val locked: Boolean, val state: KidsLock.State, val updated: Long)

    private fun record(s: KidsLock.State, locked: Boolean, updated: Long): JSONObject {
        val name = JSONObject(s.json()).put("locked", locked).put("updated", updated)
        return Sync.newItem(ID, "other", name.toString())
            .put("removed", true).put("temp", false).put("_mtime", Sync.iso(System.currentTimeMillis()))
    }

    private fun parse(item: JSONObject?): Remote? {
        val o = item?.optString("name")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        // The PIN and rules; which profiles are locked is set per device from each account's flag.
        val s = KidsLock.read(JSONObject(o.toString()).put("locked", org.json.JSONArray()).toString())
        return Remote(o.optBoolean("locked"), s, o.optLong("updated"))
    }

    /** Sends the state to every profile's account ([keys]: profile id to its sign-in). Failures are left for the next change. */
    fun push(keys: Map<String, String>, s: KidsLock.State, updated: Long) {
        keys.forEach { (pid, key) -> runCatching { Sync.push(key, listOf(record(s, s.isLocked(pid), updated))) } }
    }

    /** Each profile's account copy (missing ones left out). */
    fun pull(keys: Map<String, String>): Map<String, Remote> =
        keys.mapNotNull { (pid, key) ->
            runCatching { Sync.pullIds(key, listOf(ID)) }.getOrNull()?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("_id") == ID }
            }?.let { parse(it) }?.let { pid to it }
        }.toMap()

    /**
     * The state to use if an account has newer than this device ([localUpdated]), with its time; null
     * when this device is up to date. The PIN and rules come from the newest copy; each profile with a
     * copy is locked or not as its own account says; profiles with none keep how they are here.
     */
    fun merge(local: KidsLock.State, localUpdated: Long, remotes: Map<String, Remote>): Pair<KidsLock.State, Long>? {
        val newest = remotes.values.maxByOrNull { it.updated } ?: return null
        if (newest.updated <= localUpdated) return null
        var locked = local.locked
        remotes.forEach { (pid, r) -> locked = if (r.locked) locked + pid else locked - pid }
        val n = newest.state
        val merged = KidsLock.read(JSONObject(n.json()).put("locked", org.json.JSONArray(locked.toList())).toString())
        return merged to newest.updated
    }
}
