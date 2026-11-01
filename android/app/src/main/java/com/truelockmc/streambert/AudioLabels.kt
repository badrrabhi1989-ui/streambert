package com.truelockmc.streambert

import java.util.Locale

/** Display names for in-stream audio tracks: language, channel layout, and codec. */
object AudioLabels {
    private val ISO3 = mapOf(
        "eng" to "en",
        "ara" to "ar",
        "fra" to "fr",
        "fre" to "fr",
        "spa" to "es",
        "deu" to "de",
        "ger" to "de",
        "por" to "pt",
        "ita" to "it",
        "tur" to "tr",
        "hin" to "hi",
        "jpn" to "ja",
        "kor" to "ko",
        "zho" to "zh",
        "chi" to "zh",
        "rus" to "ru",
        "pol" to "pl",
        "nld" to "nl",
        "dut" to "nl",
        "swe" to "sv",
    )

    fun languageCode(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val bare = code.lowercase(Locale.ROOT).substringBefore("-").substringBefore("_")
        if (bare == "und" || bare == "undetermined" || bare == "mul") return ""
        if (bare.length == 2) return bare
        return ISO3[bare] ?: ""
    }

    fun languageName(code: String?): String {
        val norm = languageCode(code)
        if (norm.isBlank()) return "Original"
        val name = Locale.forLanguageTag(norm).getDisplayLanguage(Locale.ENGLISH)
        if (name.isBlank() || name.equals(norm, ignoreCase = true)) return "Original"
        return name
    }

    fun channels(count: Int): String = when (count) {
        1 -> "1.0"
        2 -> "2.0"
        3 -> "2.1"
        6 -> "5.1"
        8 -> "7.1"
        else -> if (count > 0) "$count ch" else ""
    }

    fun codec(mime: String?): String = when (mime?.lowercase(Locale.ROOT)) {
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC3"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/truehd" -> "TrueHD"
        "audio/opus" -> "Opus"
        "audio/mpeg", "audio/mpeg-l2", "audio/mp3" -> "MP3"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> ""
    }

    fun track(language: String?, channelCount: Int, mime: String?): String {
        val parts = ArrayList<String>()
        parts.add(languageName(language))
        val layout = channels(channelCount)
        if (layout.isNotBlank()) parts.add(layout)
        val name = codec(mime)
        if (name.isNotBlank()) parts.add(name)
        return parts.joinToString(" · ")
    }
}
