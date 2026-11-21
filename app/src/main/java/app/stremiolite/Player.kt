package app.stremiolite

import android.content.ActivityNotFoundException
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", title)
        }
        try {
            activity.startActivity(Intent(intent).setPackage(preferred))
        } catch (e: ActivityNotFoundException) {
            // Preferred player missing: let Android offer whatever can play video.
            try {
                activity.startActivity(Intent.createChooser(intent, "Play with"))
            } catch (e2: ActivityNotFoundException) {
                activity.toast("No video player found. Install VLC from the Appstore.")
            }
        }
    }

    /** A public test clip used by the device check (Big Buck Bunny, Blender Foundation). */
    const val TEST_CLIP =
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
}
