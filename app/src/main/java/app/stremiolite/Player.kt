package app.stremiolite

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Hands a stream link to an external player. The app never decodes video itself. */
object Player {
    const val REQUEST_PLAY = 7
    const val VLC = "org.videolan.vlc"
    const val JUST_PLAYER = "com.brouken.player"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Plays [url] in the player chosen in Settings (or [force] if given), passing [subs] along.
     * VLC takes one subtitle link; Just Player takes a list.
     */
    fun play(
        activity: MainActivity,
        url: String,
        title: String,
        subs: List<Subtitles.Sub> = emptyList(),
        force: String? = null,
        watch: History.Watch? = null,
        startAt: Long = 0,
    ) {
        val uri = Uri.parse(url)
        val choice = force ?: Settings.player(activity).pkg
        fun viewIntent() = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("title", title)
            if (subs.isNotEmpty()) {
                // VLC
                putExtra("subtitles_location", subs.first().url)
                // Just Player
                val subUris: Array<android.os.Parcelable> = subs.map { Uri.parse(it.url) as android.os.Parcelable }.toTypedArray()
                putExtra("subs", subUris)
                putExtra("subs.name", subs.map { Subtitles.languageName(it.lang) + " · " + it.addon }.toTypedArray())
                putExtra("subs.enable", arrayOf(subUris.first()))
            }
            // Ask the player to report where playback stopped (VLC always does when started for a result;
            // Just Player follows the MX Player convention).
            putExtra("return_result", true)
        }

        val attempts = mutableListOf<Intent>()
        when (choice) {
            VLC -> {
                // VLC's documented way for other apps: open its player screen directly.
                val direct = viewIntent().setComponent(ComponentName(VLC, "org.videolan.vlc.gui.video.VideoPlayerActivity"))
                if (startAt > 0) direct.putExtra("position", startAt) else direct.putExtra("from_start", true)
                attempts += direct
                attempts += viewIntent().setPackage(VLC).also { if (startAt > 0) it.putExtra("position", startAt) }
            }
            null -> Unit
            // Just Player and other MX-style players take the start position as an int
            else -> attempts += viewIntent().setPackage(choice).also { if (startAt > 0) it.putExtra("position", startAt.toInt()) }
        }
        History.setPending(activity, watch)

        for (intent in attempts) {
            try {
                activity.startActivityForResult(intent, REQUEST_PLAY)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next way
            } catch (e: SecurityException) {
                // try the next way
            }
        }
        // "Ask each time", or the chosen player is missing: let Android offer what can play video.
        try {
            activity.startActivityForResult(Intent.createChooser(viewIntent(), "Play with"), REQUEST_PLAY)
        } catch (e: ActivityNotFoundException) {
            activity.toast("No video player found. Install VLC from the Appstore.")
        }
    }

    /** 20-second 720p test clip stored in this app's own GitHub repo (testmedia/). */
    const val TEST_CLIP =
        "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/testmedia/test-clip.mp4"
}
