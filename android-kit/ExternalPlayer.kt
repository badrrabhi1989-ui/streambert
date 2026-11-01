package com.truelockmc.streambert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Hands a resolved stream to another app. VLC, MX Player, and Just Player
 * receive the title, request headers, and subtitle URLs when they understand them.
 */
object ExternalPlayer {
    private val KNOWN = listOf(
        "org.videolan.vlc",
        "com.mxtech.videoplayer.pro",
        "com.mxtech.videoplayer.ad",
        "com.brouken.player",
    )

    fun open(
        context: Context,
        url: String,
        title: String,
        headers: Map<String, String>,
        subtitles: List<String>,
        positionMs: Long,
    ): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val specific = mimeFor(url)
        val mimes = if (specific == "video/*") listOf("video/*") else listOf("video/*", specific)
        val pm = context.packageManager
        for ((attempt, mime) in mimes.withIndex()) {
            val view = viewIntent(url, mime, title, headers, subtitles, positionMs)
            val resolved = try {
                view.resolveActivity(pm) != null
            } catch (_: Exception) {
                false
            }
            if (!resolved && attempt < mimes.lastIndex) continue
            val chooser = Intent.createChooser(view, "Play in external player")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val initials = knownIntents(view, context)
            if (initials.isNotEmpty()) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initials.toTypedArray())
            }
            try {
                context.startActivity(chooser)
                return true
            } catch (_: Exception) {
                continue
            }
        }
        return false
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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra("title", title)
            putExtra("filename", title)
            val lines = headers.entries
                .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                .map { "${it.key}: ${it.value}" }
                .toTypedArray()
            if (lines.isNotEmpty()) {
                putExtra("headers", lines)
                val bundle = Bundle()
                for ((key, value) in headers) {
                    if (key.isNotBlank() && value.isNotBlank()) bundle.putString(key, value)
                }
                putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
            }
            val referer = headers["Referer"] ?: headers["referer"]
            if (!referer.isNullOrBlank()) {
                putExtra("http-referrer", referer)
                putExtra("referer", referer)
            }
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
            if (positionMs > 0) putExtra("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun knownIntents(template: Intent, context: Context): ArrayList<Intent> {
        val pm = context.packageManager
        val found = ArrayList<Intent>()
        for (pkg in KNOWN) {
            val specific = Intent(template).setPackage(pkg)
            val visible = try {
                specific.resolveActivity(pm) != null
            } catch (_: Exception) {
                false
            }
            if (visible) found.add(specific)
        }
        return found
    }

    private fun mimeFor(url: String): String {
        val path = url.lowercase().substringBefore("?")
        return when {
            path.contains(".m3u8") || path.contains("mpegurl") -> "application/vnd.apple.mpegurl"
            path.contains(".mpd") -> "application/dash+xml"
            path.contains(".webm") -> "video/webm"
            path.contains(".mkv") -> "video/x-matroska"
            path.contains(".mp4") -> "video/mp4"
            else -> "video/*"
        }
    }
}
