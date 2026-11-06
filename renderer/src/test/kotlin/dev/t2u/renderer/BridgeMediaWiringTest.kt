package dev.t2u.renderer

// Whole-bridge media wiring (plan 03-10; PLAY-04, SVC-07): the bridge hands the media server its upstream fetcher, its
// file builder and the config knobs, so every format of the chain is reachable from a speaker. The "upstream" is a JDK
// HttpServer on loopback; the file formats use the real ffmpeg when the machine has one (assumeTrue otherwise).
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.media.JvmPipeLauncher
import dev.t2u.media.SourceDownloader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

/** PLAY-04: all formats of the chain are reachable through the running bridge; production proxies googlevideo only. */
class BridgeMediaWiringTest {
    private var h = BridgeHarness()
    private val tmp: File = Files.createTempDirectory("bridge-media-").toFile().also { it.deleteOnExit() }

    @AfterEach
    fun tearDown() {
        h.close()
        tmp.deleteRecursively()
    }

    /** Starts the bridge, pairs the sender and casts [VID] alone; returns once the speaker was told to load something. */
    private suspend fun castOne(bridge: Bridge) {
        h.innerTube.scriptNext(VID, previousId = null, nextId = null)
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(VID))
        h.awaitUntil("SetAVTransportURI on the speaker") { h.setAvTransportUris().isNotEmpty() }
    }

    private fun get(url: String, range: String? = null): HttpResponse<ByteArray> {
        val req = HttpRequest.newBuilder(URI(url)).GET().also { if (range != null) it.header("Range", range) }.build()
        return h.http.send(req, HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    @DisplayName("auto format with a speaker that lists the video's MIME hands out the direct proxy, relayed from the upstream")
    fun directProxyIsRelayedFromTheUpstream() = h.run<Unit> {
        val body = ByteArray(5000) { (it % 251).toByte() }
        h.resolvedUrl = h.startUpstream(body) + "/audio.m4a"
        val bridge = h.newBridge(h.testConfig(outputFormat = "auto"), upstreamPolicy = UpstreamPolicy { true })
        castOne(bridge)

        val uri = h.setAvTransportUris().first()
        assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/stream/[0-9a-f]{32}\\.m4a").matches(uri), "CurrentURI was $uri")
        val ranged = get(uri, "bytes=0-9")
        assertEquals(206, ranged.statusCode())
        assertContentEquals(body.copyOfRange(0, 10), ranged.body())
        assertEquals("audio/mp4", ranged.headers().firstValue("Content-Type").get())
        assertTrue(
            h.upstreamSeen.any { it.method == "GET" && it.headers["accept-encoding"] == "identity" },
            "the upstream saw Accept-Encoding identity: ${h.upstreamSeen.map { it.headers }}",
        )
    }

    @Test
    @DisplayName("with the default policy a loopback upstream is refused with 502 and the start-failure path moves on to the next format")
    fun defaultPolicyRefusesALoopbackUpstreamAndTheChainMovesOn() {
        // a speaker that accepts the URL but never plays it, as one whose upstream fetch was refused
        h.close()
        h = BridgeHarness(neverPlay = true)
        h.run<Unit> {
            h.resolvedUrl = h.startUpstream(ByteArray(2000)) + "/audio.m4a"
            val bridge = h.newBridge(h.testConfig(outputFormat = "auto", startTimeoutMs = 800)) // no upstreamPolicy: production default
            castOne(bridge)

            val first = h.setAvTransportUris().first()
            assertTrue(first.endsWith(".m4a"), "auto chose the direct format first: $first")
            assertEquals(502, get(first, "bytes=0-9").statusCode())
            assertTrue(h.upstreamSeen.none { it.method == "GET" }, "the loopback upstream was never contacted")

            h.awaitUntil("a further format after the start timeout", 15_000) { h.setAvTransportUris().size >= 2 }
            val second = h.setAvTransportUris()[1]
            assertTrue(second.endsWith(".aac") || second.endsWith(".mp4") || second.endsWith(".mp3"), "the next format of the chain: $second")
        }
    }

    @Test
    @DisplayName("a forced flac format with the real ffmpeg serves a FLAC file with byte ranges from a sub-directory of the buffer directory")
    fun forcedFlacServesAFileWithRanges() = h.run<Unit> {
        assumeTrue(h.findFfmpeg() != null, "ffmpeg is not installed")
        val source = File(tmp, "source/sine.m4a")
        assumeTrue(h.makeSineSource(source), "could not make the sine source with ffmpeg")
        val buffer = File(tmp, "buffer").also { it.mkdirs() }
        val downloader = SourceDownloader { _, _, target ->
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        val bridge = h.newBridge(
            h.testConfig(outputFormat = "flac"),
            launcher = JvmPipeLauncher(h.findFfmpeg()!!),
            bufferDir = buffer,
            sourceDownloader = downloader,
        )
        castOne(bridge)

        val uri = h.setAvTransportUris().first()
        assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/stream/[0-9a-f]{32}\\.flac").matches(uri), "CurrentURI was $uri")
        val ranged = get(uri, "bytes=0-3")
        assertEquals(206, ranged.statusCode())
        assertContentEquals("fLaC".toByteArray(Charsets.US_ASCII), ranged.body())
        val built = File(buffer, "files").listFiles().orEmpty().filter { it.name.endsWith(".flac") }
        assertTrue(built.isNotEmpty(), "the built file is in <bufferDir>/files, not in the buffer directory itself")
        assertTrue(buffer.listFiles().orEmpty().none { it.name.endsWith(".flac") })

        // Stop keeps the finished cache file (D-12) and leaves no half-written one; the buffer directory stays
        bridge.stop()
        val after = File(buffer, "files").listFiles().orEmpty().map { it.name }
        assertTrue(after.any { it.endsWith(".flac") }, "the finished file survives Stop: $after")
        assertTrue(after.none { it.endsWith(".part") || it.endsWith(".download") }, "no half-written file survives Stop: $after")
        assertTrue(buffer.isDirectory, "the buffer directory itself stays")
    }

    /** Casts one mp4 track through a bridge built with [config] and the real ffmpeg; returns the bridge. */
    private suspend fun castMp4(config: Config): Bridge {
        assumeTrue(h.findFfmpeg() != null, "ffmpeg is not installed")
        val source = File(tmp, "source/sine.m4a")
        assumeTrue(h.makeSineSource(source), "could not make the sine source with ffmpeg")
        val downloader = SourceDownloader { _, _, target ->
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        val bridge = h.newBridge(
            config,
            launcher = JvmPipeLauncher(h.findFfmpeg()!!),
            bufferDir = File(tmp, "buffer").also { it.mkdirs() },
            sourceDownloader = downloader,
        )
        castOne(bridge)
        return bridge
    }

    private fun cacheOf(bridge: Bridge) = bridge.state()["cache"]!!.jsonObject

    @Test
    @DisplayName("config.json cacheMb is the cache cap the running bridge uses, and cast-state shows the cache")
    fun cacheMbIsTheCapAndCastStateShowsTheCache() = h.run<Unit> {
        val bridge = castMp4(h.testConfig(outputFormat = "mp4").copy(cacheMb = 64))
        val cache = cacheOf(bridge)
        assertEquals(64L * 1_048_576L, cache["limitBytes"]!!.jsonPrimitive.long)
        assertTrue(cache["files"]!!.jsonPrimitive.int >= 2, "the built file and its source: $cache")
        assertTrue(cache["bytes"]!!.jsonPrimitive.long > 0, "$cache")
    }

    @Test
    @DisplayName("without a cacheMb in the config the cache cap is 256 MiB")
    fun theDefaultCacheCapIs256MiB() = h.run<Unit> {
        val bridge = castMp4(h.testConfig(outputFormat = "mp4"))
        assertEquals(268_435_456L, cacheOf(bridge)["limitBytes"]!!.jsonPrimitive.long)
    }

    @Test
    @DisplayName("a bridge without a file builder reports no cache")
    fun aBridgeWithoutAFileBuilderReportsNoCache() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(outputFormat = "auto"))
        bridge.start()
        assertEquals(JsonNull, bridge.state()["cache"])
        assertTrue(bridge.state().containsKey("cache"))
    }

    @Test
    @DisplayName("a launcher without a source downloader means file builds are unavailable: a forced mp4 cast fails, the bridge keeps running")
    fun withoutASourceDownloaderAFileFormatFailsButTheBridgeRuns() = h.run<Unit> {
        val buffer = File(tmp, "buffer").also { it.mkdirs() }
        val bridge = h.newBridge(
            // No retries: this test is about the first failure (plan 04-07 retries an unclear failure twice before it gives up).
            h.testConfig(outputFormat = "mp4").copy(playRetryDelaysMs = emptyList()),
            launcher = JvmPipeLauncher("/usr/bin/ffmpeg"),
            bufferDir = buffer,
        )
        h.innerTube.scriptNext(VID, previousId = null, nextId = null)
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(VID))

        var rendererState = ""
        var error: String? = null
        h.awaitUntil("the renderer reports a failure and stops") {
            rendererState = bridge.state()["renderers"].toString()
            error = Regex("\"lastError\":\"([^\"]*)\"").find(rendererState)?.groupValues?.get(1)
            error != null && rendererState.contains("\"status\":\"STOPPED\"")
        }
        assertTrue(assertNotNull(error).contains("file builder"), "lastError was $error")
        assertTrue(h.setAvTransportUris().isEmpty(), "nothing was loaded on the speaker")
        assertTrue(rendererState.contains("\"status\":\"STOPPED\""), "the renderer is stopped: $rendererState")
        assertEquals("true", bridge.state()["running"].toString())
    }
}
