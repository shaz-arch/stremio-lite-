package app.stremiolite

import org.json.JSONObject

/**
 * The account's library records (one per title), read and updated the way Stremio's own
 * player does it. Shared so the Android and Linux apps write identical records.
 */
object LibraryItems {
    /** One record, read. [offset] in ms; 1 means "next episode ready" (Stremio's marker). */
    class Info(
        val id: String,
        val type: String,
        val name: String,
        val removed: Boolean,
        val temp: Boolean,
        val offset: Long,
        val duration: Long,
        val videoId: String,
        val updated: Long,
        val timesWatched: Int,
        val flaggedWatched: Int,
        val watchedField: String,
    ) {
        val inLibrary get() = !removed && !temp && type != "other"
        val continuing get() = (!removed || temp) && offset > 0 && type != "other"
        val upNext get() = offset == 1L
        val filmWatched get() = type == "movie" && (flaggedWatched == 1 || timesWatched > 0)
    }

    fun read(o: JSONObject): Info {
        val id = o.optString("_id")
        val st = o.optJSONObject("state") ?: JSONObject()
        return Info(
            id = id,
            type = o.optString("type"),
            name = o.optString("name"),
            removed = o.optBoolean("removed"),
            temp = o.optBoolean("temp"),
            offset = st.optLong("timeOffset"),
            duration = st.optLong("duration"),
            videoId = st.optString("video_id").takeUnless { it.isBlank() || it == "null" } ?: id,
            updated = Sync.parseTime(st.optString("lastWatched")).takeIf { it > 0 } ?: Sync.parseTime(o.optString("_mtime")),
            timesWatched = st.optInt("timesWatched"),
            flaggedWatched = st.optInt("flaggedWatched"),
            watchedField = st.optString("watched"),
        )
    }

    /**
     * Updates (or creates) a title's record after watching [videoId] up to [pos] of [dur] ms.
     * [done]: watched to the end. [videoIds]: a show's episodes in Stremio's order (for the watched
     * marks); [alsoDone]: other episodes known to be finished. [nextVideoId]: the episode after.
     */
    fun update(
        existing: JSONObject?,
        id: String,
        type: String,
        name: String,
        videoId: String,
        pos: Long,
        dur: Long,
        done: Boolean,
        positionKnown: Boolean,
        videoIds: List<String>,
        alsoDone: Set<String>,
        nextVideoId: String?,
        now: Long = System.currentTimeMillis(),
    ): JSONObject {
        val item = existing ?: Sync.newItem(id, type, name)
        item.put("name", name).put("type", type).put("_mtime", Sync.iso(now))
        val st = item.optJSONObject("state") ?: JSONObject().also { item.put("state", it) }
        st.put("lastWatched", Sync.iso(now))
        if (st.optString("video_id") != videoId) {
            st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
            st.put("timeWatched", 0).put("flaggedWatched", 0).put("video_id", videoId).put("timeOffset", 0)
        }
        val gained = (pos - st.optLong("timeOffset")).coerceAtLeast(0)
        st.put("timeWatched", st.optLong("timeWatched") + gained)
        st.put("overallTimeWatched", st.optLong("overallTimeWatched") + gained)
        if (dur > 0) st.put("duration", dur)
        if (done) {
            st.put("flaggedWatched", 1).put("timesWatched", st.optInt("timesWatched") + 1)
            if (videoIds.isNotEmpty()) {
                val marked = Sync.decodeWatched(st.optString("watched"), videoIds).toMutableSet()
                videoIds.filterTo(marked) { it in alsoDone }
                marked += videoId
                st.put("watched", Sync.encodeWatched(marked, videoIds))
            }
            if (nextVideoId != null) {
                // Stremio's "next episode" marker: the next video with a position of 1
                st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
                st.put("video_id", nextVideoId).put("timeWatched", 0).put("flaggedWatched", 0).put("timeOffset", 1)
            } else {
                st.put("timeOffset", 0)
            }
        } else if (positionKnown) {
            st.put("timeOffset", pos.coerceAtLeast(1))
        }
        return item
    }

    /** Counts as finished near the end: the last 8%, or the last 2 minutes (Stremio uses 90%). */
    fun finished(pos: Long, dur: Long) = dur > 0 && (pos >= dur * 0.92 || dur - pos < 120_000)
}
