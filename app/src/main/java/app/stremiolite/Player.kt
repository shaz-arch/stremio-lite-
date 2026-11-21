package app.stremiolite

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Hands a stream link to an external player. The app never decodes video itself. */
object Player {
    const val VLC = "org.videolan.vlc"
    const val JUST_PLAYER = "com.brouken.player"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun play(activity: MainActivity, url: String, title: String, preferred: String = VLC) {
        val uri = Uri.parse(url)
        fun viewIntent() = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("title", title)
        }

        val attempts = mutableListOf<Intent>()
        if (preferred == VLC) {
            // VLC's documented way for other apps: open its player screen directly.
            attempts += viewIntent().setComponent(
                ComponentName(VLC, "org.videolan.vlc.gui.video.VideoPlayerActivity")
            ).putExtra("from_start", true)
        }
        attempts += viewIntent().setPackage(preferred)

        for (intent in attempts) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next way
            } catch (e: SecurityException) {
                // try the next way
            }
        }
        // Preferred player missing: let Android offer whatever can play video.
        try {
            activity.startActivity(Intent.createChooser(viewIntent(), "Play with"))
        } catch (e: ActivityNotFoundException) {
            activity.toast("No video player found. Install VLC from the Appstore.")
        }
    }

    /** 20-second 720p test clip stored in this app's own GitHub repo (testmedia/). */
    const val TEST_CLIP =
        "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/testmedia/test-clip.mp4"
}
