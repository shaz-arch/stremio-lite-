package app.stremiolite

import android.content.Context

object Account {
    fun authKey(context: Context): String? = Profiles.current(context)?.authKey

    fun email(context: Context): String = Profiles.current(context)?.email ?: ""

    fun saveEmail(context: Context, email: String) = Profiles.updateEmail(context, email)

    /** Signs the current profile out of this device (the Stremio account itself is untouched). */
    fun clear(context: Context) {
        val p = Profiles.current(context) ?: return
        addonCache(context).delete()
        Profiles.remove(context, p.id)
    }

    /** Last known add-on list for the current profile, so Home appears instantly at start-up. */
    fun addonCache(context: Context) = java.io.File(context.filesDir, Profiles.fileName(context, "addons"))
}
