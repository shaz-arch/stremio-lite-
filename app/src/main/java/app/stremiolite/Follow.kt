package app.stremiolite

import android.content.Context
import org.json.JSONObject

/** Following shows for new episodes and seasons (rules in the shared Following), kept apart from the Library. */
object Follow {
    private fun record(c: Context) = History.rawRemote(c, Following.ID)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Followed shows; [lists]: shows from your own list rows (fetched in the background). */
    fun shows(c: Context, lists: List<Pair<String, String>> = emptyList()) = Following.shows(History.infos(c), record(c), lists, Settings.autoFollow(c))

    /** Stops following all of [ids] in one change. */
    fun unfollowAll(c: Context, ids: List<String>) {
        var rec = record(c)
        ids.forEach { rec = Following.unfollow(rec, it) }
        rec?.let { History.putRemote(c, it) }
    }

    fun stopped(c: Context) = Following.stopped(record(c))

    fun isFollowed(c: Context, id: String) = Following.isFollowed(id, History.infos(c), record(c), Settings.autoFollow(c))

    fun follow(c: Context, id: String, name: String) = History.putRemote(c, Following.follow(record(c), id, name))

    fun unfollow(c: Context, id: String) = History.putRemote(c, Following.unfollow(record(c), id))
}
