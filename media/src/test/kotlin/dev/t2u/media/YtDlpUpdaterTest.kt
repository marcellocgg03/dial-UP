package dev.t2u.media

// The background yt-dlp update schedule (PLAY-02, quick 261028-ago). GPL-3.0-only.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpUpdaterTest {
    private class FakeGateway : YtDlpGateway {
        val updates = CopyOnWriteArrayList<String>()
        val updateThreads = CopyOnWriteArrayList<String>()
        @Volatile var failure: ((Int) -> Throwable?) = { null }
        @Volatile var versionFailure: Throwable? = null
        @Volatile var current = "2026.01.01"

        override fun run(call: YtDlpCall): GatewayResult = error("not used")

        override fun version(): String? {
            versionFailure?.let { throw it }
            return current
        }

        override fun update(channel: String): String {
            updateThreads += Thread.currentThread().name
            updates += channel
            failure(updates.size)?.let { throw it }
            current = "2026.02.02"
            return "DONE"
        }
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "yt-dlp-updater-test") }
    private val dispatcher = executor.asCoroutineDispatcher()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Records the waits and cancels the loop once [stopAfter] pauses were requested. */
    private class Pauses(private val stopAfter: Int) {
        val waits = CopyOnWriteArrayList<Long>()
        val pause: suspend (Long) -> Unit = {
            waits += it
            if (waits.size >= stopAfter) throw CancellationException("test over")
        }
    }

    private fun updater(
        gateway: FakeGateway,
        pauses: Pauses,
        events: MutableList<Map<String, Any?>> = CopyOnWriteArrayList(),
        channel: () -> String = { "stable" },
        onEvent: (Map<String, Any?>) -> Unit = { events += it },
    ) = YtDlpUpdater(gateway, channel, onEvent, pause = pauses.pause, dispatcher = dispatcher)

    private fun runLoop(u: YtDlpUpdater) = runBlocking {
        try {
            u.run()
        } catch (_: CancellationException) {
            // the test's pause ended it
        }
    }

    @Test
    fun theFirstWaitIsTheStartDelayThenTheDailyOne() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 3)
        runLoop(updater(g, pauses, events))
        assertEquals(listOf(30_000L, 24L * 3600 * 1000, 24L * 3600 * 1000), pauses.waits.toList())
        assertEquals(listOf("stable", "stable"), g.updates.toList())
        assertEquals(listOf("start", "scheduled"), events.map { it["trigger"] })
        val first = events.first()
        assertEquals(true, first["ok"])
        assertEquals("2026.01.01", first["before"])
        assertEquals("2026.02.02", first["after"])
        assertEquals("DONE", first["status"])
        assertEquals("stable", first["channel"])
        assertTrue(first["ms"] is Long)
        assertNull(first["error"])
    }

    @Test
    fun aFailureKeepsTheSignedUrlOutAndRetriesAfterAnHourThenGoesBackToDaily() {
        val g = FakeGateway()
        g.failure = { n ->
            if (n == 1) {
                java.io.IOException("download of https://github.com/yt-dlp/releases/download/x/yt-dlp?token=SECRETSECRET failed " + "x".repeat(500))
            } else {
                null
            }
        }
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 3)
        runLoop(updater(g, pauses, events))
        assertEquals(listOf(30_000L, 3600_000L, 24L * 3600 * 1000), pauses.waits.toList())
        val failed = events.first()
        assertEquals(false, failed["ok"])
        assertEquals("FAILED", failed["status"])
        val error = failed["error"] as String
        assertTrue(error.contains("github.com"), error)
        assertFalse(error.contains("SECRET"), error)
        assertFalse(error.contains("/releases/"), error)
        assertTrue(error.length <= 300)
        assertEquals(true, events[1]["ok"])
    }

    @Test
    fun theChannelIsReadBeforeEveryAttemptAndAnInvalidOneFallsBackToStable() {
        val g = FakeGateway()
        val values = ArrayDeque(listOf("stable", "nightly", "bogus", "THROW"))
        val pauses = Pauses(stopAfter = 5)
        runLoop(
            updater(g, pauses, channel = {
                val v = values.removeFirst()
                if (v == "THROW") throw IllegalStateException("no config") else v
            }),
        )
        assertEquals(listOf("stable", "nightly", "stable", "stable"), g.updates.toList())
    }

    @Test
    fun theGatewayRunsOnTheInjectedDispatcher() {
        val g = FakeGateway()
        runLoop(updater(g, Pauses(stopAfter = 2)))
        // the coroutine debug agent may append " @coroutine#N" to the thread name
        assertEquals(1, g.updateThreads.size)
        assertTrue(g.updateThreads.single().startsWith("yt-dlp-updater-test"), g.updateThreads.toString())
    }

    @Test
    fun cancellingWhileItWaitsEndsItWithoutAnUpdate() {
        val g = FakeGateway()
        runLoop(updater(g, Pauses(stopAfter = 1)))
        assertTrue(g.updates.isEmpty())
    }

    @Test
    fun aThrowingSinkAndAThrowingVersionDoNotStopTheLoop() {
        val g = FakeGateway()
        g.versionFailure = IllegalStateException("no python yet")
        val seen = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 3)
        runLoop(
            updater(g, pauses, onEvent = {
                seen += it
                throw IllegalStateException("sink broke")
            }),
        )
        assertEquals(2, g.updates.size)
        assertNull(seen.first()["before"])
        assertEquals(true, seen.first()["ok"])
    }
}
