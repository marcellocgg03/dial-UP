package dev.t2u.renderer

// WIFI-02 (plan 07.1-02): the decision table of resyncAction. GPL-3.0-only.

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ResyncTest {
    private fun act(
        state: String?,
        uri: UriKind,
        ipChanged: Boolean = false,
        attempt: Int = 1,
        holds: Boolean = true,
    ) = resyncAction(holds, ipChanged, state, uri, attempt)

    @Test
    @DisplayName("a renderer that holds no track does nothing, whatever the speaker says")
    fun noTrack() {
        for (state in listOf("PLAYING", "STOPPED", null, "TRANSITIONING")) {
            for (uri in UriKind.entries) {
                assertEquals(ResyncAction.NOTHING, act(state, uri, holds = false))
                assertEquals(ResyncAction.NOTHING, act(state, uri, ipChanged = true, holds = false))
            }
        }
    }

    @Test
    @DisplayName("a speaker still playing or paused on our stream is kept on the same address and re-set at its own position after a move")
    fun ours() {
        for (state in listOf("PLAYING", "PAUSED_PLAYBACK")) {
            assertEquals(ResyncAction.KEEP, act(state, UriKind.OURS, ipChanged = false))
            assertEquals(ResyncAction.RESET_AT_SPEAKER, act(state, UriKind.OURS, ipChanged = true))
        }
    }

    @Test
    @DisplayName("a stopped speaker is re-set at the last polled position, never at its own 0:00")
    fun stopped() {
        for (state in listOf("STOPPED", "NO_MEDIA_PRESENT")) {
            for (uri in listOf(UriKind.OURS, UriKind.EMPTY, UriKind.UNREAD)) {
                assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act(state, uri, ipChanged = false))
                assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act(state, uri, ipChanged = true))
            }
        }
    }

    @Test
    @DisplayName("a playing speaker with no readable or no TrackURI is re-set at the last polled position")
    fun playingWithoutUri() {
        for (uri in listOf(UriKind.EMPTY, UriKind.UNREAD)) {
            assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act("PLAYING", uri))
            assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act("PLAYING", uri, ipChanged = true))
        }
    }

    @Test
    @DisplayName("a foreign stream releases the speaker in every readable state")
    fun foreign() {
        for (state in listOf("PLAYING", "PAUSED_PLAYBACK", "STOPPED", "TRANSITIONING")) {
            assertEquals(ResyncAction.RELEASE_FOREIGN, act(state, UriKind.FOREIGN))
            assertEquals(ResyncAction.RELEASE_FOREIGN, act(state, UriKind.FOREIGN, ipChanged = true))
        }
    }

    @Test
    @DisplayName("an unreadable or transitioning speaker is read again twice, then re-set at the last polled position")
    fun retries() {
        for (state in listOf<String?>(null, "TRANSITIONING")) {
            for (uri in listOf(UriKind.OURS, UriKind.EMPTY, UriKind.UNREAD)) {
                assertEquals(ResyncAction.RETRY, act(state, uri, attempt = 1))
                assertEquals(ResyncAction.RETRY, act(state, uri, attempt = 2))
                assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act(state, uri, attempt = 3))
            }
        }
        assertEquals(RESYNC_READ_ATTEMPTS, 3)
    }

    @Test
    @DisplayName("an unknown state word is re-set at the last polled position")
    fun unknown() {
        assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act("PAUSED_RECORDING", UriKind.OURS))
        assertEquals(ResyncAction.RESET_AT_LAST_POLLED, act("PAUSED_RECORDING", UriKind.OURS, ipChanged = true))
    }
}
