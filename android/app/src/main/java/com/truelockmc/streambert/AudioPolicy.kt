package com.truelockmc.streambert

import androidx.media3.common.MimeTypes

/**
 * Picks one audio track. AAC and other stereo codecs come before surround
 * passthrough codecs. A surround track is still chosen when it is the only one,
 * so the FFmpeg renderer can downmix it.
 */
object AudioPolicy {
    data class Track(
        val mime: String?,
        val channels: Int,
        val language: String?,
        val supported: Boolean,
    )

    fun index(tracks: List<Track>, preferredLanguage: String): Int {
        if (tracks.isEmpty()) return -1
        val pool = tracks.withIndex().filter { it.value.supported }.ifEmpty { tracks.withIndex() }
        var best = pool.first().index
        var bestScore = Int.MAX_VALUE
        for (item in pool) {
            val score = score(item.value, preferredLanguage)
            if (score < bestScore) {
                bestScore = score
                best = item.index
            }
        }
        return best
    }

    private fun score(track: Track, preferredLanguage: String): Int {
        val lang = AudioLabels.languageCode(track.language)
        val want = AudioLabels.languageCode(preferredLanguage)
        val langScore = when {
            want.isNotBlank() && lang == want -> 0
            want.isBlank() || lang.isBlank() -> 1
            else -> 2
        }
        val mimeScore = when (track.mime) {
            MimeTypes.AUDIO_AAC -> 0
            MimeTypes.AUDIO_MPEG -> 1
            MimeTypes.AUDIO_OPUS -> 2
            MimeTypes.AUDIO_VORBIS -> 3
            MimeTypes.AUDIO_FLAC -> 4
            MimeTypes.AUDIO_AC3 -> 5
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> 6
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> 7
            MimeTypes.AUDIO_TRUEHD -> 8
            else -> 9
        }
        val channels = if (track.channels <= 0) 2 else track.channels
        val channelScore = if (channels <= 2) 0 else 1
        return langScore * 100 + mimeScore * 10 + channelScore
    }
}
