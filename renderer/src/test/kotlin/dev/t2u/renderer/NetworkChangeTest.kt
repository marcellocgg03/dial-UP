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
    @DisplayName("a short network loss preserves card state and resyncs playback after rebind")
    fun networkLossRecoversPlaybackAfterRebind() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                val uri = fake.uris().single()

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
}
