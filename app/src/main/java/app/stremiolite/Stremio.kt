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

    fun userEmail(authKey: String): String =
        call("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey)).optString("email")

    fun addons(authKey: String): List<Addon> {
        val result = call(
            "addonCollectionGet",
            JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true),
        )
        val arr = result.optJSONArray("addons") ?: JSONArray()
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
) {
    class Catalog(val type: String, val id: String, val name: String, val required: List<String>)

    /** The add-on's base address, without /manifest.json. */
    val base: String get() = transportUrl.removeSuffix("/manifest.json")

    /** Host only, so the personal config part of the link isn't shown on screen. */
    val host: String get() = runCatching { java.net.URL(transportUrl).host }.getOrDefault("?")

    /** Stremio's built-in local add-ons run inside the Stremio app and can't be reached from here. */
    val isLocal: Boolean get() = host == "127.0.0.1" || host == "localhost" || !transportUrl.startsWith("http")

    companion object {
        fun from(o: JSONObject): Addon? {
            val m = o.optJSONObject("manifest") ?: return null
            val resources = m.optJSONArray("resources").orEmpty().let { arr ->
                (0 until arr.length()).map { i ->
                    // A resource is either "stream" or {"name":"stream", ...}
                    arr.optJSONObject(i)?.optString("name") ?: arr.optString(i)
                }
            }
            val catalogs = m.optJSONArray("catalogs").orEmpty().let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    Catalog(
                        type = c.optString("type"),
                        id = c.optString("id"),
                        name = c.optString("name").ifEmpty { c.optString("id") },
                        required = requiredExtras(c),
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
            )
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

/** The sign-in key and email, kept in the app's private storage. */
object Account {
    private const val PREFS = "account"

    fun authKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("authKey", null)

    fun email(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("email", "") ?: ""

    fun save(context: Context, authKey: String, email: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("authKey", authKey).putString("email", email).apply()
    }

    fun saveEmail(context: Context, email: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("email", email).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
