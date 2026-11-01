package com.truelockmc.streambert

/** MIME types an external player can be handed for a direct stream URL. */
object StreamIntents {
    fun mimes(url: String): List<String> {
        val path = url.lowercase().substringBefore("?")
        val hls = path.contains(".m3u8") || path.contains("mpegurl") || path.contains("/hls/")
        if (hls) return listOf("application/x-mpegURL", "video/*")
        val specific = when {
            path.contains(".mpd") -> "application/dash+xml"
            path.contains(".webm") -> "video/webm"
            path.contains(".mkv") -> "video/x-matroska"
            path.contains(".mp4") || path.contains(".m4v") -> "video/mp4"
            else -> null
        }
        return if (specific == null) listOf("video/*") else listOf("video/*", specific)
    }
}
