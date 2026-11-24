package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Set up streams": creates an AIOStreams install from the stick-tuned settings that come with
 * the app, with your TorBox key, on ElfHosted's free AIOStreams server, and adds it to the account.
 */
object StreamSetup {
    const val SERVER = "https://aiostreams.elfhosted.com"
    const val TORBOX_KEY_PAGE = "https://torbox.app/settings"

    fun preset(): JSONObject =
        JSONObject(StreamSetup::class.java.getResourceAsStream("/stremonino-streams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) })

    fun isAioStreams(a: Addon) = !a.isLocal && ("aiostreams" in a.id.lowercase() || "aiostreams" in a.name.lowercase().replace(" ", ""))

    /** Creates the install (blocking) and returns its install link. Throws [AioRows.Failed] with a plain reason. */
    fun create(torboxKey: String, password: String, server: String = SERVER): String {
        val config = preset()
        val services = config.optJSONArray("services") ?: JSONArray().also { config.put("services", it) }
        var found = false
        for (i in 0 until services.length()) {
            val s = services.getJSONObject(i)
            if (s.optString("id") == "torbox") { s.put("enabled", true).put("credentials", JSONObject().put("apiKey", torboxKey.trim())); found = true }
        }
        if (!found) services.put(JSONObject().put("id", "torbox").put("enabled", true).put("credentials", JSONObject().put("apiKey", torboxKey.trim())))
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
                "torbox" in msg.lowercase() || "apikey" in msg.lowercase().replace(" ", "") -> "TorBox didn't accept the key ($msg)"
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
