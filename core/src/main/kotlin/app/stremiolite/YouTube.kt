package app.stremiolite

/** Streams that are a YouTube video by its id (YouTubio and the like): played by a YouTube app, or mpv with yt-dlp. */
object YouTube {
    fun watch(id: String) = "https://www.youtube.com/watch?v=$id"

    private val ID = Regex("""(?:youtube\.com/watch\?(?:.*&)?v=|youtu\.be/|youtube\.com/shorts/)([A-Za-z0-9_-]{6,})""")

    /** The video id of a YouTube page link, or null for anything else. */
    fun idOf(url: String): String? = ID.find(url)?.groupValues?.get(1)
}
