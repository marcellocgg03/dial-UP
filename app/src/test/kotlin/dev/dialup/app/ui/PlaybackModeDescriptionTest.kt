// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackModeDescriptionTest {
    @Test
    fun `each selected mode has its own description`() {
        assertEquals(
            "Starts quickly and makes it easier to skip to another part of a track. Some tracks or speakers may use more battery.",
            playbackModeDescription("streaming"),
        )
        assertEquals(
            "Plays the original audio with minimal processing. Some speakers may not support it, and skipping within long tracks can be very slow.",
            playbackModeDescription("direct"),
        )
        assertEquals(
            "Prepares a file before playing, making it easier for your speaker to skip within a track. Starting can take longer and uses phone storage.",
            playbackModeDescription("mp4"),
        )
        assertNull(playbackModeDescription("unknown"))
    }
}
