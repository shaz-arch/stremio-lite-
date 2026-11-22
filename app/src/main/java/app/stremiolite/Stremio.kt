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
            return Addon(
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
