package dev.t2u.renderer

// Port of tube2upnp test/unit.test.js "time helpers", GPL-3.0-only.

import dev.t2u.media.mimeFor
import dev.t2u.media.urlExpiry
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TimeHelpersTest {
    @Test
    @DisplayName("time helpers")
    fun timeHelpers() {
        assertEquals(65.0, parseHms("0:01:05"))
        assertEquals(3600.5, parseHms("01:00:00.500"))
        assertEquals(0.0, parseHms("NOT_IMPLEMENTED"))
        val now = 1_700_000_000_000L
        assertEquals(now + 300_000, urlExpiry("https://r1.googlevideo.com/v?expire=${now / 1000 + 600}", now))
        assertEquals("audio/mp4", mimeFor("m4a", null))
    }

    @Test
    @DisplayName("parseHms ignores junk and seekHms pads and floors")
    fun junkAndSeekFormat() {
        assertEquals(0.0, parseHms(null))
        assertEquals(0.0, parseHms(""))
        assertEquals(0.0, parseHms("1:xx:05"))
        assertEquals(0.0, parseHms("NaN"))
        assertEquals("00:00:00", seekHms(-5.0))
        assertEquals("01:23:45", seekHms(5025.9))
    }
}
