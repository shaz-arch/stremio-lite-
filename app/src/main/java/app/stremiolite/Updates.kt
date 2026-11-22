package app.stremiolite

import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Checks GitHub for a newer build (at most every 30 minutes), and installs it on request. */
object Updates {
    private const val LATEST = "https://api.github.com/repos/shaz-arch/stremio-lite-/releases/latest"
    private const val APK = "https://github.com/shaz-arch/stremio-lite-/releases/latest/download/stremio-lite.apk"

    /** The newer build number, once one has been found. */
    var available: Int? = null
        private set
    private var lastCheck = 0L
    private const val EVERY_MS = 30 * 60_000L

    /** From Settings: check now and say what was found. */
    fun checkNow(app: MainActivity, done: () -> Unit) {
        app.toast("Checking for updates…")
        Net.async({ JSONObject(Net.get(LATEST)).optString("tag_name") }) { r ->
            val n = r.getOrNull()?.removePrefix("build-")?.toIntOrNull()
            when {
                n == null -> app.toast("Couldn't check: ${r.exceptionOrNull()?.let { Net.describe(it) } ?: "no answer"}")
                n > BuildConfig.VERSION_CODE -> { available = n; done() }
                else -> app.toast("You're on the latest build (${BuildConfig.VERSION_CODE}).")
            }
        }
    }

    /** Quietly checks if 30 minutes have passed since the last check; [done] runs only if a newer build exists. */
    fun check(done: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < EVERY_MS) return
        lastCheck = now
        Net.async({ JSONObject(Net.get(LATEST)).optString("tag_name") }) { r ->
            val n = r.getOrNull()?.removePrefix("build-")?.toIntOrNull()
            if (n != null && n > BuildConfig.VERSION_CODE) {
                available = n
                done()
            }
        }
    }

    fun install(app: MainActivity) {
        if (Build.VERSION.SDK_INT >= 24) {
            // Newer Android won't hand a downloaded file to the installer without extra plumbing:
            // let the browser download it instead.
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK)))
            return
        }
        app.toast("Downloading the update…")
        Net.async({
            val f = File(app.getExternalFilesDir(null), "update.apk")
            f.writeBytes(Net.getBytes(APK))
            f
        }) { r ->
            r.onSuccess { f ->
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { e -> app.toast("Update download failed: ${Net.describe(e)}") }
        }
    }
}
