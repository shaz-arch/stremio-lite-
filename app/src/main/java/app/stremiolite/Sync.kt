package app.stremiolite

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Stremio account library sync ("libraryItem" collection), in the same format Stremio's own core
 * writes (github.com/Stremio/stremio-core: types/library, models/player, stremio-watched-bitfield).
 * Not an official public API.
 */
object Sync {
    private const val API = "https://api.strem.io/api/"

    // ---- requests ----------------------------------------------------------------------------

    /** Every library item on the account. */
    fun pull(authKey: String): JSONArray {
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem")
            .put("ids", JSONArray()).put("all", true)
        val json = JSONObject(Net.postJson(API + "datastoreGet", body.toString(), timeoutMs = 20_000))
        json.optJSONObject("error")?.let { throw Stremio.ApiError(it.optString("message", "library sync failed")) }
        return json.optJSONArray("result") ?: JSONArray()
    }

    /** Just the listed records (for the background check, which needs only the waiting list). */
    fun pullIds(authKey: String, ids: List<String>): JSONArray {
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem")
            .put("ids", JSONArray(ids)).put("all", false)
        val json = JSONObject(Net.postJson(API + "datastoreGet", body.toString(), timeoutMs = 20_000))
        json.optJSONObject("error")?.let { throw Stremio.ApiError(it.optString("message", "library sync failed")) }
        return json.optJSONArray("result") ?: JSONArray()
    }

    fun push(authKey: String, items: List<JSONObject>) {
        if (items.isEmpty()) return
        val changes = JSONArray().apply { items.forEach { put(it) } }
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", changes)
        val json = JSONObject(Net.postJson(API + "datastorePut", body.toString()))
        json.optJSONObject("error")?.let { throw Stremio.ApiError(it.optString("message", "library sync failed")) }
    }

    // ---- dates --------------------------------------------------------------------------------

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun iso(ms: Long): String = fmt().format(Date(ms))

    fun parseTime(s: String?): Long {
        if (s.isNullOrBlank() || s == "null") return 0
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            runCatching {
                return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time
            }
        }
        return 0
    }

    // ---- a new item, as Stremio creates one when something is first played ----------------

    fun newItem(id: String, type: String, name: String): JSONObject {
        val now = iso(System.currentTimeMillis())
        return JSONObject()
            .put("_id", id).put("name", name).put("type", type)
            .put("poster", "").put("posterShape", "poster")
            .put("removed", true).put("temp", true)
            .put("_ctime", now).put("_mtime", now)
            .put("state", JSONObject()
                .put("lastWatched", now).put("timeWatched", 0).put("timeOffset", 0)
                .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                .put("duration", 0).put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL)
                .put("noNotif", false))
            .put("behaviorHints", JSONObject()
                .put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL)
                .put("hasScheduledVideos", false))
    }

    // ---- watched episodes: "anchorVideoId:anchorLength:base64(zlib(bits))" -----------------

    /** The ids marked watched, given the show's episode ids in Stremio's order (season, episode, release). */
    fun decodeWatched(field: String?, ids: List<String>): Set<String> {
        if (field.isNullOrBlank() || field == "null" || ids.isEmpty()) return emptySet()
        return runCatching {
            val last = field.lastIndexOf(':')
            val prev = field.lastIndexOf(':', last - 1)
            val anchor = field.substring(0, prev)
            val anchorLength = field.substring(prev + 1, last).toInt()
            val bytes = inflate(Base64.decode(field.substring(last + 1), Base64.DEFAULT))
            val anchorIdx = ids.indexOf(anchor)
            if (anchorIdx < 0) return emptySet()
            val offset = anchorLength - anchorIdx - 1
            val out = HashSet<String>()
            for (i in ids.indices) {
                val j = i + offset
                if (j >= 0 && j / 8 < bytes.size && (bytes[j / 8].toInt() shr (j % 8)) and 1 != 0) out += ids[i]
            }
            out
        }.getOrDefault(emptySet())
    }

    fun encodeWatched(watched: Set<String>, ids: List<String>): String {
        val bytes = ByteArray((ids.size + 7) / 8)
        var lastSet = -1
        ids.forEachIndexed { i, id ->
            if (id in watched) {
                bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
                lastSet = i
            }
        }
        val lastIdx = if (lastSet >= 0) lastSet else 0
        val anchor = ids.getOrNull(lastIdx) ?: "undefined"
        val data = Base64.encodeToString(deflate(bytes), Base64.NO_WRAP)
        return "$anchor:${lastIdx + 1}:$data"
    }

    private fun deflate(input: ByteArray): ByteArray {
        val d = Deflater(6)   // zlib format, level 6, as Stremio uses
        d.setInput(input)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }
}
