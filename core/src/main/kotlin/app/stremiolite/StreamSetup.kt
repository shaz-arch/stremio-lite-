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
    /** Puts your services first in [config], in your order (AIOStreams ranks by it); other services there stay after them. */
    private fun putServices(config: JSONObject, use: List<Pair<String, String>>) {
        val old = config.optJSONArray("services") ?: JSONArray()
        val services = JSONArray()
        use.forEach { (id, k) -> services.put(JSONObject().put("id", id).put("enabled", true).put("credentials", JSONObject().put("apiKey", k))) }
        for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { service(it.optString("id")) == null }?.let { services.put(it) }
        config.put("services", services)
    }

    /** The install's id and server from its link (…/stremio/<uuid>/<encrypted password>/manifest.json), or null. */
    fun installOf(link: String): Pair<String, String>? {
        val m = Regex("""^(https?://[^/]+(?:/[^/]+)*?)/stremio/([0-9a-fA-F-]{36})/""").find(link) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /**
     * Swaps the debrid services in your current install for [keys], keeping everything else it has
     * (blocking). Same install, same link: nothing changes on your Stremio account.
     */
    fun update(installLink: String, password: String, keys: List<Pair<String, String>>) {
        val use = keys.filter { it.second.isNotBlank() && service(it.first) != null }.distinctBy { it.first }
        if (use.isEmpty()) throw AioRows.Failed("add a debrid key first")
        val (server, uuid) = installOf(installLink) ?: throw AioRows.Failed("this AIOStreams can't be updated from here: use 'Create my streams'")
        val auth = mapOf("Authorization" to "Basic " + Http.base64("$uuid:$password".toByteArray(Charsets.UTF_8)))
        fun fail(code: Int, text: String, what: String): Nothing {
            val err = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            val msg = listOf(err?.optString("message"), err?.optString("code")).firstOrNull { !it.isNullOrBlank() && it != "null" }.orEmpty()
            throw AioRows.Failed(when {
                code == 401 || code == 403 || "password" in msg.lowercase() || "auth" in msg.lowercase() -> "the AIOStreams password didn't match"
                msg.isNotBlank() -> msg
                else -> "couldn't $what (the server answered $code)"
            })
        }
        // Read the install's settings with its password (sent in the header, never in the link).
        val (gc, gt) = Http.sendJson("$server/api/v1/user", "GET", null, 30_000, auth)
        val got = runCatching { JSONObject(gt) }.getOrNull()
        val config = got?.optJSONObject("data")?.optJSONObject("userData")
        if (gc >= 400 || got?.optBoolean("success") != true || config == null) fail(gc, gt, "read your streams' settings")
        putServices(config, use)
        val body = JSONObject().put("config", config)
        val (pc, pt) = Http.sendJson("$server/api/v1/user", "PUT", body.toString(), 120_000, auth)
        if (pc >= 400 || runCatching { JSONObject(pt).optBoolean("success") }.getOrDefault(false) != true) fail(pc, pt, "save the new keys")
    }

    fun create(keys: List<Pair<String, String>>, password: String, server: String = SERVER): String {
        val use = keys.filter { it.second.isNotBlank() && service(it.first) != null }.distinctBy { it.first }
        if (use.isEmpty()) throw AioRows.Failed("add a debrid key first")
        val config = preset()
        putServices(config, use)
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
