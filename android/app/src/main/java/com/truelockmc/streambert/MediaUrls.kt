package com.truelockmc.streambert

/**
 * Decides which sniffed request is a real movie stream and which is an ad,
 * a thumbnail, or a media segment. Higher rank wins.
 */
object MediaUrls {
    fun rank(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val url = raw.trim()
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return 0
        if (lower.startsWith("blob:") || lower.startsWith("data:")) return 0
        if (isJunk(lower)) return 0
        if (lower.contains(".m3u8") || lower.contains("mpegurl") || lower.contains("format=m3u8") || lower.contains("playlist.m3u")) {
            if (lower.contains("master")) return 100
            if (lower.contains("index")) return 80
            return 60
        }
        if (lower.contains(".mpd")) return 70
        if (lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".webm")) {
            if (lower.contains("preview") || lower.contains("thumb") || lower.contains("trailer")) return 0
            return 50
        }
        return 0
    }

    fun isSubtitle(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val lower = raw.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (isJunk(lower)) return false
        return lower.contains(".vtt") || lower.contains(".srt") || lower.contains("format=vtt")
    }

    private fun isJunk(lower: String): Boolean {
        val blocked = listOf(
            "doubleclick", "googlesyndication", "googleadservices", "adservice",
            "imasdk", "scorecardresearch", "facebook.net", "adnxs", "2mdn.net",
            "amazon-adsystem", "preroll", "/ads/", "/ad/", "popads", "exoclick",
            ".ts?", ".m4s", ".aac", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".css", ".js",
        )
        if (blocked.any { lower.contains(it) }) return true
        if (lower.endsWith(".ts") || lower.endsWith(".m4s") || lower.endsWith(".js")) return true
        return false
    }
}
