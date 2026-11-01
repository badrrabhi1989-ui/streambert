package com.truelockmc.streambert

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPolicyTest {
    @Test
    fun prefersAacStereoOverSurround() {
        val index = AudioPolicy.index(
            listOf(
                AudioPolicy.Track(MimeTypes.AUDIO_E_AC3, 6, "en", true),
                AudioPolicy.Track(MimeTypes.AUDIO_AAC, 2, "en", true),
            ),
            "en",
        )
        assertEquals(1, index)
    }

    @Test
    fun keepsSurroundWhenItIsTheOnlyTrack() {
        val index = AudioPolicy.index(
            listOf(AudioPolicy.Track(MimeTypes.AUDIO_AC3, 6, "en", true)),
            "en",
        )
        assertEquals(0, index)
    }

    @Test
    fun prefersTheSavedLanguage() {
        val index = AudioPolicy.index(
            listOf(
                AudioPolicy.Track(MimeTypes.AUDIO_AAC, 2, "fr", true),
                AudioPolicy.Track(MimeTypes.AUDIO_AAC, 2, "en", true),
            ),
            "en",
        )
        assertEquals(1, index)
    }
}
