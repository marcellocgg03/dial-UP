package dev.t2u.lounge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The direction rule of a setVolume request: a button press never moves the speaker the wrong way. */
class VolumeRequestTest {
    @Test
    fun aVolumeDownRequestNeverRaisesTheSpeaker() {
        val directed = directVolume(requested = 60, delta = -5, current = 40)
        assertEquals(40, directed.level)
        assertTrue(directed.conflicted)
    }

    @Test
    fun aVolumeDownRequestBelowTheCurrentLevelIsKept() {
        val directed = directVolume(requested = 30, delta = -5, current = 40)
        assertEquals(30, directed.level)
        assertFalse(directed.conflicted)
    }

    @Test
    fun aVolumeUpRequestNeverLowersTheSpeaker() {
        val directed = directVolume(requested = 20, delta = 5, current = 40)
        assertEquals(40, directed.level)
        assertTrue(directed.conflicted)
    }

    @Test
    fun aVolumeUpRequestAboveTheCurrentLevelIsKept() {
        val directed = directVolume(requested = 55, delta = 5, current = 40)
        assertEquals(55, directed.level)
        assertFalse(directed.conflicted)
    }

    @Test
    fun withoutADeltaTheRequestIsKeptWhicheverWayItGoes() {
        assertEquals(DirectedVolume(10, false), directVolume(requested = 10, delta = null, current = 40))
        assertEquals(DirectedVolume(90, false), directVolume(requested = 90, delta = null, current = 40))
    }

    @Test
    fun aZeroDeltaKeepsTheRequest() {
        assertEquals(DirectedVolume(10, false), directVolume(requested = 10, delta = 0, current = 40))
        assertEquals(DirectedVolume(90, false), directVolume(requested = 90, delta = 0, current = 40))
    }

    @Test
    fun aRequestEqualToTheCurrentLevelIsNoConflict() {
        assertEquals(DirectedVolume(40, false), directVolume(requested = 40, delta = -1, current = 40))
        assertEquals(DirectedVolume(40, false), directVolume(requested = 40, delta = 1, current = 40))
    }
}
