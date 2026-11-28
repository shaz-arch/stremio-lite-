package app.stremiolite

/** Subtitle links from the account's subtitle add-ons, for the title being played. */
object Subtitles {

    /** A recent fetch for this title (from fetching early), or a new one ([SubtitleFetch], shared with the Linux app). */
    fun get(app: MainActivity, type: String, id: String): SubtitleFetch = SubtitleFetch.get(type, id, Home.allAddons())

    fun prefetch(app: MainActivity, type: String, id: String) {
        if (Settings.subtitleLanguage(app).codes.isNotEmpty()) get(app, type, id)
    }

    fun matches(sub: Sub, lang: Language) = Languages.matches(sub, lang)

    fun languageName(code: String): String = Languages.name(code)
}
