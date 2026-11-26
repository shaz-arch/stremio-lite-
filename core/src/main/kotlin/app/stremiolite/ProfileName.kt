package app.stremiolite

/** Names a device gives a profile by itself ("Profile 1", "Me"): never sent to the account, and replaced by the account's name. */
object ProfileName {
    private val AUTO = Regex("""^(Profile \d+|Me)$""")
    fun isAuto(name: String) = name.isBlank() || AUTO.matches(name.trim())
}
