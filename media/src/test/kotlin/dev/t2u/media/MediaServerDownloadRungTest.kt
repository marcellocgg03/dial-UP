package dev.t2u.media

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of the remux.test.js title 'adts/mp3: falls back to the yt-dlp download when YouTube keeps refusing ffmpeg',
 * plus the order of the ladder and the time bound on prepare, against real /usr/bin/ffmpeg. The "YouTube" upstream
 * answers every request with 403; the "download" copies a generated 60 s fragmented M4A.
 */
class MediaServerDownloadRungTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = "/usr/bin/ffmpeg"
    private val ffprobe = "/usr/bin/ffprobe"
    private lateinit var source: File
    private lateinit var upstream: HttpServer
    private lateinit var base: String
    private lateinit var launcher: CountingLauncher
    private val upstreamRequests = AtomicInteger()
    private val servers = CopyOnWriteArrayList<MediaServer>()
    private val builders = CopyOnWriteArrayList<FileBuilder>()
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    private class CountingLauncher(val inner: JvmPipeLauncher) : PipeLauncher {
        val starts = AtomicInteger()
        val argLists = CopyOnWriteArrayList<List<String>>()

        override fun start(args: List<String>): Process {
            starts.incrementAndGet()
            argLists.add(args)
            return inner.start(args)
        }

        override fun stderrTail(process: Process): String = inner.stderrTail(process)
    }

    private class FakeSourceDownloader(private val src: File, private val delayMs: Long = 0) : SourceDownloader {
        val calls = AtomicInteger()

        override suspend fun download(videoId: String, info: ResolvedInfo, target: File) {
            calls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            src.copyTo(target, overwrite = true)
        }
    }

    /** Every URL points at the path the upstream answers with 403. */
    private class RefusedResolver(private val base: String) : VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo {
            resolves.incrementAndGet()
            return ResolvedInfo("$base/forbidden.m4a", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")
        }

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
        }
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(File(ffmpeg).canExecute() && File(ffprobe).canExecute(), "needs $ffmpeg and $ffprobe")
        source = File(dir, "src.m4a")
        val p = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
            "-c:a", "aac", "-b:a", "128k", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", source.absolutePath,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), log)

        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { ex ->
            ex.use {
                upstreamRequests.incrementAndGet()
                ex.sendResponseHeaders(403, -1)
            }
        }
        upstream.executor = Executors.newCachedThreadPool { r -> Thread(r, "upstream").also { it.isDaemon = true } }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
        launcher = CountingLauncher(JvmPipeLauncher(ffmpeg))
    }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.stop() }
        builders.forEach { it.close() }
        if (::upstream.isInitialized) upstream.stop(0)
    }

    private fun builder(resolver: VideoResolver, downloader: SourceDownloader): FileBuilder =
        FileBuilder(
            dir = File(dir, "build"),
            launcher = launcher,
            resolver = resolver,
            downloader = downloader,
            retryDelaysMs = listOf(10L, 10L),
        ).also { builders.add(it) }

    private fun serve(
        resolver: VideoResolver,
        files: FileBuilder?,
        prepareTimeoutMs: Long = 120_000,
    ): MediaServer =
        MediaServer(
            "127.0.0.1", 0, launcher, { seen.add(it) }, File(dir, "pipe"), resolver,
            files = files, prepareTimeoutMs = prepareTimeoutMs,
        ).also {
            it.start()
            servers.add(it)
        }

    private fun probe(file: File, entries: String): String {
        val p = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", entries, "-of", "csv=p=0", file.absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun isAdtsStart(b: ByteArray): Boolean =
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xF0) == 0xF0

    private fun isMp3Start(b: ByteArray): Boolean {
        if (b.size >= 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3") return true
        return b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
    }

    private fun awaitFinished(files: FileBuilder, id: String, format: String, startAt: Int = 0): File {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            files.fileFor(id, format, startAt)?.let { return it }
            Thread.sleep(50)
        }
        error("the $format build of $id did not finish")
    }

    // ----- the last rung ------------------------------------------------------------------------------------

    @Test
    @DisplayName("adts/mp3: falls back to the yt-dlp download when YouTube keeps refusing ffmpeg")
    fun adtsMp3FallsBackToTheYtDlpDownloadWhenYouTubeKeepsRefusingFfmpeg() {
        for (format in listOf("adts", "mp3")) {
            val resolver = RefusedResolver(base)
            val downloader = FakeSourceDownloader(source)
            val ms = serve(resolver, builder(resolver, downloader))
            val path = ms.registerVideo(VideoEntry("jjjjjjjjjjj", format))
            val r = RawHttp.request(ms.resolvedPort(), "GET", path)
            assertEquals(200, r.status, "$format reported: $seen")
            assertTrue(r.body.isNotEmpty(), "$format: empty body")
            if (format == "adts") assertTrue(isAdtsStart(r.body), "adts first bytes ${r.body.take(4)}")
            else assertTrue(isMp3Start(r.body), "mp3 first bytes ${r.body.take(4)}")
            val saved = File(dir, "out.$format").also { it.writeBytes(r.body) }
            assertEquals(if (format == "adts") "aac" else "mp3", probe(saved, "stream=codec_name"))
            assertEquals(1, downloader.calls.get(), "$format: the download ran once")
            assertEquals(1, resolver.invalidations.get(), "$format: only the second attempt drops the cached resolve")
        }
    }

    @Test
    fun withStartAtTheDownloadedFileIsSeekedOnTheInputSide() {
        val resolver = RefusedResolver(base)
        val downloader = FakeSourceDownloader(source)
        val ms = serve(resolver, builder(resolver, downloader))
        val path = ms.registerVideo(VideoEntry("kkkkkkkkkkk", "adts", startAt = 30))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(200, r.status, "reported: $seen")
        val saved = File(dir, "seek.aac").also { it.writeBytes(r.body) }
        assertTrue(Math.abs(probe(saved, "format=duration").toDouble() - 30) < 3, probe(saved, "format=duration"))
        val fileRun = launcher.argLists.single { args -> args.any { it.endsWith("kkkkkkkkkkk.source") } }
        val at = fileRun.indexOf("-ss")
        assertTrue(at >= 0 && fileRun[at + 1] == "30" && at < fileRun.indexOf("-i"), "args: $fileRun")
    }

    @Test
    fun anExistingSourceIsTriedFirstAndYouTubeIsNotContacted() = runBlocking<Unit> {
        val resolver = RefusedResolver(base)
        val downloader = FakeSourceDownloader(source)
        val files = builder(resolver, downloader)
        val ms = serve(resolver, files)
        files.sourceFile("lllllllllll")
        assertEquals(1, downloader.calls.get())
        val resolvesBefore = resolver.resolves.get()
        upstreamRequests.set(0)

        val path = ms.registerVideo(VideoEntry("lllllllllll", "adts"))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(200, r.status, "reported: $seen")
        assertTrue(isAdtsStart(r.body))
        assertEquals(0, upstreamRequests.get(), "the upstream must not be contacted")
        assertEquals(1, downloader.calls.get(), "the downloader must not run again")
        assertEquals(resolvesBefore, resolver.resolves.get(), "nothing is resolved either")
    }

    @Test
    fun hasSourceIsTrueWhileTheDownloadRunsAndAfterItEnded() = runBlocking<Unit> {
        val resolver = RefusedResolver(base)
        val files = builder(resolver, FakeSourceDownloader(source, delayMs = 300))
        assertFalse(files.hasSource("mmmmmmmmmmm"))
        val job = async(Dispatchers.IO) { files.sourceFile("mmmmmmmmmmm") }
        val deadline = System.currentTimeMillis() + 5000
        while (!files.hasSource("mmmmmmmmmmm") && System.currentTimeMillis() < deadline) delay(10)
        assertTrue(files.hasSource("mmmmmmmmmmm"), "a running download counts")
        job.await()
        assertTrue(files.hasSource("mmmmmmmmmmm"), "a finished file counts")
        assertFalse(files.hasSource("nnnnnnnnnnn"))
    }

    @Test
    fun withoutAFileBuilderThePipeKeepsTwoAttemptsAndAnswers502() {
        val resolver = RefusedResolver(base)
        val ms = serve(resolver, files = null)
        val path = ms.registerVideo(VideoEntry("ooooooooooo", "mp3"))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(502, r.status, "reported: $seen")
        assertEquals(2, launcher.starts.get(), "YouTube URL and a fresh extraction only")
    }

    // ----- the time bound on prepare ------------------------------------------------------------------------

    @Test
    fun prepareGivesUpAfterTheTimeBoundButTheBuildKeepsRunning() = runBlocking<Unit> {
        val resolver = RefusedResolver(base)
        val downloader = FakeSourceDownloader(source, delayMs = 800)
        val files = builder(resolver, downloader)
        val ms = serve(resolver, files, prepareTimeoutMs = 200)
        val entry = VideoEntry("ppppppppppp", "mp4")

        val e = assertFailsWith<IllegalStateException> { ms.prepare(entry) }
        assertTrue(e.message!!.contains("took longer than"), e.message)
        assertFalse(e is CancellationException)

        val finished = awaitFinished(files, "ppppppppppp", "mp4")
        assertTrue(finished.length() > 0)
        ms.prepare(entry) // returns at once now
        assertEquals(1, downloader.calls.get(), "the shared build ran once; the later prepare joined it")
        assertNotNull(files.fileFor("ppppppppppp", "mp4", 0))
    }
}
