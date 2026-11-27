package app.stremiolite

/** Where anyone can download Stremonino (always the newest build). */
object AppLinks {
    private const val BASE = "https://github.com/shaz-arch/stremio-lite-/releases/latest/download/"
    const val ANDROID = BASE + "stremonino.apk"
    const val LINUX = BASE + "stremonino-linux.jar"
    const val PAGE = "https://github.com/shaz-arch/stremio-lite-/releases/latest"

    fun message(): String = "Stremonino, a simple Stremio app:\n" +
        "Android phone, Fire Stick or Android TV: $ANDROID\n" +
        "Linux (needs Java 17): $LINUX\n" +
        "Sign in with your own Stremio account."
}
