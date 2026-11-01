package com.truelockmc.streambert

import java.net.URI
import java.net.URLEncoder

/** Pure HLS playlist parsing and proxy rewriting. No Android dependencies. */
object HlsPlaylist {
    data class Variant(val url: String, val bandwidth: Int)
    data class Segment(val url: String, val sequence: Int)
    data class KeyInfo(val method: String, val uri: String?, val ivHex: String?)
    data class Playlist(
        val isMaster: Boolean,
        val variants: List<Variant>,
        val segments: List<Segment>,
        val initUrl: String?,
        val key: KeyInfo?,
        val mediaSequence: Int,
        val isFmp4: Boolean,
    )

    fun abs(base: String, ref: String): String {
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    fun parse(text: String, base: String): Playlist {
        val lines = text.split(Regex("\\r?\\n"))
        var isMaster = false
        val variants = mutableListOf<Variant>()
        val segments = mutableListOf<Segment>()
        var bandwidth = 0
        var expectSegment = false
        var initUrl: String? = null
        var key: KeyInfo? = null
        var mediaSequence = 0
        var seq = 0
        var isFmp4 = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                isMaster = true
                bandwidth = attrInt(line, "BANDWIDTH") ?: 0
                expectSegment = true
                continue
            }
            if (line.startsWith("#EXTINF")) {
                expectSegment = true
                continue
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                mediaSequence = line.substringAfter(":").trim().toIntOrNull() ?: 0
                seq = mediaSequence
                continue
            }
            if (line.startsWith("#EXT-X-MAP")) {
                val uri = attrQuoted(line, "URI")
                if (uri != null) {
                    initUrl = abs(base, uri)
                    isFmp4 = true
                }
                continue
            }
            if (line.startsWith("#EXT-X-KEY")) {
                val method = attrQuoted(line, "METHOD") ?: attrRaw(line, "METHOD") ?: "NONE"
                val uri = attrQuoted(line, "URI")?.let { abs(base, it) }
                val iv = attrRaw(line, "IV")
                key = KeyInfo(method, uri, iv)
                continue
            }
            if (line.startsWith("#")) continue
            if (isMaster && !expectSegment) continue
            val url = abs(base, line)
            if (isMaster) variants.add(Variant(url, bandwidth))
            else {
                segments.add(Segment(url, seq))
                if (url.contains(".m4s", true) || url.contains(".mp4", true)) isFmp4 = true
                seq++
            }
            expectSegment = false
        }
        return Playlist(isMaster, variants, segments, initUrl, key, mediaSequence, isFmp4)
    }

    fun bestVariant(playlist: Playlist): String? {
        return playlist.variants.maxByOrNull { it.bandwidth }?.url
    }

    fun rewrite(text: String, playlistUrl: String, token: String): String {
        fun proxied(u: String): String {
            val absUrl = abs(playlistUrl, u)
            return "https://player.streambert.local/proxy?t=$token&url=" +
                URLEncoder.encode(absUrl, "UTF-8")
        }
        return text.split(Regex("\\r?\\n")).joinToString("\n") { line ->
            val t = line.trim()
            if (t.isEmpty()) line
            else if (t.startsWith("#")) {
                Regex("URI=\"([^\"]+)\"").replace(line) { m ->
                    "URI=\"${proxied(m.groupValues[1])}\""
                }
            } else proxied(t)
        }
    }

    private fun attrQuoted(line: String, name: String): String? {
        val m = Regex("$name=\"([^\"]*)\"").find(line) ?: return null
        return m.groupValues[1]
    }

    private fun attrRaw(line: String, name: String): String? {
        val m = Regex("$name=([^,]*)").find(line) ?: return null
        return m.groupValues[1].trim().trim('"')
    }

    private fun attrInt(line: String, name: String): Int? = attrRaw(line, name)?.toIntOrNull()
}
