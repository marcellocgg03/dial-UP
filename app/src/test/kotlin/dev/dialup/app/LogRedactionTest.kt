package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogRedactionTest {
    private val pathId = "0123456789abcdef0123456789abcdef"

    @Test
    fun aStreamTokenIsCutToSixCharactersAndTheRestOfThePathStays() {
        assertEquals("/stream/012345...mp3", redactStreamToken("/stream/$pathId.mp3"))
        assertEquals("/stream/012345...adts?x=1", redactStreamToken("/stream/$pathId.adts?x=1"))
        assertFalse(pathId.substring(6) in redactStreamToken("/stream/$pathId.mp3"))
    }

    @Test
    fun otherPathsAreUntouched() {
        for (uri in listOf("/ytcr/apps/YouTube", "/stream/short.mp3", "/", "/other/$pathId", "")) {
            assertEquals(uri, redactStreamToken(uri), uri)
        }
    }

    @Test
    fun aSourceGetsOneLineEachWindowWithTheCountOfTheOnesHeldBack() {
        var now = 0L
        val l = SourceLogLimiter(1_000L, 100) { now }
        assertEquals(0, l.admit("a"))
        assertNull(l.admit("a"))
        assertNull(l.admit("a"))
        assertEquals(0, l.admit("b"), "another source has its own window")
        now = 999L
        assertNull(l.admit("a"))
        now = 1_000L
        assertEquals(3, l.admit("a"), "three were held back")
        assertNull(l.admit("a"))
    }

    @Test
    fun aFloodOfSourcesStaysBoundedAndKeepsTheRecentOnesLimited() {
        var now = 0L
        val l = SourceLogLimiter(1_000_000L, 4) { now }
        for (i in 0 until 4) {
            now += 10
            l.admit("s$i")
        }
        now += 10
        l.admit("s4") // evicts s0, the oldest
        assertNull(l.admit("s3"), "a recent source is still limited")
        assertEquals(0, l.admit("s0"), "the evicted one starts over")
    }

    @Test
    fun onlyA404WithNoLabelAndNoNoteIsAnUnregisteredTokenMiss() {
        assertTrue(isUnregisteredTokenMiss(404, null, null))
        assertFalse(isUnregisteredTokenMiss(404, "Song", null))
        assertFalse(isUnregisteredTokenMiss(404, null, "file vanished"))
        assertFalse(isUnregisteredTokenMiss(200, null, null))
        assertFalse(isUnregisteredTokenMiss(null, null, null))
    }
}
