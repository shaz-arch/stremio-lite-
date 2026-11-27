package app.stremiolite

import android.content.Intent

/** Settings › Share › Share Stremonino: the download links, to send or scan. */
object AppShare {
    private fun send(app: MainActivity, text: String) {
        runCatching { app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share Stremonino")) }
            .onFailure { LinkOut.copy(app, text, "Link") }
    }

    private fun link(app: MainActivity, label: String, url: String, text: String): Row =
        if (app.isPhone) Row(label, "share  ►", action = { send(app, text) })
        else Row(label, "QR  ►", opens = { LinkOut.qr(label, url) })

    fun screen(app: MainActivity): Screen = Screen("Share Stremonino", listOfNotNull(
        if (app.isPhone) Row("Share all the links", "►", action = { send(app, AppLinks.message()) }) else null,
        Row("──── Android phone, Fire Stick, Android TV ────"),
        link(app, "Android app", AppLinks.ANDROID, "Stremonino for Android: ${AppLinks.ANDROID}"),
        Row(info("Downloader code", AppLinks.DOWNLOADER_CODE)),
        Row(info("Or type in Downloader", AppLinks.ANDROID.removePrefix("https://"))),
        Row("──── Linux ────"),
        link(app, "Linux app", AppLinks.LINUX, "Stremonino for Linux (needs Java 17): ${AppLinks.LINUX}\nRun it with: java -jar stremonino-linux.jar"),
        Row(info("Run it with", "java -jar stremonino-linux.jar")),
        Row("──── All builds ────"),
        LinkOut.row(app, "Releases page", "Stremonino releases", AppLinks.PAGE),
    ))
}
