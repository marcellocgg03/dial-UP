package dev.t2u.renderer

// SVC-04 and D-07 (plan 05-08): a renderer follows the phone's move to another address. It builds stream URLs with the new
// address, still counts a URL with one of its last own addresses as its own, keeps quiet while the network is down and
// re-sets the loaded track at the speaker's position. Driven against the upnp FakeRenderer through RendererHarness.
// GPL-3.0-only.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
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

    private fun pinned(format: String, prefetch: Boolean = false): (File) -> Config =
        { Config(dataDir = it, outputFormat = format, pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "", prefetch = prefetch) }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    private fun FakeRenderer.seekTargets(): List<String> =
        calls.filter { it.action == "Seek" }.map { Regex("<Target>(.*?)</Target>").find(it.body)?.groupValues?.get(1) ?: "" }

    /** The CurrentURI of every SetAVTransportURI the speaker received, in order. */
    private fun FakeRenderer.uris(): List<String> =
        calls.filter { it.action == "SetAVTransportURI" }.map { Regex("<CurrentURI>([^<]*)</CurrentURI>").find(it.body)?.groupValues?.get(1).orEmpty() }

    private fun hostOf(uri: String): String = URI(uri).host

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
    @DisplayName("a speaker that still reports a URL with an old own address is not released as another source")
    fun anOldOwnAddressIsStillOurs() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                val old = fake.uris().first()
                assertEquals("127.0.0.1", hostOf(old))
                fake.state.reportedUri = old

                h.renderer.onNetworkLost()
                h.renderer.onNetworkChanged("127.0.0.2")
                delay(600) // twelve poll intervals, six times the foreign-poll debounce

                assertTrue(h.notes("the speaker was taken over").isEmpty(), "not released as another source")
                assertEquals(PlayerStatus.PLAYING, h.renderer.status)
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
}
