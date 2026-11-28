package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings that follow the account, shared by every front-end. They're kept as one hidden record
 * on the Stremio account ("stremiolite:settings", like the waiting list), so the phone, the stick
 * and the computer share them; "Share my setup" sends the ones that aren't private.
 *
 * Each front-end keeps the values in its own settings store (a [Host]); this decides which go
 * where. Anything not listed in [KEYS] (the player, downloads, this device's sign-ins…) stays on
 * the device.
 */
object SharedSettings {
    const val ID = "stremiolite:settings"

    enum class Kind { INT, BOOL, LONG, TEXT, LIST }

    /**
     * [look]: part of the look, which can be kept to one device ("lookLocal").
     * [private]: follows your own devices, but never goes out with "Share my setup".
     */
    class Key(val name: String, val kind: Kind, val look: Boolean = false, val private: Boolean = false)

    private const val HIDDEN_ROWS = "hiddenRows"
    private const val SYNCED_AT = "syncedAt"
    private const val PENDING = "pendingSync"
    private const val LOOK_LOCAL = "lookLocal"

    val KEYS = listOf(
        Key("subLang", Kind.INT),
        Key("theme", Kind.INT, look = true),
        Key("clock", Kind.INT, look = true),
        Key("textSize", Kind.INT, look = true),
        Key("rice", Kind.TEXT, look = true),
        Key("customTheme", Kind.TEXT, look = true),
        Key("autoNext", Kind.BOOL),
        Key("hideWatched", Kind.BOOL),
        Key("autoFollow", Kind.BOOL),
        Key("liveTv", Kind.BOOL),
        Key("newsOn", Kind.BOOL),
        Key("comingOn", Kind.BOOL),
        Key("sleepOnHome", Kind.BOOL),
        Key("ratingsVotes", Kind.BOOL),
        Key("ratingsPercent", Kind.BOOL),
        Key("ratingsOrder", Kind.TEXT),
        // Your Home layout (Show on Home, and the Index's arrangement) is the same on every device.
        Key("layout", Kind.TEXT),
        Key("homeHidden", Kind.LIST),
        Key("playlists", Kind.TEXT, private = true),
        Key("liveFavs", Kind.TEXT, private = true),
        Key("liveGuide", Kind.TEXT, private = true),
        Key("liveFreeHide", Kind.TEXT, private = true),
        Key("newsFeeds", Kind.TEXT, private = true),
        Key("newsHide", Kind.TEXT, private = true),
        Key("watchRegion", Kind.TEXT, private = true),
        Key("tmdbKey", Kind.TEXT, private = true),
        Key("geminiKey", Kind.TEXT, private = true),
    )

    /** A front-end's settings for the current profile, and its copy of the account's records. */
    interface Host {
        fun has(key: String): Boolean
        /** The value as Int, Boolean, Long, String or Set<String> (by [kind]), or null if not set. */
        fun get(key: String, kind: Kind): Any?
        /** Sets every value in [changes] at once (same types as [get]); null removes it. */
        fun edit(changes: Map<String, Any?>)
        fun profileName(): String?
        fun takeName(name: String)
        /** The account's settings record, as JSON text. */
        fun record(): String?
        /** Stores the record and sends it to the account. */
        fun putRecord(item: JSONObject)
    }

    private fun Host.shares(k: Key) = !k.look || get(LOOK_LOCAL, Kind.BOOL) != true
    private fun Host.syncedAt() = get(SYNCED_AT, Kind.LONG) as? Long ?: 0L
    @Suppress("UNCHECKED_CAST")
    private fun Host.hiddenRows() = get(HIDDEN_ROWS, Kind.LIST) as? Set<String> ?: emptySet()

    private fun toJson(v: Any?): Any? = if (v is Set<*>) JSONArray(v.toList()) else v

    private fun list(a: JSONArray?): Set<String>? = a?.let { (0 until it.length()).map { i -> it.optString(i) }.toSet() }

    /** [k]'s value in [o] (null if missing or the wrong shape). */
    private fun fromJson(o: JSONObject, k: Key): Any? {
        if (!o.has(k.name)) return null
        return when (k.kind) {
            Kind.INT -> o.optInt(k.name)
            Kind.BOOL -> o.optBoolean(k.name)
            Kind.LONG -> o.optLong(k.name)
            Kind.TEXT -> o.optString(k.name)
            Kind.LIST -> list(o.optJSONArray(k.name))
        }
    }

    // ---- Share my setup -----------------------------------------------------------------------

    /** The settings "Share my setup" sends: none of the private ones. */
    fun exportShared(h: Host): JSONObject {
        val o = JSONObject()
        for (k in KEYS) if (!k.private && h.has(k.name) && h.shares(k)) h.get(k.name, k.kind)?.let { o.put(k.name, toJson(it)) }
        return o.put(HIDDEN_ROWS, JSONArray(h.hiddenRows().toList()))
    }

    /** Takes someone's shared settings, plus their hidden rows and [alsoHide], and sends them on to the account. */
    fun importShared(h: Host, o: JSONObject, alsoHide: Set<String>) {
        val changes = LinkedHashMap<String, Any?>()
        for (k in KEYS) if (!k.private) fromJson(o, k)?.let { changes[k.name] = it }
        changes[HIDDEN_ROWS] = h.hiddenRows() + (list(o.optJSONArray(HIDDEN_ROWS)) ?: emptySet()) + alsoHide
        h.edit(changes)
        syncOut(h)
    }

    // ---- following the account ----------------------------------------------------------------

    /** Sends this device's shared settings, keeping anything else the record holds (other devices' settings). */
    fun syncOut(h: Host) {
        val now = System.currentTimeMillis()
        val item = h.record()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: Sync.newItem(ID, "other", "{}")
        val o = runCatching { JSONObject(item.optString("name")) }.getOrNull() ?: JSONObject()
        // Only settings chosen on this device (or already taken from the account): never a default.
        for (k in KEYS) {
            if (!h.has(k.name) || !h.shares(k)) continue
            val v = h.get(k.name, k.kind) ?: continue
            if (k.name == "geminiKey" && (v as? String).isNullOrBlank()) continue
            o.put(k.name, toJson(v))
        }
        o.put(HIDDEN_ROWS, JSONArray(h.hiddenRows().toList()))
        // The profile's name, so every device signed in to this account shows the same one.
        h.profileName()?.takeIf { !ProfileName.isAuto(it) }?.let { o.put("profileName", it) }
        o.put("updated", now)
        h.edit(mapOf(SYNCED_AT to now))
        item.put("name", o.toString()).put("removed", true).put("temp", false).put("_mtime", Sync.iso(now))
        h.putRecord(item)
    }

    /**
     * Takes the account's settings when they're newer than this device's, and any it has never had.
     * Returns true if anything changed here.
     */
    fun syncIn(h: Host): Boolean {
        // Copied into this profile while another was in use: send it to the account first.
        if (h.get(PENDING, Kind.BOOL) == true) {
            h.edit(mapOf(PENDING to null))
            syncOut(h)
            return false
        }
        val raw = h.record()
        if (raw == null) {
            // Nothing on the account yet: this device's settings become the shared ones.
            if (h.syncedAt() == 0L) syncOut(h)
            return false
        }
        val o = runCatching { JSONObject(JSONObject(raw).optString("name")) }.getOrNull() ?: return false
        val updated = o.optLong("updated")
        val syncedAt = h.syncedAt()
        val newer = updated > syncedAt
        val changes = LinkedHashMap<String, Any?>()
        for (k in KEYS) {
            if (!h.shares(k)) continue
            val theirs = fromJson(o, k) ?: continue
            if (k.name == "geminiKey") {
                // A key is never wiped by a blank one; and a device without one always takes it.
                if ((theirs as String).isBlank()) continue
                if (!newer && !(h.get(k.name, k.kind) as? String).isNullOrBlank()) continue
            } else if (!newer && h.has(k.name)) continue
            if (theirs != h.get(k.name, k.kind)) changes[k.name] = theirs
        }
        var resend = false
        if (newer) list(o.optJSONArray(HIDDEN_ROWS))?.let { remote ->
            // The first time a device joins, keep the rows it had hidden as well as the account's.
            val hidden = if (syncedAt == 0L) remote + h.hiddenRows() else remote
            if (hidden != h.hiddenRows()) changes[HIDDEN_ROWS] = hidden
            resend = hidden != remote
        }
        // The account's name when it's newer, or always if this device named the profile itself ("Profile 1").
        val accName = o.optString("profileName")
        val cur = h.profileName() ?: ""
        val rename = !ProfileName.isAuto(accName) && accName != cur && (newer || ProfileName.isAuto(cur))
        if (rename) h.takeName(accName)
        val changed = changes.isNotEmpty() || rename
        if (newer) changes[SYNCED_AT] = updated
        if (changes.isNotEmpty()) h.edit(changes)
        when {
            resend -> syncOut(h)
            // Set up before names were synced: a device that's synced before sends its profile name once.
            !newer && !o.has("profileName") && syncedAt > 0 -> syncOut(h)
        }
        return changed
    }
}
