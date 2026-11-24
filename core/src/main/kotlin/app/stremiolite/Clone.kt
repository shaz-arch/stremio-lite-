package app.stremiolite

import org.json.JSONArray
import org.json.JSONObject

/**
 * Copying your add-ons to other Stremio accounts (your other profiles, or family accounts):
 * like the stremio-account-addon-cloner page, with a choice of Add or Replace.
 *
 * Stream add-ons (AIOStreams with your TorBox key and the like) stay out unless asked for: they
 * carry your own debrid login. With Replace, the other account keeps its own stream add-ons.
 */
object Clone {
    enum class Mode { ADD, REPLACE }

    class Result(val sent: Int, val total: Int, val skippedStreams: Int)

    private fun manifest(o: JSONObject) = o.optJSONObject("manifest") ?: JSONObject()

    private fun id(o: JSONObject) = manifest(o).optString("id")

    /** Stremio's own built-in add-ons (Cinemeta, Local Files…): always there, never copied. */
    private fun isProtected(o: JSONObject) = o.optJSONObject("flags")?.optBoolean("protected") == true

    /** An add-on that finds streams (and so may hold a debrid login). */
    fun isStream(o: JSONObject): Boolean {
        val r = manifest(o).optJSONArray("resources") ?: return false
        return (0 until r.length()).any { i ->
            val v = r.opt(i)
            (v is String && v == "stream") || (v is JSONObject && v.optString("name") == "stream")
        }
    }

    private fun same(a: JSONObject, b: JSONObject) =
        a.optString("transportUrl") == b.optString("transportUrl") || (id(a).isNotEmpty() && id(a) == id(b))

    private fun list(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /**
     * Their new add-on list. ADD: theirs, then yours they don't have. REPLACE: yours in your order
     * (their built-in ones kept, and their stream add-ons unless you're sending yours).
     */
    fun plan(mine: JSONArray, theirs: JSONArray, mode: Mode, withStreams: Boolean): Pair<JSONArray, Int> {
        val myList = list(mine)
        val send = myList.filter { !isProtected(it) && (withStreams || !isStream(it)) }
        val skipped = myList.count { !isProtected(it) && !withStreams && isStream(it) }
        val theirList = list(theirs)
        val out = when (mode) {
            Mode.ADD -> theirList + send.filter { m -> theirList.none { same(it, m) } }
            Mode.REPLACE -> {
                val keep = theirList.filter { isProtected(it) || (!withStreams && isStream(it)) }
                // Their built-in add-ons first (as Stremio has them), then yours, then their own stream add-ons.
                keep.filter { isProtected(it) } + send.filter { m -> keep.none { same(it, m) } } + keep.filter { !isProtected(it) }
            }
        }
        return JSONArray(out) to skipped
    }

    /** Copies your add-ons (from [fromKey]) to the account signed in with [toKey]. Blocking. */
    fun send(fromKey: String, toKey: String, mode: Mode, withStreams: Boolean): Result {
        val mine = JSONObject(Stremio.addonsJson(fromKey)).optJSONArray("addons") ?: JSONArray()
        val theirs = JSONObject(Stremio.addonsJson(toKey)).optJSONArray("addons") ?: JSONArray()
        val (out, skipped) = plan(mine, theirs, mode, withStreams)
        val before = list(theirs).size
        try {
            Stremio.setAddons(toKey, out)
        } catch (e: Stremio.ApiError) {
            // Stremio caps the stored size: very large setups go in slim form (the app fetches the full manifests).
            if (e.message?.contains("size", ignoreCase = true) != true) throw e
            for (o in list(out)) o.optJSONObject("manifest")?.let { o.put("manifest", Stremio.slim(it)) }
            Stremio.setAddons(toKey, out)
        }
        val mineList = list(mine)
        val sent = if (mode == Mode.ADD) out.length() - before else list(out).count { o -> !isProtected(o) && mineList.any { same(it, o) } }
        return Result(sent, out.length(), skipped)
    }
}
