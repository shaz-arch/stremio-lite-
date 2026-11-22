package app.stremiolite

class Language(val name: String, val codes: List<String>)

class Sub(val url: String, val lang: String, val addon: String)

/** Subtitle languages offered in Settings, and matching subtitles to them. */
object Languages {

/** Off first, then the most likely choices. Codes cover both 3-letter and 2-letter forms. */
    val all = listOf(
    Language("Off", emptyList()),
    Language("English", listOf("eng", "en")),
    Language("Urdu", listOf("urd", "ur")),
    Language("Hindi", listOf("hin", "hi")),
    Language("Punjabi", listOf("pan", "pa")),
    Language("Arabic", listOf("ara", "ar")),
    Language("French", listOf("fre", "fra", "fr")),
    Language("Spanish", listOf("spa", "es")),
    Language("German", listOf("ger", "deu", "de")),
    Language("Italian", listOf("ita", "it")),
    Language("Portuguese", listOf("por", "pt", "pob")),
    Language("Polish", listOf("pol", "pl")),
    Language("Turkish", listOf("tur", "tr")),
    Language("Dutch", listOf("dut", "nld", "nl")),
    )

    fun matches(sub: Sub, lang: Language) =
        lang.codes.any { sub.lang.equals(it, ignoreCase = true) } || sub.lang.equals(lang.name, ignoreCase = true)

    fun name(code: String): String =
        all.firstOrNull { l -> l.codes.any { it.equals(code, true) } }?.name ?: code.ifEmpty { "?" }
}
