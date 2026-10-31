// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.NowPlaying
import dev.t2u.renderer.SpeakerPosition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private val pos = SpeakerPosition(10.0, 200.0, atMs = 1_000)

    @Test
    fun `position extrapolates while playing`() {
        assertEquals(15.0, positionAt(pos, nowMs = 6_000, playing = true))
    }

    @Test
    fun `position holds when not playing`() {
        assertEquals(10.0, positionAt(pos, nowMs = 6_000, playing = false))
    }

    @Test
    fun `position clamps to the duration`() {
        assertEquals(200.0, positionAt(pos, nowMs = 10_000_000, playing = true))
    }

    @Test
    fun `a zero duration never clamps`() {
        assertEquals(15.0, positionAt(SpeakerPosition(10.0, 0.0, 1_000), nowMs = 6_000, playing = true))
    }

    @Test
    fun `a clock before the report gives the reported position`() {
        assertEquals(10.0, positionAt(pos, nowMs = 500, playing = true))
    }

    @Test
    fun `progress text with a duration`() {
        assertEquals("1:05 / 3:42", progressText(65.0, 222.0))
    }

    @Test
    fun `progress text over an hour`() {
        assertEquals("1:02:03 / 1:06:40", progressText(3723.0, 4000.0))
    }

    @Test
    fun `progress text without a duration`() {
        assertEquals("1:05", progressText(65.0, 0.0))
    }

    @Test
    fun `progress fraction`() {
        assertEquals(0.25f, progressFraction(50.0, 200.0))
    }

    @Test
    fun `progress fraction needs a duration`() {
        assertNull(progressFraction(50.0, 0.0))
    }

    @Test
    fun `progress fraction clamps`() {
        assertEquals(1f, progressFraction(300.0, 200.0))
        assertEquals(0f, progressFraction(-5.0, 200.0))
    }
}
