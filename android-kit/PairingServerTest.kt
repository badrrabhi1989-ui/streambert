package com.truelockmc.streambert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL

class PairingServerTest {
    @Test
    fun lanCheckRejectsPublicAddresses() {
        assertTrue(PairingServer.isPrivateLan(InetAddress.getByName("192.168.1.20")))
        assertTrue(PairingServer.isPrivateLan(InetAddress.getByName("10.0.2.15")))
        assertTrue(PairingServer.isPrivateLan(InetAddress.getByName("127.0.0.1")))
        assertFalse(PairingServer.isPrivateLan(InetAddress.getByName("8.8.8.8")))
        assertFalse(PairingServer.isPrivateLan(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun validateRequiresCodeAndAKey() {
        val form = PairingServer.parseForm("code=AB12CD&tmdb=eyJhbGciOiJIUzI1NiJ9.payload.sig&wyzie=&subdl=")
        val keys = PairingServer.validateKeys(form, "AB12CD")
        assertEquals("eyJhbGciOiJIUzI1NiJ9.payload.sig", keys?.get("apikey"))
        assertNull(PairingServer.validateKeys(form, "ZZZZZZ"))
        assertNull(PairingServer.validateKeys(PairingServer.parseForm("code=AB12CD"), "AB12CD"))
        val wyzie = PairingServer.validateKeys(
            PairingServer.parseForm("code=AB12CD&wyzie=wyzie-abc"),
            "AB12CD",
        )
        assertEquals("wyzie-abc", wyzie?.get("wyzieApiKey"))
        assertNull(
            PairingServer.validateKeys(
                PairingServer.parseForm("code=AB12CD&wyzie=not-a-key"),
                "AB12CD",
            ),
        )
    }

    @Test
    fun serverSavesOnceThenCloses() {
        val saved = mutableListOf<Map<String, String>>()
        val session = PairingServer.start("127.0.0.1") { saved.add(it) }
        try {
            val page = http(session, "GET", session.url, null)
            assertTrue(page.contains("TMDB"))
            val body = "code=${session.code}&tmdb=eyJhbGciOiJIUzI1NiJ9.payload.signaturevalue"
            val posted = http(session, "POST", session.url + "#" + session.code, body)
            assertTrue(posted.contains("Saved"))
            assertEquals(1, saved.size)
            assertEquals("eyJhbGciOiJIUzI1NiJ9.payload.signaturevalue", saved[0]["apikey"])
            Thread.sleep(250)
            http(session, "POST", session.url, body)
            assertEquals(1, saved.size)
        } finally {
            PairingServer.stop()
        }
    }

    private fun http(session: PairingServer.Session, method: String, url: String, body: String?): String {
        val clean = url.substringBefore("#")
        val conn = URL(clean).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = false
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
            stream?.bufferedReader()?.readText().orEmpty()
        } catch (e: Exception) {
            e.message ?: ""
        } finally {
            conn.disconnect()
        }
    }
}
