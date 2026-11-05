// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// SVC-04: a renderer leaves playback already accepted by the speaker alone across a network loss. It clears the card's local
// playback state, does not re-set or stop the speaker on return, and still tracks its own served addresses.

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
        { Config(dataDir = it, outputFormat = format, pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "") }

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
    @DisplayName("network loss clears the card and leaves accepted playback alone across rebind")
    fun networkLossDetachesCardPlaybackAndLeavesSpeakerAlone() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                val uri = fake.uris().single()

                h.renderer.onNetworkLost()
                waitUntil { h.renderer.ui.value.status == PlayerStatus.IDLE }
                assertEquals(null, h.renderer.ui.value.track)
                assertEquals(null, h.renderer.ui.value.position)
                assertEquals(false, h.renderer.ui.value.queueHasCurrent)
                assertEquals("PLAYING", fake.state.transport)
                assertEquals(uri, fake.state.currentUri)

                // Allow any poll already in flight at the moment of loss to finish before checking for further traffic.
                delay(150)
                val callsAfterLoss = fake.calls.size
                h.renderer.onNetworkChanged("127.0.0.2")
                delay(300)

                assertEquals(callsAfterLoss, fake.calls.size, "the detached renderer is no longer polled or controlled")
                assertEquals(listOf(uri), fake.uris())
                assertEquals(uri, fake.state.currentUri)
                assertEquals("PLAYING", fake.state.transport)
                assertEquals(PlayerStatus.IDLE, h.renderer.ui.value.status)
                assertEquals(null, h.renderer.ui.value.track)
            }
        }
    }
}
