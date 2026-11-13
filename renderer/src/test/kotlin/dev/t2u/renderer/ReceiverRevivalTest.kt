package dev.t2u.renderer

// DISC-05 (plan 05-04): a cast receiver that stopped itself after a lounge failure is given a
// new one by its renderer, 10 s later and then every 5 minutes until it starts. GPL-3.0-only.

import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.ReceiverOptions
import dev.t2u.lounge.Sender
import dev.t2u.media.MediaRegistrar
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReceiverRevivalTest {
    /** A receiver that remembers the callback its owner registered and whether it was started. */
    private class RecordingReceiver(val options: ReceiverOptions, private val failStart: Boolean) : CastReceiver {
        @Volatile override var status: String = "stopped"
        @Volatile var terminate: ((Throwable) -> Unit)? = null
        @Volatile var autoplay: Boolean? = null
        @Volatile var started = false
        @Volatile var stopped = false

        override suspend fun start() {
            if (failStart) throw IOException("dial port busy")
            started = true
            status = "running"
        }

        override suspend fun stop() {
            stopped = true
            status = "stopped"
        }

        override fun connectedSenders(): List<Sender> = emptyList()

        override fun enableAutoplayOnConnect(value: Boolean) {
            autoplay = value
        }

        override fun setOnTerminate(callback: (Throwable) -> Unit) {
            terminate = callback
        }
    }

    private class Rig(fake: FakeRenderer) : AutoCloseable {
        val dataDir: File = Files.createTempDirectory("revival-").toFile().also { it.deleteOnExit() }
        val now = AtomicLong(1_000_000)
        val receivers = CopyOnWriteArrayList<RecordingReceiver>()
        val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

        /** Whether the next receivers created fail to start. */
        @Volatile var failStart = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo = throw IOException("not used")
            override fun invalidate(videoId: String) {}
        }
        private val media = object : MediaRegistrar {
            override fun registerVideo(entry: VideoEntry): String = "/stream/x"
        }

        val renderer = Renderer(
            udn = "fake-renderer",
            location = fake.location,
            address = "127.0.0.1",
            maxAge = 1800,
            port = 3000,
            deps = RendererDeps(
                config = Config(dataDir = dataDir, pollIntervalMs = 50, startTimeoutMs = 200),
                resolver = resolver,
                media = media,
                http = lanHttpClient(),
                createReceiver = ReceiverFactory { _, options ->
                    RecordingReceiver(options, failStart).also { receivers.add(it) }
                },
                scope = scope,
                events = EventSink { kind, fields -> events.add(kind to fields) },
                clock = { now.get() },
                allowLoopbackSpeakers = true,
            ),
        )

        fun notes(prefix: String) =
            events.filter { it.first == "renderer" && (it.second["message"] as? String)?.startsWith(prefix) == true }

        override fun close() {
            runBlocking { renderer.destroy() }
            scope.cancel()
        }
    }

    private fun <T> withRig(block: suspend (Rig) -> T): T {
        val fake = FakeRenderer()
        fake.start()
        try {
            Rig(fake).use { rig -> return runBlocking { block(rig) } }
        } finally {
            fake.close()
        }
    }

    @Test
    @DisplayName("a terminated receiver is replaced 10 s later with the same options and started")
    fun reviveAfterTenSecondsWithSameOptions() = withRig { rig ->
        assertTrue(rig.renderer.init())
        val first = rig.receivers.single()
        val t = rig.now.get()
        first.terminate!!(IOException("lounge down"))

        assertNull(rig.renderer.reviveIfDue(t + 5_000))
        assertEquals(1, rig.receivers.size)
        assertEquals(true, rig.renderer.reviveIfDue(t + 10_000))

        assertEquals(2, rig.receivers.size)
        val second = rig.receivers[1]
        assertEquals(first.options, second.options)
        assertSame(first.options.dataStore, second.options.dataStore)
        assertEquals(3000, second.options.dialPort)
        assertTrue(second.started)
        assertEquals(true, second.autoplay)
        assertEquals(1, rig.notes("Cast receiver stopped itself").size)
        assertEquals(1, rig.notes("Cast receiver \"").filter { (it.second["message"] as String).contains("started again") }.size)
        // revived and running: nothing more is due
        assertNull(rig.renderer.reviveIfDue(t + 20_000))
        assertEquals(2, rig.receivers.size)
    }

    @Test
    @DisplayName("a revival whose start throws is reported false and tried again 5 minutes later")
    fun failedRevivalBacksOffFiveMinutes() = withRig { rig ->
        assertTrue(rig.renderer.init())
        val t = rig.now.get()
        rig.receivers.single().terminate!!(IOException("lounge down"))
        rig.failStart = true

        val failedAt = t + 10_000
        assertEquals(false, rig.renderer.reviveIfDue(failedAt))
        assertEquals(2, rig.receivers.size)
        assertEquals(1, rig.notes("Cast receiver could not start again").size)

        assertNull(rig.renderer.reviveIfDue(failedAt + 4 * 60_000L))
        assertEquals(2, rig.receivers.size)
        assertEquals(false, rig.renderer.reviveIfDue(failedAt + RendererRegistry.RETRY_FAILED_MS))
        assertEquals(3, rig.receivers.size)

        rig.failStart = false
        val third = failedAt + RendererRegistry.RETRY_FAILED_MS
        assertNull(rig.renderer.reviveIfDue(third + 4 * 60_000L))
        assertEquals(true, rig.renderer.reviveIfDue(third + RendererRegistry.RETRY_FAILED_MS))
        assertTrue(rig.receivers.last().started)
    }

    @Test
    @DisplayName("a revived receiver that terminates again is revived again")
    fun revivedReceiverTerminatesAgain() = withRig { rig ->
        assertTrue(rig.renderer.init())
        val t = rig.now.get()
        rig.receivers.single().terminate!!(IOException("one"))
        assertEquals(true, rig.renderer.reviveIfDue(t + 10_000))
        val second = rig.receivers[1]
        assertTrue(second.terminate != null)

        rig.now.set(t + 60_000)
        second.terminate!!(IOException("two"))
        assertNull(rig.renderer.reviveIfDue(t + 65_000))
        assertEquals(true, rig.renderer.reviveIfDue(t + 70_000))
        assertEquals(3, rig.receivers.size)
        assertTrue(rig.receivers[2].started)
    }

    @Test
    @DisplayName("after destroy a terminate and any revival create nothing")
    fun noRevivalAfterDestroy() = withRig { rig ->
        assertTrue(rig.renderer.init())
        val first = rig.receivers.single()
        val t = rig.now.get()
        first.terminate!!(IOException("lounge down"))
        rig.renderer.destroy()

        assertNull(rig.renderer.reviveIfDue(t + 10_000))
        first.terminate!!(IOException("late"))
        assertNull(rig.renderer.reviveIfDue(t + 3_600_000))
        assertEquals(1, rig.receivers.size)
    }

    @Test
    @DisplayName("a renderer whose receiver never terminated has nothing to revive")
    fun nothingToReviveWithoutTerminate() = withRig { rig ->
        assertTrue(rig.renderer.init())
        assertNull(rig.renderer.reviveIfDue(rig.now.get() + 3_600_000))
        assertEquals(1, rig.receivers.size)
    }
}
