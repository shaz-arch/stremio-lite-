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
    /** Opens a YouTube video in a YouTube app: SmartTube, YouTube for TV / Fire TV, or the phone's YouTube. */
    private fun openYouTube(activity: MainActivity, id: String, title: String) {
        val link = android.net.Uri.parse(YouTube.watch(id))
        val pm = activity.packageManager
        val apps = listOf("com.teamsmart.videomanager.tv", "org.smarttube.stable", "com.liskovsoft.smarttubetv.beta", "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube")
        val tries = apps.filter { p -> runCatching { pm.getPackageInfo(p, 0) }.isSuccess }.map { p -> android.content.Intent(android.content.Intent.ACTION_VIEW, link).setPackage(p) } +
            listOf(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id")), android.content.Intent(android.content.Intent.ACTION_VIEW, link))
        for (i in tries) {
            if (runCatching { activity.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) { activity.toast("Opening in YouTube: $title"); return }
        }
        activity.toast("Install a YouTube app (YouTube or SmartTube) to play YouTube videos")
    }

    fun play(
        activity: MainActivity,
        url: String,
        title: String,
        subs: List<Sub> = emptyList(),
        force: String? = null,
        watch: History.Watch? = null,
        startAt: Long = 0,
    ) {
        // A locked profile's age limit: nothing over it (or not rated yet) plays.
        if (watch != null && !KidsGuard.allows(activity, watch.id)) {
            activity.toast("Not allowed on this profile (" + (AgeRatings.of(watch.id)?.ifBlank { null } ?: "not rated yet") + ")")
            return
        }
        // A locked profile at bedtime, or with today's time used up: only with the parent PIN.
        if (KidsGuard.blockedNow(activity) != null) {
            KidsGuard.checkTime(activity) { play(activity, url, title, subs, force, watch, startAt) }
            return
        }
        // A YouTube video: VLC can't play those reliably, so a YouTube app does.
        YouTube.idOf(url)?.let { id -> openYouTube(activity, id, title); return }
        // Skip intros: an episode played from the start begins after an opening recap or intro.
        var startAt = startAt
        if (startAt == 0L && watch != null && ':' in watch.videoId && Settings.skipIntros(activity)) {
            Skips.known(watch.videoId)?.let { Skips.startAfterOpening(it) }?.takeIf { it > 0 }?.let {
                startAt = it; activity.toast("Starting after the intro (" + (it / 1000) + "s in)")
            }
        }
        val uri = Uri.parse(url)
        val choice = force ?: Settings.player(activity).pkg
        // A TV on the network: it plays the link itself, so this device isn't tied up.
        if (choice == Cast.PKG) {
            if (url.contains("127.0.0.1")) { activity.toast("Google Drive videos can't be cast yet: playing here"); }
            else { activity.push(Cast.screen(activity, url, title)); return }
        }
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
            null, Cast.PKG -> Unit
            // Just Player and other MX-style players take the start position as an int
            else -> attempts += viewIntent().setPackage(choice).also { if (startAt > 0) it.putExtra("position", startAt.toInt()) }
        }
        History.setPending(activity, watch)

        for (intent in attempts) {
            try {
                activity.startActivityForResult(intent, REQUEST_PLAY)
                activity.playerOpen = true
                Sleep.started(startAt)
                KidsGuard.arm(activity)
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
            activity.playerOpen = true
            Sleep.started(startAt)
            KidsGuard.arm(activity)
        } catch (e: ActivityNotFoundException) {
            activity.toast("No video player found. Install VLC from the Appstore.")
        }
    }

    /** 20-second 720p test clip stored in this app's own GitHub repo (testmedia/). */
    const val TEST_CLIP =
        "https://raw.githubusercontent.com/shaz-arch/stremio-lite-/main/testmedia/test-clip.mp4"
}
