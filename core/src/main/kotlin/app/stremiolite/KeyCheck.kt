package app.stremiolite

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Checks each key with its own service (blocking): accepted or not, and for debrid accounts
 * whether the plan is active and when it ends. Nothing is changed anywhere.
 */
object KeyCheck {
    private const val WARN_DAYS = 14

    private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, JSONObject?> {
        val (code, text) = Http.sendJson(url, "GET", null, 20_000, headers)
        return code to runCatching { JSONObject(text) }.getOrNull()
    }

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer ${key.trim()}")
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    /** "premium until 12 Mar 2027" from a date ([ms]) or "", with a warning when it's close. */
    private fun until(ms: Long?, now: Long = System.currentTimeMillis()): Health.Outcome? {
        if (ms == null || ms <= 0) return null
        val day = SimpleDateFormat("d MMM yyyy", Locale.UK).format(java.util.Date(ms))
        val left = (ms - now) / 86_400_000L
        return when {
            left < 0 -> Health.Outcome(false, "plan ended $day", "Renew it, or remove the key so streams don't try it.")
            left < WARN_DAYS -> Health.Outcome(true, "premium until $day ($left day${if (left == 1L) "" else "s"} left)", "Renews soon: worth checking.")
            else -> Health.Outcome(true, "premium until $day")
        }
    }

    private fun iso(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let { t ->
        listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd").firstNotNullOfOrNull { f ->
            runCatching { SimpleDateFormat(f, Locale.UK).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(t.take(f.replace("'", "").length))?.time }.getOrNull()
        }
    }

    private fun refused(code: Int) = Health.Outcome(false, "key not accepted ($code)", "Copy the key again from the service's website.")

    fun tmdb(key: String): Health.Outcome = try {
        WhereToWatch.checkKey(key); Health.Outcome(true, "accepted")
    } catch (e: WhereToWatch.KeyRefused) { Health.Outcome(false, "key not accepted", "Copy it again from themoviedb.org › Settings › API.") }

    fun debrid(id: String, key: String): Health.Outcome = when (id) {
        "torbox" -> get("https://api.torbox.app/v1/api/user/me", bearer(key)).let { (c, o) ->
            val d = o?.optJSONObject("data")
            if (c !in 200..299 || o?.optBoolean("success") == false || d == null) refused(c)
            else if (d.optInt("plan") == 0) Health.Outcome(false, "free plan: no ready-to-play streams", "TorBox needs a paid plan for streaming.")
            else until(iso(d.optString("premium_expires_at"))) ?: Health.Outcome(true, "plan active")
        }
        "realdebrid" -> get("https://api.real-debrid.com/rest/1.0/user", bearer(key)).let { (c, o) ->
            if (c !in 200..299 || o == null) refused(c)
            else if (o.optString("type") != "premium") Health.Outcome(false, "not premium", "Real-Debrid needs premium for streaming.")
            else until(iso(o.optString("expiration"))) ?: Health.Outcome(true, "premium")
        }
        "alldebrid" -> get("https://api.alldebrid.com/v4/user?agent=stremonino", bearer(key)).let { (c, o) ->
            val u = o?.optJSONObject("data")?.optJSONObject("user")
            if (c !in 200..299 || o?.optString("status") != "success" || u == null) refused(c)
            else if (!u.optBoolean("isPremium")) Health.Outcome(false, "not premium", "AllDebrid needs premium for streaming.")
            else until(u.optLong("premiumUntil") * 1000) ?: Health.Outcome(true, "premium")
        }
        "premiumize" -> get("https://www.premiumize.me/api/account/info?apikey=${enc(key)}").let { (c, o) ->
            if (c !in 200..299 || o?.optString("status") != "success") refused(c)
            else until(o.optLong("premium_until") * 1000) ?: Health.Outcome(false, "no premium", "Premiumize needs premium for streaming.")
        }
        "debridlink" -> get("https://debrid-link.com/api/v2/account/infos", bearer(key)).let { (c, o) ->
            val v = o?.optJSONObject("value")
            if (c !in 200..299 || o?.optBoolean("success") != true || v == null) refused(c)
            else if (v.optInt("accountType") == 0) Health.Outcome(false, "free account", "Debrid-Link needs premium for streaming.")
            else v.optLong("premiumLeft").takeIf { it > 0 }?.let { until(System.currentTimeMillis() + it * 1000) } ?: Health.Outcome(true, "premium")
        }
        else -> Health.Outcome(false, "unknown service")
    }

    fun mdblist(key: String): Health.Outcome = get("https://api.mdblist.com/user?apikey=${enc(key)}").let { (c, o) ->
        if (c !in 200..299 || o == null || o.has("error")) refused(c)
        else Health.Outcome(true, "accepted" + (o.optString("username").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
    }

    fun gemini(key: String): Health.Outcome {
        val (model, tried) = AiSearch.findModel(key)
        return when {
            model != null -> Health.Outcome(true, "works ($model answering)")
            tried.isNotEmpty() && tried.all { it.second?.let { w -> AiSearch.isBusy(w) } == true } -> Health.Outcome(true, "accepted, but Google is busy right now")
            else -> Health.Outcome(false, tried.lastOrNull()?.second ?: "no answer", "Check the key at aistudio.google.com.")
        }
    }

    /** A check for each key that's set ([tmdb] blank = left out, e.g. when Health already checks it). */
    fun checks(tmdb: String, debrid: List<Pair<String, String>>, mdblist: String, gemini: String, describe: (Throwable) -> String): List<Health.Check> {
        fun c(name: String, f: () -> Health.Outcome) = Health.Check(name) { try { f() } catch (e: Throwable) { Health.Outcome(false, describe(e)) } }
        val list = mutableListOf<Health.Check>()
        if (tmdb.isNotBlank()) list += c("TMDB key") { tmdb(tmdb) }
        debrid.forEach { (id, k) -> list += c("${StreamSetup.service(id)?.name ?: id} key") { debrid(id, k) } }
        if (mdblist.isNotBlank()) list += c("MDBList key") { mdblist(mdblist) }
        if (gemini.isNotBlank()) list += c("Gemini key") { gemini(gemini) }
        return list
    }
}
