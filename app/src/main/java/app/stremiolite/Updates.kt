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

    const val ACTION_STATUS = "app.stremiolite.INSTALL_STATUS"
    private var busy = false

    /**
     * Downloads the new build here and hands it to Android's own installer (PackageInstaller), so it
     * installs in place: no browser, no Downloader. Newer Android (and Fire OS 7/8) first needs
     * "Install unknown apps" allowed for Stremonino, once.
     */
    fun install(app: MainActivity) {
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            app.toast("Allow Stremonino to install apps (once), then choose Install again")
            runCatching {
                app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)))
            }.onFailure {
                app.toast("Settings › Applications (or My Fire TV › Developer options) › Install unknown apps › Stremonino: On")
            }
            return
        }
        if (busy) { app.toast("Already downloading the update…"); return }
        busy = true
        app.toast("Downloading the update…")
        Net.async({
            val pi = app.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(app.packageName)
            val id = pi.createSession(params)
            val session = pi.openSession(id)
            try {
                val c = java.net.URL(APK).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", Http.userAgent)
                if (c.responseCode != 200) throw Http.HttpError(c.responseCode)
                val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                c.inputStream.use { input ->
                    session.openWrite("stremonino.apk", 0, len).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        session.fsync(out)
                    }
                }
                val back = Intent(app, MainActivity::class.java).setAction(ACTION_STATUS)
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) 33554432 /* FLAG_MUTABLE */ else 0)
                session.commit(android.app.PendingIntent.getActivity(app, id, back, flags).intentSender)
            } catch (e: Exception) {
                runCatching { session.abandon() }
                throw e
            } finally { session.close() }
        }) { r ->
            busy = false
            r.onSuccess { app.toast("Installing…") }
                .onFailure { e -> app.toast("Update download failed: ${Net.describe(e)}") }
        }
    }

    /** Android's installer answering: ask you to confirm, or say how it went. */
    fun status(app: MainActivity, intent: Intent?): Boolean {
        if (intent?.action != ACTION_STATUS) return false
        val st = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        when (st) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                (intent.extras?.get(Intent.EXTRA_INTENT) as? Intent)?.let { runCatching { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> app.toast("Updated")
            else -> app.toast("The update didn't install" + (intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: ""))
        }
        return true
    }
}
