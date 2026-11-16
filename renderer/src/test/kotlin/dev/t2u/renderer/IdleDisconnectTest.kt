// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// A cast that stays paused for the time the setting names is ended through the receiver; anything but a pause cancels the watch.

import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val THIRTY_MIN = 30 * 60_000L

internal class IdleDisconnectTest {
    private fun withFake(block: suspend (FakeRenderer) -> Unit) {
        val fake = FakeRenderer()
        fake.start()
        try {
            runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    /** The wait of the watch, held until a test lets it out; [waits] are the times it was asked to wait. */
    private class Gate {
        val waits = CopyOnWriteArrayList<Long>()
        @Volatile var open = CompletableDeferred<Unit>()

        suspend fun pause(ms: Long) {
            waits.add(ms)
            open.await()
        }
    }

    @Test
    @DisplayName("a cast paused for the set time is ended through the receiver")
    fun aPausedCastIsEnded() = withFake { fake ->
        val g = Gate()
        RendererHarness(fake, pause = g::pause, idleDisconnectMs = { THIRTY_MIN }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            waitUntil { g.waits.isNotEmpty() }
            assertEquals(listOf(THIRTY_MIN), g.waits)
            assertTrue(h.receiver.endedCasts.isEmpty(), "ended before the time was up")

            g.open.complete(Unit)
            waitUntil { h.receiver.endedCasts.isNotEmpty() }
            assertEquals(listOf("paused for 30 min"), h.receiver.endedCasts.toList())
        }
    }

    @Test
    @DisplayName("with the setting off nothing is watched")
    fun offWatchesNothing() = withFake { fake ->
        val g = Gate()
        RendererHarness(fake, pause = g::pause).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            kotlinx.coroutines.delay(300)
            assertTrue(g.waits.isEmpty())
            assertTrue(h.receiver.endedCasts.isEmpty())
        }
    }

    @Test
    @DisplayName("a resume before the time is up cancels the watch")
    fun resumeCancelsTheWatch() = withFake { fake ->
        val g = Gate()
        RendererHarness(fake, pause = g::pause, idleDisconnectMs = { THIRTY_MIN }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            waitUntil { g.waits.isNotEmpty() }

            assertTrue(r.resume())
            g.open.complete(Unit)
            kotlinx.coroutines.delay(300)
            assertTrue(h.receiver.endedCasts.isEmpty(), "a playing cast was ended")
        }
    }

    @Test
    @DisplayName("a stop before the time is up cancels the watch")
    fun stopCancelsTheWatch() = withFake { fake ->
        val g = Gate()
        RendererHarness(fake, pause = g::pause, idleDisconnectMs = { THIRTY_MIN }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            waitUntil { g.waits.isNotEmpty() }

            assertTrue(r.stop())
            g.open.complete(Unit)
            kotlinx.coroutines.delay(300)
            assertTrue(h.receiver.endedCasts.isEmpty(), "a stopped cast was ended")
        }
    }

    @Test
    @DisplayName("a pause that starts again after a resume gets a fresh watch")
    fun aSecondPauseStartsANewWatch() = withFake { fake ->
        val g = Gate()
        RendererHarness(fake, pause = g::pause, idleDisconnectMs = { THIRTY_MIN }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            waitUntil { g.waits.size == 1 }
            assertTrue(r.resume())
            assertTrue(r.pause())
            waitUntil { g.waits.size == 2 }
            assertEquals(listOf(THIRTY_MIN, THIRTY_MIN), g.waits.toList())
        }
    }
}
