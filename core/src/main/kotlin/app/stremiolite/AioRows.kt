package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Choosing AIOMetadata's rows from the app: loads the install's settings with its password,
 * changes which rows are on (and on the home screen) and their order, and saves them back.
 * The install link doesn't change, so every device and Stremio itself pick it up on reload.
 */
object AioRows {
    /** An AIOMetadata install, found from its install link (…/stremio/<id>/manifest.json). */
    class Install(val server: String, val id: String)

    fun isAio(a: Addon) = !a.isLocal && ("aiometadata" in a.id.lowercase() || "aiometadata" in a.name.lowercase().replace(" ", "")) &&
        a.base.contains("/stremio/")

    fun install(a: Addon): Install? {
        val m = Regex("""^(https?://[^/]+(?:/.*?)?)/stremio/([^/]+)$""").find(a.base) ?: return null
        return Install(m.groupValues[1], m.groupValues[2])
    }

    class Failed(message: String) : Exception(message)

    /** The settings as loaded, plus the version they were loaded at (so a newer save elsewhere isn't overwritten). */
    class Loaded(val install: Install, val config: JSONObject) {
        val version: Long get() = config.optLong("configVersion", 0)
        val catalogs: JSONArray get() = config.optJSONArray("catalogs") ?: JSONArray().also { config.put("catalogs", it) }
    }

    private fun error(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
        return when {
            code == 401 && "addon password" in msg.lowercase() -> "this AIOMetadata server needs its own access password, so use its settings page"
            code == 401 -> "wrong password"
            code == 409 -> "the settings were changed somewhere else since you opened this. Open it again to get the latest"
            code == 403 || "sign" in msg.lowercase() -> "this AIOMetadata server wants you signed in on its page, so use its settings page"
            code == 429 -> "too many tries. Wait a minute and try again"
            msg.isNotBlank() -> msg
            else -> "the server answered $code"
        }
    }

    /** Loads the install's settings (blocking). */
    fun load(i: Install, password: String): Loaded {
        val (code, text) = Http.sendJson(i.server + "/api/config/load/" + enc(i.id), "POST", JSONObject().put("password", password).toString())
        if (code >= 400) throw Failed(error(code, text))
        val cfg = runCatching { JSONObject(text).optJSONObject("config") }.getOrNull() ?: throw Failed("the server's answer didn't include the settings")
        return Loaded(i, cfg)
    }

    /** Saves changed settings back (blocking). Returns the new version. */
    fun save(l: Loaded, password: String) {
        val body = JSONObject().put("config", l.config).put("password", password).put("baseVersion", l.version)
        val (code, text) = Http.sendJson(l.install.server + "/api/config/update/" + enc(l.install.id), "PUT", body.toString())
        if (code >= 400) throw Failed(error(code, text))
        runCatching { JSONObject(text) }.getOrNull()?.let { r ->
            r.optJSONObject("config")?.optLong("configVersion")?.takeIf { it > 0 }?.let { l.config.put("configVersion", it) }
                ?: r.optLong("configVersion").takeIf { it > 0 }?.let { l.config.put("configVersion", it) }
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- The rows ----

    enum class State(val label: String) { OFF("off"), ON("on"), HOME("on home") }

    /** One row as the picker shows it (rows folded into a merged row are left out). */
    class Item(val obj: JSONObject) {
        val id: String get() = obj.optString("id")
        val name: String get() = obj.optString("name").ifBlank { id }
        val type: String get() = obj.optString("type")
        var state: State
            get() = when {
                !obj.optBoolean("enabled") -> State.OFF
                obj.optBoolean("showInHome") -> State.HOME
                else -> State.ON
            }
            set(v) {
                obj.put("enabled", v != State.OFF)
                obj.put("showInHome", v == State.HOME)
            }

        fun next(): State = when (state) { State.OFF -> State.ON; State.ON -> State.HOME; State.HOME -> State.OFF }
        val typeLabel: String get() = when (type) { "movie" -> "Movies"; "series" -> "Shows"; "all" -> "Both"; "collections" -> "Collections"; else -> type }
    }

    fun items(l: Loaded): List<Item> {
        val arr = l.catalogs
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("mergedInto").isBlank() }
            .map { Item(it) }
    }

    /** Moves a row past the next row that's switched on, up ([by] = -1) or down (+1). Returns true if it moved. */
    fun move(l: Loaded, item: Item, by: Int): Boolean {
        val arr = l.catalogs
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
        val from = list.indexOfFirst { it === item.obj }.takeIf { it >= 0 } ?: return false
        fun visibleOn(o: JSONObject) = o.optBoolean("enabled") && o.optString("mergedInto").isBlank()
        var to = from + by
        while (to in list.indices && !visibleOn(list[to])) to += by
        if (to !in list.indices) return false
        list.removeAt(from)
        list.add(to, item.obj)
        replace(l, list)
        return true
    }

    /** Puts a row first (or last) among the rows. */
    fun moveToEnd(l: Loaded, item: Item, top: Boolean) {
        val arr = l.catalogs
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it !== item.obj }.toMutableList()
        if (top) list.add(0, item.obj) else list.add(item.obj)
        replace(l, list)
    }

    private fun replace(l: Loaded, list: List<JSONObject>) {
        val out = JSONArray()
        list.forEach { out.put(it) }
        l.config.put("catalogs", out)
    }

    /** Which folder a row belongs under in the picker: its "Folder: Name" prefix or a guess from its name. */
    fun folder(item: Item): String {
        val (prefix, _) = Folders.split(item.name)
        return prefix?.substringBefore(" > ")?.trim() ?: Folders.autoFolder(item.name)
    }

    /** A fingerprint of what's switched on and in what order, to tell whether anything changed. */
    fun signature(l: Loaded): String = items(l).joinToString("|") { it.id + ":" + it.state.ordinal }
}
