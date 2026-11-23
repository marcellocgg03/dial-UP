// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// SVC-04: short network blips pause polling, then recover and resync the active renderer without losing its local queue.

import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"

internal class NetworkChangeTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun pinned(format: String): (File) -> Config =
        { Config(dataDir = it, outputFormat = format, pollIntervalMs = 50, startTimeoutMs = 2000) }

    private fun FakeRenderer.uris(): List<String> =
        calls.filter { it.action == "SetAVTransportURI" }
            .map { Regex("<CurrentURI>([^<]*)</CurrentURI>").find(it.body)?.groupValues?.get(1).orEmpty() }

    @Test
    @DisplayName("a renderer remembers at most its last 4 own addresses, newest last, and never lists the current one")
    fun ownHostsAreBounded() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                for (host in listOf("127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5", "127.0.0.6", "127.0.0.6")) {
                    h.renderer.onNetworkChanged(host)
                }
                assertEquals(listOf("127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5"), h.renderer.ownHosts)
            }
        }
    }

    @Test
    @DisplayName("a URL with an address the renderer never served from is still another source")
    fun aStrangersAddressIsStillForeign() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                h.renderer.onNetworkChanged("127.0.0.2")
                fake.state.reportedUri = fake.uris().first().replace("127.0.0.1", "127.0.0.9")

                waitUntil { h.notes("the speaker was taken over").isNotEmpty() }
                assertEquals(1, h.notes("the speaker was taken over").size)
            }
        }
    }

    @Test
    @DisplayName("a short network loss preserves card state and resyncs playback after rebind")
    fun networkLossRecoversPlaybackAfterRebind() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                val uri = fake.uris().single()
                // The card shows loading until the speaker plays, then playing.
                waitUntil { h.renderer.ui.value.status == PlayerStatus.PLAYING }

                h.renderer.onNetworkLost()
                assertEquals(PlayerStatus.PLAYING, h.renderer.ui.value.status)
                assertEquals(A, h.renderer.ui.value.track?.videoId)
                assertEquals(true, h.renderer.ui.value.queueHasCurrent)
                assertEquals("PLAYING", fake.state.transport)
                assertEquals(uri, fake.state.currentUri)
                val playsBeforeRebind = fake.calls.count { it.action == "Play" }

                delay(150)
                val pollsDuringLoss = fake.calls.count { it.action == "GetTransportInfo" }
                delay(200)
                assertEquals(pollsDuringLoss, fake.calls.count { it.action == "GetTransportInfo" }, "polling pauses during the outage")

                h.renderer.onNetworkChanged("127.0.0.2")
                waitUntil {
                    fake.uris().size > 1 &&
                        fake.calls.count { it.action == "Play" } > playsBeforeRebind &&
                        fake.state.transport == "PLAYING" &&
                        fake.calls.count { it.action == "GetTransportInfo" } > pollsDuringLoss
                }

                assertEquals("127.0.0.2", java.net.URI(fake.uris().last()).host)
                assertEquals("PLAYING", fake.state.transport)
                assertEquals(PlayerStatus.PLAYING, h.renderer.ui.value.status)
                assertEquals(A, h.renderer.ui.value.track?.videoId)
                assertEquals(true, h.renderer.ui.value.queueHasCurrent)
            }
        }
    }

    /** A track the poll has read at 40 s; the speaker then answers [unreadable] as its position and the phone's address changes. */
    private fun resetAfterAnUnreadablePosition(unreadable: String) {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                fake.state.relTime = "0:00:40"
                assertTrue(h.renderer.play(Video(A), 0.0))
                waitUntil { h.renderer.ui.value.status == PlayerStatus.PLAYING }
                delay(300) // several polls have read 40 s

                fake.state.relTime = unreadable
                h.renderer.onNetworkChanged("127.0.0.2")
                waitUntil { h.registered.size > 1 && h.notes("Re-set ").isNotEmpty() }

                assertEquals(2, h.registered.size, "the track was re-set once")
                assertEquals(A, h.registered.last().videoId)
                assertEquals(40, h.registered.last().startAt, "at the last polled position, not at 0:00")
                assertEquals(1, h.notes("Speaker position unreadable after the address change").size)
                assertEquals("last-polled", h.notes("Re-set ").single()["why"])
            }
        }
    }

    @Test
    @DisplayName("a speaker that answers NOT_IMPLEMENTED as its position after an address change is re-set at the last polled position")
    fun anUnreadableSpeakerPositionResetsAtTheLastPolledPosition() {
        resetAfterAnUnreadablePosition("NOT_IMPLEMENTED")
    }

    @Test
    @DisplayName("a speaker that answers an empty position after an address change is re-set at the last polled position")
    fun anEmptySpeakerPositionResetsAtTheLastPolledPosition() {
        resetAfterAnUnreadablePosition("")
    }

    @Test
    @DisplayName("a readable speaker position after an address change is still the position the track is re-set at")
    fun aReadableSpeakerPositionStillWins() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                fake.state.relTime = "0:00:40"
                assertTrue(h.renderer.play(Video(A), 0.0))
                waitUntil { h.renderer.ui.value.status == PlayerStatus.PLAYING }
                delay(300)

                fake.state.relTime = "0:01:30"
                h.renderer.onNetworkChanged("127.0.0.2")
                waitUntil { h.registered.size > 1 && h.notes("Re-set ").isNotEmpty() }

                assertEquals(90, h.registered.last().startAt, "the speaker's own position")
                assertTrue(h.notes("Speaker position unreadable").isEmpty())
                assertEquals("speaker", h.notes("Re-set ").single()["why"])
            }
        }
    }
}
