package com.truelockmc.streambert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistTest {
    @Test
    fun parsesMasterAndPicksHighestBandwidth() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=8000,RESOLUTION=1920x1080
            high.m3u8
        """.trimIndent()
        val pl = HlsPlaylist.parse(text, "https://cdn.example/master.m3u8")
        assertTrue(pl.isMaster)
        assertEquals("https://cdn.example/high.m3u8", HlsPlaylist.bestVariant(pl))
    }

    @Test
    fun parsesMediaSegmentsAndInitMap() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:4
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k",IV=0x00112233445566778899aabbccddeeff
            #EXTINF:6.0,
            seg.ts
        """.trimIndent()
        val pl = HlsPlaylist.parse(text, "https://cdn.example/v/playlist.m3u8")
        assertFalse(pl.isMaster)
        assertEquals(1, pl.segments.size)
        assertEquals("https://cdn.example/v/seg.ts", pl.segments[0].url)
        assertEquals(4, pl.segments[0].sequence)
        assertEquals("https://cdn.example/v/init.mp4", pl.initUrl)
        assertTrue(pl.isFmp4)
        assertEquals("AES-128", pl.key?.method)
        assertEquals("https://keys.example/k", pl.key?.uri)
    }

    @Test
    fun rewriteProxiesUrls() {
        val text = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nseg.ts\n"
        val out = HlsPlaylist.rewrite(text, "https://cdn.example/a/p.m3u8", "tok")
        assertTrue(out.contains("https://player.streambert.local/proxy?t=tok&url="))
        assertTrue(out.contains("init.mp4"))
        assertTrue(out.contains("seg.ts"))
    }
}

class BlocklistTest {
    @Test
    fun matchesExactAndWildcard() {
        val patterns = listOf("*://doubleclick.net/*", "*://*.doubleclick.net/*", "*://ads.example.com/*")
        assertTrue(Blocklist.matches("doubleclick.net", patterns))
        assertTrue(Blocklist.matches("foo.doubleclick.net", patterns))
        assertTrue(Blocklist.matches("ads.example.com", patterns))
        assertFalse(Blocklist.matches("image.tmdb.org", patterns))
    }
}

class NetGuardTest {
    @Test
    fun rejectsLoopbackAndAcceptsPublicIp() {
        assertFalse(NetGuard.isPublicHttpUrl("http://127.0.0.1/x"))
        assertFalse(NetGuard.isPublicHttpUrl("http://10.1.2.3/x"))
        assertFalse(NetGuard.isPublicHttpUrl("file:///etc/passwd"))
        assertTrue(NetGuard.isPublicHttpUrl("https://1.1.1.1/a"))
    }
}
