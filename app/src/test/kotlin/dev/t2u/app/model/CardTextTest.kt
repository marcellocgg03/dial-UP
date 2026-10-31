// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.NowPlaying
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CardTextTest {
    private fun track(title: String = "Kiss on My List", artist: String = "Daryl Hall & John Oates") =
        NowPlaying("vid123", title, artist, 200.0, "adts", "https://i.ytimg.com/vi/vid123/hq.jpg")

    @Test
    fun `status words for an online speaker`() {
        assertEquals("Playing", statusLabel(PlayerStatus.PLAYING, true))
        assertEquals("Paused", statusLabel(PlayerStatus.PAUSED, true))
        assertEquals("Loading", statusLabel(PlayerStatus.LOADING, true))
        assertEquals("Stopped", statusLabel(PlayerStatus.STOPPED, true))
        assertEquals("Idle", statusLabel(PlayerStatus.IDLE, true))
    }

    @Test
    fun `an offline speaker is Offline whatever its status`() {
        PlayerStatus.entries.forEach { assertEquals("Offline", statusLabel(it, false)) }
    }

    @Test
    fun `track lines are title and artist`() {
        assertEquals("Kiss on My List" to "Daryl Hall & John Oates", trackLines(track()))
    }

    @Test
    fun `accented characters stay intact`() {
        assertEquals("Été à Montréal" to "Étienne Daho", trackLines(track("Été à Montréal", "Étienne Daho")))
    }

    @Test
    fun `a blank artist gives no second line`() {
        assertEquals("Kiss on My List" to null, trackLines(track(artist = "  ")))
    }

    @Test
    fun `a blank title falls back to the video id`() {
        assertEquals("vid123" to "Daryl Hall & John Oates", trackLines(track(title = "")))
    }
}
