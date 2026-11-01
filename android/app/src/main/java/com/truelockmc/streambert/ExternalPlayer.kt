package com.truelockmc.streambert

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Opens a resolved http(s) stream in VLC, MX Player, or Just Player.
 * The intent carries the video MIME type, title, referer, and user agent.
 */
object ExternalPlayer {
    private val KNOWN = listOf(
        "org.videolan.vlc",
        "com.mxtech.videoplayer.pro",
        "com.mxtech.videoplayer.ad",
        "com.brouken.player",
    )
    private val BROWSERS = setOf(
        "com.android.chrome",
        "com.chrome.browser",
        "org.chromium.chrome",
        "com.android.browser",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
    )

    data class Launch(val ok: Boolean, val detail: String)

    fun open(
        context: Context,
        url: String,
        title: String,
        headers: Map<String, String>,
        subtitles: List<String>,
        positionMs: Long,
    ): Launch {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Launch(false, "This source did not return a direct stream.")
        }
        val ready = LinkedHashMap<String, String>()
        for ((key, value) in headers) {
            if (key.isNotBlank() && value.isNotBlank()) ready[key] = value
        }
        if (ready.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            ready["User-Agent"] = StreambertEngine.UA
        }
        val pm = context.packageManager
        val installed = KNOWN.filter { installed(pm, it) }
        var last = "Install VLC, MX Player, or Just Player, then try again."
        for (pkg in installed) {
            for (mime in StreamIntents.mimes(url)) {
                val intent = viewIntent(url, mime, title, ready, subtitles, positionMs).setPackage(pkg)
                val started = start(context, intent)
                if (started == null) return Launch(true, pkg)
                last = started
            }
        }
        for (mime in StreamIntents.mimes(url)) {
            val intent = viewIntent(url, mime, title, ready, subtitles, positionMs)
            val match = resolveNonBrowser(pm, intent) ?: continue
            intent.setClassName(match.first, match.second)
            val started = start(context, intent)
            if (started == null) return Launch(true, match.first)
            last = started
        }
        return Launch(false, last)
    }

    private fun start(context: Context, intent: Intent): String? {
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "The external player could not open this stream."
        } catch (e: SecurityException) {
            e.message ?: "The external player could not open this stream."
        } catch (e: Exception) {
            e.message ?: "The external player could not open this stream."
        }
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveNonBrowser(pm: PackageManager, intent: Intent): Pair<String, String>? {
        val list = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val match = list.firstOrNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@firstOrNull false
            pkg !in BROWSERS && !pkg.contains("browser")
        } ?: return null
        val activity = match.activityInfo ?: return null
        return activity.packageName to activity.name
    }

    private fun viewIntent(
        url: String,
        mime: String,
        title: String,
        headers: Map<String, String>,
        subtitles: List<String>,
        positionMs: Long,
    ): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val shown = title.ifBlank { "Reelix" }
            putExtra(Intent.EXTRA_TITLE, shown)
            putExtra("title", shown)
            putExtra("filename", shown)
            val lines = headers.map { "${it.key}: ${it.value}" }.toTypedArray()
            if (lines.isNotEmpty()) putExtra("headers", lines)
            val bundle = Bundle()
            for ((key, value) in headers) bundle.putString(key, value)
            putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
            val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            if (!referer.isNullOrBlank()) {
                putExtra("http-referrer", referer)
                putExtra("http-referer", referer)
                putExtra("referer", referer)
            }
            val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            if (!agent.isNullOrBlank()) putExtra("http-user-agent", agent)
            val subs = subtitles.filter { it.startsWith("http://") || it.startsWith("https://") }
            if (subs.isNotEmpty()) {
                putExtra("subtitles_location", subs.first())
                val uris = ArrayList<Uri>()
                val names = ArrayList<String>()
                subs.forEachIndexed { index, sub ->
                    uris.add(Uri.parse(sub))
                    names.add("Subtitle ${index + 1}")
                }
                putParcelableArrayListExtra("subs", uris)
                putStringArrayListExtra("subs.name", names)
            }
            if (positionMs > 0) {
                val ms = positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                putExtra("position", ms)
            }
        }
    }
}
