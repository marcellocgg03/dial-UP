package dev.t2u.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of the cache titles of reference/tube2upnp/test/remux.test.js (D-16), against real /usr/bin/ffmpeg and
 * ffprobe. The source a track is "downloaded" from is a generated fragmented M4A; no network is involved.
 * Eviction runs inside the build (before `prepare` returns), so no test waits for a background prune.
 */
class MediaServerCacheTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = "/usr/bin/ffmpeg"
    private val ffprobe = "/usr/bin/ffprobe"
    private lateinit var buildDir: File
    private lateinit var launcher: PipeLauncher
    private val servers = CopyOnWriteArrayList<MediaServer>()

    /** Copies a generated source to the requested target, as a real download would leave a file there. */
    private class FakeSourceDownloader(private val src: File) : SourceDownloader {
        val calls = AtomicInteger()

        override suspend fun download(videoId: String, info: ResolvedInfo, target: File) {
            calls.incrementAndGet()
            src.copyTo(target, overwrite = true)
        }
    }

    private class StubResolver : VideoResolver {
        override suspend fun resolve(videoId: String): ResolvedInfo =
            ResolvedInfo("https://stub.invalid/a.m4a", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")

        override fun invalidate(videoId: String) {}
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(File(ffmpeg).canExecute() && File(ffprobe).canExecute(), "needs $ffmpeg and $ffprobe")
        buildDir = File(dir, "build")
        launcher = JvmPipeLauncher(ffmpeg)
    }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun makeSource(name: String, seconds: Int): File {
        val out = File(dir, name)
        val p = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
            "-c:a", "aac", "-b:a", "128k", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", out.absolutePath,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), log)
        return out
    }

    private fun serve(
        downloader: SourceDownloader,
        cacheLimitBytes: Long = 64L * 1_048_576L,
        events: MutableList<Map<String, Any?>>? = null,
    ): MediaServer {
        val builder = FileBuilder(
            dir = buildDir,
            launcher = launcher,
            resolver = StubResolver(),
            downloader = downloader,
            cacheLimitBytes = { cacheLimitBytes },
            retryDelaysMs = listOf(10L, 10L),
            onEvent = { e -> events?.add(e) },
        )
        return MediaServer("127.0.0.1", 0, launcher, { }, File(dir, "pipe"), StubResolver(), files = builder).also {
            it.start()
            servers.add(it)
        }
    }

    private fun get(ms: MediaServer, path: String) = RawHttp.request(ms.resolvedPort(), "GET", path)

    private fun durationOf(bytes: ByteArray): Double {
        val f = File(dir, "out-${System.nanoTime()}")
        f.writeBytes(bytes)
        val p = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f.absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text.toDouble()
    }

    private fun cached(): List<String> = buildDir.listFiles()!!.map { it.name }.sorted()

    @Test
    @DisplayName("cache: a file larger than CACHE_MB is still served; older files are evicted first")
    fun aFileLargerThanTheCapIsStillServedAndOlderFilesAreEvictedFirst() = runBlocking {
        val ms = serve(FakeSourceDownloader(makeSource("src.m4a", 60)), cacheLimitBytes = 0)
        val a = VideoEntry("mmmmmmmmmmm", "mp4")
        ms.prepare(a)
        val ra = get(ms, ms.registerVideo(a))
        assertEquals(200, ra.status)
        assertTrue(durationOf(ra.body) > 59, "complete file served")
        assertEquals(listOf("mmmmmmmmmmm-mp4-0.m4a", "mmmmmmmmmmm.source"), cached(), "just built: kept")

        val b = VideoEntry("nnnnnnnnnnn", "flac")
        ms.prepare(b)
        val rb = get(ms, ms.registerVideo(b))
        assertEquals(200, rb.status)
        assertEquals(listOf("nnnnnnnnnnn-flac-0.flac", "nnnnnnnnnnn.source"), cached(), "older track evicted")
        Unit
    }

    @Test
    @DisplayName("cache: a file that vanished after it was built is built again")
    fun aFileThatVanishedAfterItWasBuiltIsBuiltAgain() = runBlocking {
        val downloader = FakeSourceDownloader(makeSource("src.m4a", 60))
        val ms = serve(downloader)
        val entry = VideoEntry("ooooooooooo", "mp4")
        ms.prepare(entry)
        get(ms, ms.registerVideo(entry))
        val before = downloader.calls.get()
        buildDir.listFiles()!!.forEach { it.delete() } // e.g. Android reclaiming the cache directory
        ms.prepare(entry)
        val again = get(ms, ms.registerVideo(entry))
        assertEquals(200, again.status)
        assertTrue(durationOf(again.body) > 59)
        assertEquals(1, downloader.calls.get() - before, "source downloaded again")
        Unit
    }

    @Test
    @DisplayName("cache survives a restart; half-written files are dropped; clear works")
    fun cacheSurvivesARestartHalfWrittenFilesAreDroppedClearWorks() = runBlocking {
        val downloader = FakeSourceDownloader(makeSource("src.m4a", 5))
        val entry = VideoEntry("iiiiiiiiiii", "mp4")
        val first = serve(downloader)
        first.prepare(entry)
        first.stop()
        assertEquals(1, downloader.calls.get())
        // Simulate a crash mid-download of another track.
        File(buildDir, "jjjjjjjjjjj.source.download").writeText("half")

        val second = serve(downloader)
        second.prepare(entry)
        assertEquals(1, downloader.calls.get(), "served from the cache kept on disk, not downloaded again")
        assertTrue(!File(buildDir, "jjjjjjjjjjj.source.download").exists(), "partial file removed")
        assertTrue(second.cacheInfo()!!.files >= 2)

        assertTrue(second.clearCache() >= 2)
        assertEquals(0, second.cacheInfo()!!.files)
        second.prepare(entry)
        assertEquals(2, downloader.calls.get(), "downloaded again after clearing")
        Unit
    }

    @Test
    fun startKeepsOnlyFinishedCacheFilesAndReportsTheScan() {
        buildDir.mkdirs()
        File(buildDir, "aaaaaaaaaaa-mp4-0.m4a").writeBytes(ByteArray(100))
        File(buildDir, "aaaaaaaaaaa.source").writeBytes(ByteArray(50))
        File(buildDir, "bbbbbbbbbbb-flac-0.flac").writeBytes(ByteArray(0))
        File(buildDir, "bbbbbbbbbbb-flac-0.flac.part").writeBytes(ByteArray(10))
        File(buildDir, "ccccccccccc.source.download").writeBytes(ByteArray(10))
        File(buildDir, "ccccccccccc.source.download.info.json").writeBytes(ByteArray(10))
        File(buildDir, "ccccccccccc.source.download.ytdl").writeBytes(ByteArray(10))
        File(buildDir, "notes.txt").writeBytes(ByteArray(10))
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        serve(FakeSourceDownloader(makeSource("src.m4a", 1)), events = events)
        assertEquals(listOf("aaaaaaaaaaa-mp4-0.m4a", "aaaaaaaaaaa.source"), cached())
        val scan = events.filter { it["event"] == "cache-scan" }
        assertEquals(1, scan.size, events.toString())
        assertEquals(2, scan.single()["kept"])
        assertEquals(150L, scan.single()["keptBytes"])
    }

    @Test
    fun stopKeepsFinishedFilesAndDeletesTempFiles() = runBlocking {
        val ms = serve(FakeSourceDownloader(makeSource("src.m4a", 5)))
        ms.prepare(VideoEntry("ddddddddddd", "mp4"))
        File(buildDir, "ddddddddddd-flac-0.flac.part").writeText("x")
        File(buildDir, "eeeeeeeeeee.source.download").writeText("x")
        File(buildDir, "eeeeeeeeeee.source.download.info.json").writeText("x")
        ms.stop()
        assertEquals(listOf("ddddddddddd-mp4-0.m4a", "ddddddddddd.source"), cached())
        Unit
    }
}
