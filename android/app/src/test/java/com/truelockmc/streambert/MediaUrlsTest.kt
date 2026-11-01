package com.truelockmc.streambert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlsTest {
    @Test
    fun prefersMasterPlaylistsOverFiles() {
        assertEquals(100, MediaUrls.rank("https://cdn.example/master.m3u8"))
        assertEquals(80, MediaUrls.rank("https://cdn.example/index.m3u8"))
        assertEquals(50, MediaUrls.rank("https://cdn.example/movie.mp4"))
        assertTrue(MediaUrls.rank("https://cdn.example/master.m3u8") > MediaUrls.rank("https://cdn.example/movie.mp4"))
    }

    @Test
    fun rejectsAdsSegmentsAndBlobs() {
        assertEquals(0, MediaUrls.rank("blob:https://embed.example/123"))
        assertEquals(0, MediaUrls.rank("https://pubads.g.doubleclick.net/vast"))
        assertEquals(0, MediaUrls.rank("https://cdn.example/seg-1.ts"))
        assertEquals(0, MediaUrls.rank("https://cdn.example/thumb.jpg"))
        assertEquals(0, MediaUrls.rank("https://cdn.example/preview.mp4"))
        assertFalse(MediaUrls.isSubtitle("https://pubads.g.doubleclick.net/a.vtt"))
        assertTrue(MediaUrls.isSubtitle("https://cdn.example/subs/en.vtt"))
    }
}
