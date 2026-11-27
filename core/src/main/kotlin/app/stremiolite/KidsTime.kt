package app.stremiolite

import org.json.JSONObject
import java.util.Calendar

/**
 * Bedtime and a daily watch limit for locked profiles ([KidsLock]). Time watched is counted on each
 * device (kept there, per profile), from when the player opens to when it closes. The parent PIN lets
 * a title play anyway, for the rest of the day.
 */
object KidsTime {
    /** The daily-limit choices, in minutes (0 = off). */
    val DAILY = listOf(0, 60, 90, 120, 180)
    /** Bedtime choices (minutes past midnight; -1 = off). */
    val BEDTIMES = listOf(-1, 19 * 60, 19 * 60 + 30, 20 * 60, 20 * 60 + 30, 21 * 60, 21 * 60 + 30, 22 * 60)
    val WAKES = listOf(6 * 60, 6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60)

    fun clock(m: Int): String {
        val h = m / 60; val mm = m % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12" + (if (mm == 0) "" else ":" + mm.toString().padStart(2, '0')) + if (h < 12) "am" else "pm"
    }
    fun dailyLabel(m: Int) = when { m <= 0 -> "no limit"; m % 60 == 0 -> "${m / 60} hour" + (if (m == 60) "" else "s") + " a day"; else -> "${m / 60}h ${m % 60}m a day" }
    fun bedtimeLabel(s: KidsLock.State) = if (s.bedtime < 0) "off" else clock(s.bedtime) + " to " + clock(s.wake)
    fun <T> next(list: List<T>, now: T): T = list[(list.indexOf(now).coerceAtLeast(0) + 1) % list.size]

    fun day(now: Calendar = Calendar.getInstance()) =
        "%04d-%02d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))

    private fun minuteOf(now: Calendar) = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

    /** In the bedtime hours now (bedtime to wake, across midnight). */
    fun isBedtime(s: KidsLock.State, now: Calendar = Calendar.getInstance()): Boolean {
        if (s.bedtime < 0) return false
        val m = minuteOf(now)
        return if (s.bedtime > s.wake) m >= s.bedtime || m < s.wake else m >= s.bedtime && m < s.wake
    }

    /** Time watched today, from the stored record ({"day":…, "ms":…}). */
    fun used(raw: String?, today: String = day()): Long {
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return 0
        return if (o.optString("day") == today) o.optLong("ms") else 0
    }
    fun add(raw: String?, ms: Long, today: String = day()): String =
        JSONObject().put("day", today).put("ms", used(raw, today) + ms.coerceAtLeast(0)).toString()

    /** Whether the PIN let things play today ({"day":…, "ok":true} in the same record). */
    fun overridden(raw: String?, today: String = day()) =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { it.optString("day") == today && it.optBoolean("ok") } == true
    fun withOverride(raw: String?, today: String = day()): String =
        JSONObject().put("day", today).put("ms", used(raw, today)).put("ok", true).toString()

    /** Why [profileId] can't watch now, or null if it can. */
    fun blocked(s: KidsLock.State, profileId: String?, raw: String?, now: Calendar = Calendar.getInstance()): String? {
        if (!s.isLocked(profileId) || overridden(raw, day(now))) return null
        if (isBedtime(s, now)) return "It's bedtime (from ${clock(s.bedtime)} until ${clock(s.wake)})"
        if (s.dailyMins > 0 && used(raw, day(now)) >= s.dailyMins * 60_000L) return "Today's watch time (${dailyLabel(s.dailyMins).removeSuffix(" a day")}) is used up"
        return null
    }

    /** How long [profileId] may keep watching from now (null: no limit). */
    fun leftMs(s: KidsLock.State, profileId: String?, raw: String?, now: Calendar = Calendar.getInstance()): Long? {
        if (!s.isLocked(profileId) || overridden(raw, day(now))) return null
        var left: Long? = null
        if (s.dailyMins > 0) left = (s.dailyMins * 60_000L - used(raw, day(now))).coerceAtLeast(0)
        if (s.bedtime >= 0) {
            val toBed = ((s.bedtime - minuteOf(now) + 24 * 60) % (24 * 60)) * 60_000L - now.get(Calendar.SECOND) * 1000L
            left = minOf(left ?: Long.MAX_VALUE, toBed.coerceAtLeast(0))
        }
        return left
    }

    /** "45 min left today", for the Home line of a locked profile. */
    fun summary(s: KidsLock.State, profileId: String?, raw: String?): String? {
        if (!s.isLocked(profileId) || (s.dailyMins <= 0 && s.bedtime < 0)) return null
        blocked(s, profileId, raw)?.let { return it }
        if (overridden(raw)) return "allowed by a parent today"
        val left = leftMs(s, profileId, raw) ?: return null
        val m = left / 60_000
        return if (m >= 60) "${m / 60}h ${m % 60}m left today" else "$m min left today"
    }
}
