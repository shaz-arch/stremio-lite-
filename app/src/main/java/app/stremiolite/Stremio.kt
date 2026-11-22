package app.stremiolite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Stremio account calls, matching what Stremio's own open-source core sends
 * (github.com/Stremio/stremio-core, src/types/api). Not an official public API.
 */
object Stremio {
    private const val API = "https://api.strem.io/api/"
    private const val LINK = "https://link.stremio.com/api/v2/"

    class ApiError(message: String) : Exception(message)

    class LinkCode(val code: String, val link: String, val qrcode: String)

    fun linkCreate(): LinkCode {
        val r = unwrap(JSONObject(Net.get(LINK + "create?type=Create", allowErrors = true)))
        return LinkCode(r.getString("code"), r.optString("link"), r.optString("qrcode"))
    }

    /** The auth key once the code has been approved on the phone, otherwise null. */
    fun linkRead(code: String): String? {
        val url = LINK + "read?type=Read&code=" + URLEncoder.encode(code, "UTF-8")
        val json = JSONObject(Net.get(url, allowErrors = true))
        val result = json.optJSONObject("result") ?: return null
        return result.optString("authKey").ifEmpty { null }
    }

    fun login(email: String, password: String): String = call(
        "login",
        JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false),
    ).getString("authKey")

    /** Creates a new Stremio account (the user has agreed to Stremio's terms first); returns its sign-in key. */
    fun register(email: String, password: String): String = call(
        "register",
        JSONObject().put("type", "Register").put("email", email).put("password", password)
            .put("gdpr_consent", JSONObject().put("tos", true).put("privacy", true).put("marketing", false).put("from", "web")),
    ).getString("authKey")

    /** Replaces an account's add-on list (used to copy add-ons from one profile to another). */
    fun setAddons(authKey: String, addons: JSONArray) {
        call("addonCollectionSet", JSONObject().put("type", "AddonCollectionSet").put("authKey", authKey).put("addons", addons))
    }

    fun userEmail(authKey: String): String =
        call("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey)).optString("email")

    /** The add-on collection as raw JSON, so it can also be cached on disk. */
    fun addonsJson(authKey: String): String = call(
        "addonCollectionGet",
        JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true),
    ).toString()

    /**
     * The collection with each add-on's manifest fetched live from the add-on itself.
     * Stremio caps the size of what it stores, so the account may hold a slimmed copy;
     * the live manifest has every catalogue. Keeps the stored copy if an add-on can't be reached.
     */
    fun withLiveManifests(json: String): String {
        val root = JSONObject(json)
        val arr = root.optJSONArray("addons") ?: return json
        val threads = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("transportUrl")
            if (!url.startsWith("http") || "127.0.0.1" in url || "localhost" in url) return@mapNotNull null
            if (o.optJSONObject("manifest")?.optString("id")?.endsWith(STICK_ROWS) == true) o.put("stickRows", true)
            Thread {
                runCatching { JSONObject(Net.get(url, timeoutMs = 8_000)) }.getOrNull()
                    ?.takeIf { it.has("id") && it.has("resources") }
                    ?.let { m -> synchronized(o) { o.put("manifest", m) } }
            }.also { it.start() }
        }
        threads.forEach { it.join(10_000) }
        return root.toString()
    }

    /** A copy small enough for Stremio's size cap: home and search rows only, without option lists. */
    fun slim(manifest: JSONObject): JSONObject {
        val m = JSONObject(manifest.toString())
        val cats = m.optJSONArray("catalogs") ?: return m
        val keep = JSONArray()
        for (i in 0 until cats.length()) {
            val c = cats.optJSONObject(i) ?: continue
            val extras = c.optJSONArray("extra")
            var needed = false
            if (extras != null) for (j in 0 until extras.length()) {
                val e = extras.optJSONObject(j) ?: continue
                if (e.optBoolean("isRequired") && e.optString("name") != "search") needed = true
                e.remove("options")
            }
            if (!needed && keep.length() < 60) keep.put(c)
        }
        m.put("catalogs", keep)
        return m
    }

    /**
     * Installs an add-on from its link. One with the same id (e.g. an older AIOMetadata)
     * is replaced in place; otherwise it is added to the end. Returns the add-on's name.
     */
    /** Marks the stored copy of a "stick rows" add-on (its id gets this ending). */
    const val STICK_ROWS = ".stickrows"

    /**
     * A "stick rows" copy for the account: no rows and only the catalog resource, so Stremio's
     * own apps ignore it. Stremio Lite reads the live list and uses its rows instead.
     */
    private fun stickRowsCopy(manifest: JSONObject): JSONObject {
        val m = JSONObject()
        for (k in listOf("version", "description", "logo", "types", "idPrefixes")) if (manifest.has(k)) m.put(k, manifest.get(k))
        m.put("id", manifest.optString("id") + STICK_ROWS)
        m.put("name", manifest.optString("name", "Add-on") + " (stick rows)")
        m.put("resources", JSONArray().put("catalog"))
        m.put("catalogs", JSONArray())
        if (!m.has("types")) m.put("types", JSONArray().put("movie").put("series"))
        return m
    }

    /** Removes the add-on with this link from the account. */
    fun removeAddon(authKey: String, transportUrl: String) {
        val old = JSONObject(addonsJson(authKey)).optJSONArray("addons") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { it.optString("transportUrl") != transportUrl }?.let { out.put(it) }
        setAddons(authKey, out)
    }

    fun installAddon(authKey: String, link: String, stickRows: Boolean = false): String {
        val url = link.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
            .let { if (it.endsWith("/manifest.json")) it else it.trimEnd('/') + "/manifest.json" }
        val manifest = JSONObject(Net.get(url, timeoutMs = 15_000))
        if (!manifest.has("id")) throw ApiError("That link isn't an add-on")
        val stored = if (stickRows) stickRowsCopy(manifest) else slim(manifest)
        val entry = JSONObject().put("transportUrl", url).put("transportName", "")
            .put("manifest", stored)
            .put("flags", JSONObject().put("official", false).put("protected", false))
        val old = JSONObject(addonsJson(authKey)).optJSONArray("addons") ?: JSONArray()
        val out = JSONArray()
        var placed = false
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            val same = o.optString("transportUrl") == url ||
                o.optJSONObject("manifest")?.optString("id") == stored.optString("id")
            if (same) { if (!placed) { out.put(entry); placed = true } } else out.put(o)
        }
        if (!placed) out.put(entry)
        setAddons(authKey, out)
        return manifest.optString("name", "Add-on")
    }

    fun parseAddons(json: String): List<Addon> {
        val arr = JSONObject(json).optJSONArray("addons") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(Addon::from) }
    }

    private fun call(method: String, body: JSONObject): JSONObject =
        unwrap(JSONObject(Net.postJson(API + method, body.toString())))

    private fun unwrap(json: JSONObject): JSONObject {
        json.optJSONObject("error")?.let { throw ApiError(it.optString("message", "Stremio returned an error")) }
        return json.optJSONObject("result") ?: throw ApiError("Stremio sent an empty reply")
    }
}

/** One installed add-on, as listed on the Stremio account. */
class Addon(
    /** The add-on's own id (for a stick-rows copy, the id of the add-on it was made from). */
    val id: String,
    /** Installed with "stick rows": its rows replace those of the add-on with the same id. */
    val stickRows: Boolean,
    val name: String,
    val version: String,
    val transportUrl: String,
    val types: List<String>,
    val resources: List<String>,
    val catalogs: List<Catalog>,
    private val resourceRules: List<Resource> = emptyList(),
    private val idPrefixes: List<String>? = null,
) {
    class Catalog(val type: String, val id: String, val name: String, val required: List<String>, val extras: List<String> = emptyList()) {
        /** A catalogue that can be searched with nothing else required. */
        val searchable: Boolean get() = "search" in extras && required.all { it == "search" }
    }

    /** A resource with its own limits; null lists mean "use the manifest's". */
    class Resource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

    /** Stremio's rule for whether this add-on can answer e.g. a "meta" or "stream" request for a title. */
    fun supports(resource: String, type: String, id: String): Boolean {
        if (isLocal) return false
        val r = resourceRules.firstOrNull { it.name == resource } ?: return false
        val okTypes = r.types ?: types
        if (type !in okTypes) return false
        val prefixes = r.idPrefixes ?: idPrefixes ?: return true
        return prefixes.any { id.startsWith(it) }
    }

    /** The add-on's base address, without /manifest.json. */
    val base: String get() = transportUrl.removeSuffix("/manifest.json")

    /** Host only, so the personal config part of the link isn't shown on screen. */
    val host: String get() = runCatching { java.net.URL(transportUrl).host }.getOrDefault("?")

    /** Stremio's built-in local add-ons run inside the Stremio app and can't be reached from here. */
    val isLocal: Boolean get() = host == "127.0.0.1" || host == "localhost" || !transportUrl.startsWith("http")

    companion object {
        fun from(o: JSONObject): Addon? {
            val m = o.optJSONObject("manifest") ?: return null
            // A resource is either "stream" or {"name":"stream","types":[...],"idPrefixes":[...]}
            val rules = m.optJSONArray("resources").orEmpty().let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.optJSONObject(i)
                    if (o == null) Resource(arr.optString(i), null, null)
                    else Resource(o.optString("name"), o.optJSONArray("types")?.strings(), o.optJSONArray("idPrefixes")?.strings())
                }
            }
            val resources = rules.map { it.name }
            val catalogs = m.optJSONArray("catalogs").orEmpty().let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    Catalog(
                        type = c.optString("type"),
                        id = c.optString("id"),
                        name = c.optString("name").ifEmpty { c.optString("id") },
                        required = requiredExtras(c),
                        extras = extraNames(c),
                    )
                }
            }
            val rawId = m.optString("id")
            return Addon(
                id = rawId.removeSuffix(Stremio.STICK_ROWS),
                stickRows = o.optBoolean("stickRows") || rawId.endsWith(Stremio.STICK_ROWS),
                name = m.optString("name").ifEmpty { m.optString("id", "Add-on") },
                version = m.optString("version"),
                transportUrl = o.optString("transportUrl"),
                types = m.optJSONArray("types").orEmpty().strings(),
                resources = resources,
                catalogs = catalogs,
                resourceRules = rules,
                idPrefixes = m.optJSONArray("idPrefixes")?.strings(),
            )
        }

        private fun extraNames(c: JSONObject): List<String> {
            val out = mutableListOf<String>()
            c.optJSONArray("extra")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("name")?.let { out += it }
            }
            c.optJSONArray("extraSupported")?.let { out += it.strings() }
            return out.distinct()
        }

        /** Extras a catalogue can't be opened without (e.g. search-only catalogues). */
        private fun requiredExtras(c: JSONObject): List<String> {
            val out = mutableListOf<String>()
            c.optJSONArray("extra")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    if (e.optBoolean("isRequired")) out += e.optString("name")
                }
            }
            c.optJSONArray("extraRequired")?.let { out += it.strings() }
            return out.distinct()
        }

        private fun JSONArray?.orEmpty() = this ?: JSONArray()
        private fun JSONArray.strings() = (0 until length()).map { optString(it) }
    }
}

/** The current profile's sign-in key and email (see [Profiles]). */
object Account {
    fun authKey(context: Context): String? = Profiles.current(context)?.authKey

    fun email(context: Context): String = Profiles.current(context)?.email ?: ""

    fun saveEmail(context: Context, email: String) = Profiles.updateEmail(context, email)

    /** Signs the current profile out of this device (the Stremio account itself is untouched). */
    fun clear(context: Context) {
        val p = Profiles.current(context) ?: return
        addonCache(context).delete()
        Profiles.remove(context, p.id)
    }

    /** Last known add-on list for the current profile, so Home appears instantly at start-up. */
    fun addonCache(context: Context) = java.io.File(context.filesDir, Profiles.fileName(context, "addons"))
}
