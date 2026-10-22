package dev.t2u.renderer

// Reusable fake-speaker rig for the renderer tests of Phase 3 (plans 03-06, 03-08, 03-09), GPL-3.0-only.
// RendererTest keeps its own private Harness; this one is for tests that need the media seam to be
// observable (what was prepared, what was registered, in what order) and failable.

import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.ReceiverOptions
import dev.t2u.lounge.Sender
import dev.t2u.media.MediaRegistrar
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.VideoResolver
import dev.t2u.media.extensionOf
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

internal class HarnessReceiver : CastReceiver {
    @Volatile override var status: String = "stopped"

    @Volatile var autoplayOnConnect: Boolean? = null

    override suspend fun start() {
        status = "running"
    }

    override suspend fun stop() {
        status = "stopped"
    }

    override fun connectedSenders(): List<Sender> = emptyList()

    override fun enableAutoplayOnConnect(value: Boolean) {
        autoplayOnConnect = value
    }
}

/**
 * One [Renderer] wired to a [FakeRenderer], a [HarnessReceiver], a stub resolver and a recording media
 * registrar. [prepareFailure] may return a throwable for an entry about to be prepared; [prepareDelayMs]
 * makes every prepare slow; [directDelegate] serves `direct` entries from a real media server; [duration] is the
 * length the stub resolver reports (0 means unknown); [clock] is the renderer's epoch-millisecond clock (a test can
 * move it by hand, see SeekPositionTest).
 * [close] destroys the renderer.
 */
internal class RendererHarness(
    val fake: FakeRenderer,
    config: (File) -> Config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "") },
    val dataDir: File = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() },
    private val prepareDelayMs: Long = 0,
    @Volatile var prepareFailure: (VideoEntry) -> Throwable? = { null },
    private val directDelegate: MediaRegistrar? = null,
    playlistRequestHandlerFactory: (() -> PlaylistRequestHandler)? = null,
    private val duration: Double = 210.0,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    val registered = CopyOnWriteArrayList<VideoEntry>()
    val prepared = CopyOnWriteArrayList<VideoEntry>()
    val resolves = CopyOnWriteArrayList<String>()

    /** The stream paths the renderer told the media seam it no longer needs (plan 03-20). */
    val released = CopyOnWriteArrayList<String>()

    /** "prepare:<format>" and "register:<format>" in the order the media seam saw them. */
    val order = CopyOnWriteArrayList<String>()
    val receiver = HarnessReceiver()
    val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    @Volatile var receiverCreated = 0
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val resolver = object : VideoResolver {
        override suspend fun resolve(videoId: String): ResolvedInfo {
            resolves.add(videoId)
            return ResolvedInfo(
                url = "https://example/v",
                ext = "m4a",
                mime = "audio/mp4",
                headers = emptyMap(),
                title = "Song $videoId",
                artist = "Band",
                album = "",
                thumbnail = "https://i/x.jpg",
                duration = duration,
                filesize = 0,
                formatId = null,
            )
        }

        override fun invalidate(videoId: String) {}
    }

    private val media = object : MediaRegistrar {
        override suspend fun prepare(entry: VideoEntry) {
            order.add("prepare:${entry.format}")
            prepared.add(entry)
            if (prepareDelayMs > 0) delay(prepareDelayMs)
            prepareFailure(entry)?.let { throw it }
            directDelegate?.takeIf { entry.format == "direct" }?.prepare(entry)
        }

        override fun release(path: String) {
            released.add(path)
        }

        override fun registerVideo(entry: VideoEntry): String {
            order.add("register:${entry.format}")
            registered.add(entry)
            if (entry.format == "direct" && directDelegate != null) return directDelegate.registerVideo(entry)
            val ext = if (entry.format == "direct") checkNotNull(entry.ext) else extensionOf(entry.format)
            return "/stream/" + registered.size.toString().padStart(32, '0') + "." + ext
        }
    }

    val renderer = Renderer(
        udn = "fake-renderer",
        location = fake.location,
        address = "127.0.0.1",
        maxAge = 1800,
        port = 3000,
        deps = RendererDeps(
            config = config(dataDir),
            resolver = resolver,
            media = media,
            http = lanHttpClient(),
            createReceiver = ReceiverFactory { _, _: ReceiverOptions ->
                receiverCreated++
                receiver
            },
            scope = scope,
            events = EventSink { kind, fields -> events.add(kind to fields) },
            playlistRequestHandlerFactory = playlistRequestHandlerFactory,
            clock = clock,
            pause = pause,
        ),
    )

    /** The renderer events whose message starts with [prefix]. */
    fun notes(prefix: String): List<Map<String, Any?>> =
        events.filter { it.first == "renderer" && (it.second["message"] as? String)?.startsWith(prefix) == true }.map { it.second }

    /** The formats registered so far, in order. */
    fun formats(): List<String> = registered.map { it.format }

    override fun close() {
        runBlocking { renderer.destroy() }
        scope.cancel()
    }
}

/** Polls [cond] until it holds or [timeoutMs] passes. */
internal suspend fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond() && System.currentTimeMillis() < end) delay(20)
}
