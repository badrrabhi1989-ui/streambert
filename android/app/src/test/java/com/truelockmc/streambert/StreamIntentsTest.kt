package com.truelockmc.streambert

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamIntentsTest {
    @Test
    fun hlsUsesMpegUrlThenGenericVideo() {
        val mimes = StreamIntents.mimes("https://cdn.example/master.m3u8?token=1")
        assertEquals("application/x-mpegURL", mimes.first())
        assertEquals(listOf("application/x-mpegURL", "video/*"), mimes)
    }

    @Test
    fun mp4StartsWithGenericVideo() {
        val mimes = StreamIntents.mimes("https://cdn.example/movie.mp4")
        assertEquals("video/*", mimes.first())
        assertEquals(true, mimes.contains("video/mp4"))
    }
}
