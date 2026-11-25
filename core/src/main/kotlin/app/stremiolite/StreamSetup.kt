package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Set up streams": creates an AIOStreams install from the stick-tuned settings that come with
 * the app, with your debrid services (TorBox, Real-Debrid, AllDebrid, Premiumize, Debrid-Link; any
 * mix, in your order), on ElfHosted's free AIOStreams server, and adds it to the account.
 */
object StreamSetup {
    const val SERVER = "https://aiostreams.elfhosted.com"
    const val TORBOX_KEY_PAGE = "https://torbox.app/settings"

    fun preset(): JSONObject =
        JSONObject(StreamSetup::class.java.getResourceAsStream("/stremonino-streams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })

    fun isAioStreams(a: Addon) = !a.isLocal && ("aiostreams" in a.id.lowercase() || "aiostreams" in a.name.lowercase().replace(" ", ""))

    /** A debrid service AIOStreams can use with just an API key. */
    class Service(val id: String, val name: String, val keyPage: String, val hint: String)

    val SERVICES = listOf(
        Service("torbox", "TorBox", TORBOX_KEY_PAGE, "On torbox.app: Settings, then copy your API key."),
        Service("realdebrid", "Real-Debrid", "https://real-debrid.com/apitoken", "Signed in at real-debrid.com, this page shows your API token."),
        Service("alldebrid", "AllDebrid", "https://alldebrid.com/apikeys", "On alldebrid.com: API keys, then create one and copy it."),
        Service("premiumize", "Premiumize", "https://www.premiumize.me/account", "On premiumize.me: Account, then copy your API key."),
        Service("debridlink", "Debrid-Link", "https://debrid-link.com/webapp/apikey", "On debrid-link.com: API key, then copy it."),
    )

    fun service(id: String) = SERVICES.firstOrNull { it.id == id }

    /** Your debrid keys in your order (first = tried first), stored as one line: [{"id","key"}…]. */
    fun parseKeys(raw: String?): List<Pair<String, String>> {
        val a = runCatching { JSONArray(raw ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { it.optString("id") to it.optString("key").trim() }
            .filter { (id, k) -> service(id) != null && k.isNotEmpty() }.distinctBy { it.first }
    }

    fun writeKeys(keys: List<Pair<String, String>>): String =
        JSONArray().apply { keys.filter { it.second.isNotBlank() }.distinctBy { it.first }.forEach { (id, k) -> put(JSONObject().put("id", id).put("key", k.trim())) } }.toString()

    /** [keys] with [id]'s key set (kept in its place, or added last) or, when blank, taken out. */
    fun withKey(keys: List<Pair<String, String>>, id: String, key: String): List<Pair<String, String>> = when {
        key.isBlank() -> keys.filter { it.first != id }
        keys.any { it.first == id } -> keys.map { if (it.first == id) id to key.trim() else it }
        else -> keys + (id to key.trim())
    }

    /** "TorBox, then Real-Debrid" for the screens. */
    fun names(keys: List<Pair<String, String>>) = keys.mapNotNull { service(it.first)?.name }

    /** Creates the install (blocking) with just a TorBox key. */
    fun create(torboxKey: String, password: String, server: String = SERVER): String = create(listOf("torbox" to torboxKey), password, server)

    /**
     * Creates the install (blocking) from the stick-tuned settings with your debrid services, in
     * your order, and returns its install link. Throws [AioRows.Failed] with a plain reason.
     */
    fun create(keys: List<Pair<String, String>>, password: String, server: String = SERVER): String {
        val use = keys.filter { it.second.isNotBlank() && service(it.first) != null }.distinctBy { it.first }
        if (use.isEmpty()) throw AioRows.Failed("add a debrid key first")
        val config = preset()
        val old = config.optJSONArray("services") ?: JSONArray()
        val services = JSONArray()
        // Your services first, in your order: AIOStreams ranks by this order.
        use.forEach { (id, k) -> services.put(JSONObject().put("id", id).put("enabled", true).put("credentials", JSONObject().put("apiKey", k))) }
        // Anything else the settings had (not one of these debrid services) stays after them.
        for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { service(it.optString("id")) == null }?.let { services.put(it) }
        config.put("services", services)
        val body = JSONObject().put("config", config).put("password", password)
        val (code, text) = Http.sendJson("$server/api/v1/user", "POST", body.toString(), timeoutMs = 120_000)
        val o = runCatching { JSONObject(text) }.getOrNull()
        val data = o?.optJSONObject("data")
        if (code >= 400 || o == null || !o.optBoolean("success") || data == null) {
            val err = o?.optJSONObject("error")
            val msg = listOf(err?.optString("message"), err?.optString("code"), o?.optString("error")).firstOrNull { !it.isNullOrBlank() && it != "null" }.orEmpty()
            throw AioRows.Failed(when {
                "password" in msg.lowercase() && "short" in msg.lowercase() -> "the password needs at least 6 characters"
                "access" in msg.lowercase() -> "this AIOStreams server needs its own access password"
                "apikey" in msg.lowercase().replace(" ", "") || SERVICES.any { it.id in msg.lowercase() || it.name.lowercase() in msg.lowercase() } ->
                    "a debrid service didn't accept its key ($msg)"
                msg.isNotBlank() -> msg
                else -> "the server answered $code"
            })
        }
        val uuid = data.optString("uuid"); val enc = data.optString("encryptedPassword")
        if (uuid.isBlank() || enc.isBlank()) throw AioRows.Failed("the server didn't send an install link")
        return "$server/stremio/$uuid/$enc/manifest.json"
    }

    /** Adds the new install and (if [replace]) takes any other AIOStreams off the account. Returns how many were removed. */
    fun install(authKey: String, installUrl: String, replace: Boolean): Int {
        Stremio.installAddon(authKey, installUrl)
        if (!replace) return 0
        val others = Stremio.parseAddons(Stremio.addonsJson(authKey)).filter { isAioStreams(it) && it.transportUrl != installUrl }
        others.forEach { Stremio.removeAddon(authKey, it.transportUrl) }
        return others.size
    }
}
