package com.truelockmc.streambert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-pad traversal of the TV settings column. Rects match scripts/verify-tv-focus.mjs.
 * Key codes are KEYCODE_DPAD_* / KEYCODE_ENTER / KEYCODE_DPAD_CENTER.
 */
class TvFocusTest {
    private val rail = TvFocus.Rect(40f, 500f, 240f, 564f)
    private val input = TvFocus.Rect(400f, 160f, 1120f, 224f)
    private val save = TvFocus.Rect(400f, 248f, 640f, 312f)
    private val pairing = TvFocus.Rect(400f, 400f, 760f, 464f)
    private val rects = listOf(rail, input, save, pairing)

    @Test
    fun rightFromTheRailLandsOnPairing() {
        val next = TvFocus.pick(rects, 0, TvFocus.directionOf(22)!!)
        assertEquals(3, next)
    }

    @Test
    fun downFromTheTokenFieldReachesPairing() {
        val saveIndex = TvFocus.pick(rects, 1, TvFocus.directionOf(20)!!)
        assertEquals(2, saveIndex)
        val pairingIndex = TvFocus.pick(rects, saveIndex, "down")
        assertEquals(3, pairingIndex)
    }

    @Test
    fun leftReturnsToTheRailAndCenterActivates() {
        assertEquals(2, TvFocus.pick(rects, 3, TvFocus.directionOf(21)!!))
        assertEquals(0, TvFocus.pick(rects, 2, "left"))
        assertTrue(TvFocus.isActivate(23))
        assertTrue(TvFocus.isActivate(66))
        assertFalse(TvFocus.isActivate(20))
    }
}
