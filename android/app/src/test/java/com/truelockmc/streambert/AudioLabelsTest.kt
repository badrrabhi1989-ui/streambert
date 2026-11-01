package com.truelockmc.streambert

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLabelsTest {
    @Test
    fun languageNames() {
        assertEquals("English", AudioLabels.languageName("en"))
        assertEquals("Arabic", AudioLabels.languageName("ar"))
        assertEquals("French", AudioLabels.languageName("fr"))
        assertEquals("English", AudioLabels.languageName("eng"))
        assertEquals("Original", AudioLabels.languageName("und"))
        assertEquals("Original", AudioLabels.languageName(null))
    }

    @Test
    fun channelLayoutAndCodec() {
        assertEquals("2.0", AudioLabels.channels(2))
        assertEquals("5.1", AudioLabels.channels(6))
        assertEquals("E-AC3", AudioLabels.codec("audio/eac3"))
        assertEquals("AAC", AudioLabels.codec("audio/mp4a-latm"))
        assertEquals("English · 5.1 · E-AC3", AudioLabels.track("en", 6, "audio/eac3"))
        assertEquals("Arabic · 2.0 · AAC", AudioLabels.track("ar", 2, "audio/mp4a-latm"))
        assertEquals("French · 5.1 · AC3", AudioLabels.track("fra", 6, "audio/ac3"))
    }
}
