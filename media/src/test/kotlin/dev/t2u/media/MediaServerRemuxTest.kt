package dev.t2u.media

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of the remux.test.js titles that belong to the file formats (mp4 and flac) and to the download retries,
 * against real /usr/bin/ffmpeg and ffprobe. The source a track is "downloaded" from is a generated 60 s fragmented
 * M4A (what YouTube serves); no network is involved. WSL has ffmpeg 4.4.2, the phone 7.1.1: these tests prove the
 * arguments and the file structure, seek accuracy on 7.1.1 is a device check.
 */
class MediaServerRemuxTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = "/usr/bin/ffmpeg"
    private val ffprobe = "/usr/bin/ffprobe"
    private lateinit var source: File
    private lateinit var buildDir: File
    private lateinit var launcher: CountingLauncher
    private val servers = CopyOnWriteArrayList<MediaServer>()
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    /** Wraps the real launcher so a test can see how many ffmpeg processes were started and with what. */
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

    /** Copies the generated source to the requested target, as a real download would leave a file there. */
    private class FakeSourceDownloader(private val src: File) : SourceDownloader {
        val calls = AtomicInteger()
        val targets = CopyOnWriteArrayList<File>()
        val infos = CopyOnWriteArrayList<ResolvedInfo>()

        override suspend fun download(videoId: String, info: ResolvedInfo, target: File) {
            calls.incrementAndGet()
            targets.add(target)
            infos.add(info)
            src.copyTo(target, overwrite = true)
        }
    }

    /** A resolver with a fixed answer that counts its calls. */
    private class StubResolver : VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo {
            resolves.incrementAndGet()
            return ResolvedInfo("https://stub.invalid/a.m4a", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")
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
        buildDir = File(dir, "build")
        launcher = CountingLauncher(JvmPipeLauncher(ffmpeg))
    }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun serve(
        downloader: SourceDownloader,
        resolver: VideoResolver = StubResolver(),
        flacBits: Int = 16,
        retryDelaysMs: List<Long> = listOf(10L, 10L),
    ): MediaServer {
        val builder = FileBuilder(
            dir = buildDir,
            launcher = launcher,
            resolver = resolver,
            downloader = downloader,
            flacBits = flacBits,
            retryDelaysMs = retryDelaysMs,
        )
        return MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, File(dir, "pipe"), resolver, files = builder).also {
            it.start()
            servers.add(it)
        }
    }

    private fun get(ms: MediaServer, path: String, headers: Map<String, String> = emptyMap()) =
        RawHttp.request(ms.resolvedPort(), "GET", path, headers)

    private fun probe(file: File, entries: String): String {
        val p = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", entries, "-of", "csv=p=0", file.absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun duration(file: File): Double = probe(file, "format=duration").toDouble()

    /** Top-level box types of an MP4 (4-byte size, 4-byte type; size 1 means a 64-bit size follows). */
    private fun topLevelBoxes(bytes: ByteArray): List<String> {
        val types = ArrayList<String>()
        var pos = 0L
        while (pos + 8 <= bytes.size) {
            val at = pos.toInt()
            var size = ((bytes[at].toLong() and 0xff) shl 24) or ((bytes[at + 1].toLong() and 0xff) shl 16) or
                ((bytes[at + 2].toLong() and 0xff) shl 8) or (bytes[at + 3].toLong() and 0xff)
            types += String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                size = 0
                for (i in 0 until 8) size = (size shl 8) or (bytes[at + 8 + i].toLong() and 0xff)
            }
            if (size < 8) break
            pos += size
        }
        return types
    }

    @Test
    @DisplayName("mp4: lossless remux to a non-fragmented, faststart MP4 with byte ranges")
    fun mp4LosslessRemuxWithByteRanges() = runBlocking {
        val downloader = FakeSourceDownloader(source)
        val ms = serve(downloader)
        val entry = VideoEntry("aaaaaaaaaaa", "mp4")
        ms.prepare(entry)
        val path = ms.registerVideo(entry)
        assertTrue(path.endsWith(".m4a"), path)

        val res = get(ms, path)
        assertEquals(200, res.status)
        assertEquals("audio/mp4", res.header("Content-Type"))
        assertEquals("bytes", res.header("Accept-Ranges"))
        val built = buildDir.listFiles { f -> f.name.endsWith(".m4a") }!!.single()
        assertEquals(built.length(), res.header("Content-Length")!!.toLong())
        assertEquals(built.length(), res.body.size.toLong())

        val boxes = topLevelBoxes(res.body)
        assertEquals("ftyp", boxes.first(), "boxes: $boxes")
        assertTrue(boxes.indexOf("moov") in 0 until boxes.indexOf("mdat"), "moov before mdat (faststart): $boxes")
        assertFalse(boxes.contains("moof"), "not fragmented: $boxes")

        val ranged = get(ms, path, mapOf("Range" to "bytes=100-199"))
        assertEquals(206, ranged.status)
        assertEquals("bytes 100-199/${built.length()}", ranged.header("Content-Range"))
        assertContentEquals(res.body.copyOfRange(100, 200), ranged.body)

        assertEquals("aac", probe(built, "stream=codec_name"))
        assertTrue(kotlin.math.abs(duration(built) - 60.0) < 0.5, "duration ${duration(built)}")
        Unit
    }

    @Test
    fun theDownloaderRunsOnceWithTheResolvedInfoAndATargetInsideTheBuildDirectory() = runBlocking {
        val downloader = FakeSourceDownloader(source)
        val ms = serve(downloader)
        ms.prepare(VideoEntry("bbbbbbbbbbb", "mp4"))
        assertEquals(1, downloader.calls.get())
        assertEquals("https://stub.invalid/a.m4a", downloader.infos.single().url)
        val target = downloader.targets.single()
        assertEquals(buildDir.canonicalFile, target.canonicalFile.parentFile)
        Unit
    }

    @Test
    fun registerVideoForAFileFormatWithoutPrepareFailsClearly() {
        val ms = serve(FakeSourceDownloader(source))
        assertFailsWith<IllegalStateException> { ms.registerVideo(VideoEntry("ccccccccccc", "mp4")) }
        assertFailsWith<IllegalArgumentException> { ms.registerVideo(VideoEntry("ccccccccccc", "ogg")) }
    }

    // ----- flac, offset builds, coalescing (Task 2) ---------------------------------------------

    @Test
    @DisplayName("flac (\${bits}-bit): lossless file with byte ranges and correct length")
    fun flacLosslessFileWithByteRangesAndCorrectLength() = runBlocking {
        for (bits in listOf(16, 24)) {
            val ms = serve(FakeSourceDownloader(source), flacBits = bits)
            val entry = VideoEntry("eeeeeeeee$bits", "flac")
            ms.prepare(entry)
            val path = ms.registerVideo(entry)
            assertTrue(path.endsWith(".flac"), path)

            val res = get(ms, path)
            assertEquals(200, res.status)
            assertEquals("audio/flac", res.header("Content-Type"))
            val built = File(buildDir, "eeeeeeeee$bits-flac-0.flac")
            assertEquals(built.length(), res.header("Content-Length")!!.toLong())
            assertEquals(built.length(), res.body.size.toLong())
            assertEquals("fLaC", String(res.body, 0, 4, Charsets.ISO_8859_1))

            assertEquals("flac", probe(built, "stream=codec_name"))
            if (bits == 16) {
                assertEquals("s16", probe(built, "stream=sample_fmt"))
            } else {
                assertEquals("s32", probe(built, "stream=sample_fmt"))
            }
            assertEquals(bits.toString(), probe(built, "stream=bits_per_raw_sample"))
            assertTrue(kotlin.math.abs(duration(built) - 60.0) < 0.5, "duration ${duration(built)}")

            val ranged = get(ms, path, mapOf("Range" to "bytes=0-99"))
            assertEquals(206, ranged.status)
            assertEquals(100, ranged.body.size)
            assertEquals("fLaC", String(ranged.body, 0, 4, Charsets.ISO_8859_1))
        }
    }

    @Test
    @DisplayName("flac at offset (renderer cannot Seek)")
    fun flacAtOffset() = runBlocking {
        val ms = serve(FakeSourceDownloader(source))
        ms.prepare(VideoEntry("fffffffffff", "flac"))
        val whole = File(buildDir, "fffffffffff-flac-0.flac")
        val wholeStamp = whole.lastModified()
        ms.prepare(VideoEntry("fffffffffff", "flac", startAt = 30))
        val cut = File(buildDir, "fffffffffff-flac-30.flac")
        assertTrue(cut.isFile)
        assertTrue(kotlin.math.abs(duration(cut) - 30.0) < 1.0, "duration ${duration(cut)}")
        assertEquals(wholeStamp, whole.lastModified(), "the file for startAt 0 is not touched")
        assertTrue(kotlin.math.abs(duration(whole) - 60.0) < 0.5)
    }

    @Test
    @DisplayName("mp4 at offset (renderer cannot Seek) reuses the downloaded source")
    fun mp4AtOffsetReusesTheDownloadedSource() = runBlocking {
        val downloader = FakeSourceDownloader(source)
        val ms = serve(downloader)
        ms.prepare(VideoEntry("bbbbbbbbbbb", "mp4"))
        val entry30 = VideoEntry("bbbbbbbbbbb", "mp4", startAt = 30)
        ms.prepare(entry30)
        assertEquals(1, downloader.calls.get(), "source downloaded once")
        val cut = File(buildDir, "bbbbbbbbbbb-mp4-30.m4a")
        assertTrue(kotlin.math.abs(duration(cut) - 30.0) < 1.0, "duration ${duration(cut)}")
        val p0 = ms.registerVideo(VideoEntry("bbbbbbbbbbb", "mp4"))
        val p30 = ms.registerVideo(entry30)
        assertTrue(p0 != p30)
        assertEquals(File(buildDir, "bbbbbbbbbbb-mp4-0.m4a").length(), get(ms, p0).body.size.toLong())
        assertEquals(cut.length(), get(ms, p30).body.size.toLong())
    }

    @Test
    fun twoConcurrentPreparesForTheSameBuildStartOneFfmpegAndOneDownload() = runBlocking {
        val downloader = FakeSourceDownloader(source)
        val ms = serve(downloader)
        val entry = VideoEntry("ggggggggggg", "mp4")
        listOf(async { ms.prepare(entry) }, async { ms.prepare(entry) }).awaitAll()
        assertEquals(1, launcher.starts.get(), "one ffmpeg run")
        assertEquals(1, downloader.calls.get(), "one download")
        assertTrue(File(buildDir, "ggggggggggg-mp4-0.m4a").length() > 0)
        Unit
    }

    @Test
    fun differentFormatsOfOneVideoShareTheDownload() = runBlocking {
        val downloader = FakeSourceDownloader(source)
        val ms = serve(downloader)
        listOf(async { ms.prepare(VideoEntry("hhhhhhhhhhh", "mp4")) }, async { ms.prepare(VideoEntry("hhhhhhhhhhh", "flac")) }).awaitAll()
        assertEquals(1, downloader.calls.get(), "one download for both formats")
        assertEquals(2, launcher.starts.get())
        Unit
    }

    @Test
    fun theFlacBitDepthMustBe16Or24() {
        assertFailsWith<IllegalArgumentException> {
            FileBuilder(buildDir, launcher, StubResolver(), FakeSourceDownloader(source), flacBits = 32)
        }
    }

    @Test
    fun theBuildArgumentsCarryOnlyFixedFlagsTheOffsetAndPathsInTheBuildDirectory() {
        val src = File(buildDir, "aaaaaaaaaaa.source")
        val out = File(buildDir, "aaaaaaaaaaa-flac-30.flac.part")
        val args = fileBuildArgs("flac", 24, 30, src, out)
        assertEquals(listOf("-ss", "30"), args.subList(args.indexOf("-ss"), args.indexOf("-ss") + 2))
        assertTrue(args.indexOf("-ss") < args.indexOf("-i"), "input-side seek")
        assertEquals(out.path, args.last())
        assertEquals(src.path, args[args.indexOf("-i") + 1])
        assertTrue(args.containsAll(listOf("-c:a", "flac", "-sample_fmt", "s32", "-bits_per_raw_sample", "24", "-compression_level", "5", "-f", "flac")))
        val mp4 = fileBuildArgs("mp4", 16, 0, src, File(buildDir, "x.part"))
        assertTrue(mp4.containsAll(listOf("-c:a", "copy", "-movflags", "+faststart", "-f", "mp4")))
        assertFalse(mp4.contains("-ss"), "no seek at offset 0")
    }

    // ----- failures, cleanup, vanished files (Task 3) --------------------------------------------

    /** Throws "HTTP Error 403" for the first [failures] calls, then leaves the (not really AAC) bytes "audio". */
    private class FlakyDownloader(private val failures: Int, private val message: String = "yt-dlp download failed for x: ERROR: HTTP Error 403: Forbidden") : SourceDownloader {
        val calls = AtomicInteger()

        override suspend fun download(videoId: String, info: ResolvedInfo, target: File) {
            if (calls.incrementAndGet() <= failures) throw IllegalStateException(message)
            target.writeText("audio")
        }
    }

    @Test
    @DisplayName("download retries with a fresh extraction after a 403")
    fun downloadRetriesWithAFreshExtractionAfterA403() = runBlocking {
        val resolver = StubResolver()
        val downloader = FlakyDownloader(failures = 2)
        val ms = serve(downloader, resolver, retryDelaysMs = listOf(10L, 10L))
        // The stub "audio" is not real AAC, so the remux after the download may fail: that is fine here.
        runCatching { ms.prepare(VideoEntry("iiiiiiiiiii", "mp4")) }
        assertEquals(3, downloader.calls.get())
        assertEquals(3, resolver.resolves.get(), "each retry uses a fresh extraction")
        assertEquals(2, resolver.invalidations.get())
        Unit
    }

    @Test
    @DisplayName("download gives up after the configured retries")
    fun downloadGivesUpAfterTheConfiguredRetries() = runBlocking {
        val downloader = FlakyDownloader(failures = 99, message = "nope")
        val ms = serve(downloader, retryDelaysMs = listOf(10L))
        val e = assertFailsWith<Exception> { ms.prepare(VideoEntry("jjjjjjjjjjj", "mp4")) }
        assertTrue(e.message!!.contains("nope"), e.message)
        // coroutine stack-trace recovery may add copies on the way; the downloader's own exception stays at the root
        assertEquals("nope", generateSequence<Throwable>(e) { it.cause }.last().message, "the cause is kept")
        assertEquals(2, downloader.calls.get())
        Unit
    }

    @Test
    fun aFailedDownloadLeavesNothingAndIsNotCached() = runBlocking {
        val downloader = FlakyDownloader(failures = 99, message = "nope")
        val ms = serve(downloader, retryDelaysMs = emptyList())
        assertFailsWith<Exception> { ms.prepare(VideoEntry("kkkkkkkkkkk", "mp4")) }
        assertEquals(emptyList(), buildDir.listFiles()!!.map { it.name }.filter { it.endsWith(".part") || it.endsWith(".download") })
        assertFailsWith<Exception> { ms.prepare(VideoEntry("kkkkkkkkkkk", "mp4")) }
        assertEquals(2, downloader.calls.get(), "a failure is not cached: the second prepare downloads again")
        Unit
    }

    @Test
    fun aSourceFfmpegCannotReadMakesPrepareThrowAndLeavesNoPartFile() = runBlocking {
        val garbage = SourceDownloader { _, _, target -> target.writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
        val ms = serve(garbage)
        val e = assertFailsWith<FileBuildException> { ms.prepare(VideoEntry("lllllllllll", "mp4")) }
        assertTrue(Regex("exited -?\\d+").containsMatchIn(e.message!!), e.message)
        assertFalse(Regex("https?://\\S*\\?").containsMatchIn(e.message!!), "no URL with a query string")
        assertEquals(emptyList(), buildDir.listFiles()!!.map { it.name }.filter { it.endsWith(".part") })
        assertFailsWith<IllegalStateException> { ms.registerVideo(VideoEntry("lllllllllll", "mp4")) }
        Unit
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelTheBuildForTheOther() = runBlocking {
        val slow = SourceDownloader { _, _, target ->
            delay(400)
            source.copyTo(target, overwrite = true)
        }
        val ms = serve(slow)
        val entry = VideoEntry("mmmmmmmmmmm", "mp4")
        val first = async { ms.prepare(entry) }
        val second = async { ms.prepare(entry) }
        delay(100)
        first.cancelAndJoin()
        second.await()
        assertTrue(File(buildDir, "mmmmmmmmmmm-mp4-0.m4a").length() > 0)
        assertEquals(1, launcher.starts.get())
        Unit
    }

    @Test
    fun aRegisteredFileThatVanishedAnswers404ForGetAndHead() = runBlocking {
        val ms = serve(FakeSourceDownloader(source))
        val entry = VideoEntry("ppppppppppp", "mp4")
        ms.prepare(entry)
        val path = ms.registerVideo(entry)
        val tokens = ms.tokenCount()
        File(buildDir, "ppppppppppp-mp4-0.m4a").delete()
        assertEquals(404, get(ms, path).status)
        assertEquals(404, RawHttp.request(ms.resolvedPort(), "HEAD", path).status)
        assertTrue(seen.any { it.status == 404 && it.note == "file vanished" }, seen.toString())
        assertEquals(tokens, ms.tokenCount(), "the token table is unchanged")
        Unit
    }

    @Test
    fun ffmpegStartsWithOnlyTheValidatedIdTheOffsetAndPathsInsideTheBuildDirectory() = runBlocking {
        val builder = FileBuilder(buildDir, launcher, StubResolver(), FakeSourceDownloader(source), retryDelaysMs = emptyList())
        builder.ensure("qqqqqqqqqqq", "flac", 12)
        val args = launcher.argLists.single()
        val root = buildDir.absoluteFile.path
        for (a in args) {
            if (a.contains('/')) assertTrue(a.startsWith("$root/"), "path outside the build directory: $a")
        }
        assertTrue(args.any { it.startsWith("$root/qqqqqqqqqqq-flac-12.flac") })
        for (bad in listOf("../../etc/passwd", "abc; rm -rf /", "short", "has space 1", "ab/cdefghijk")) {
            assertFailsWith<IllegalArgumentException> { builder.ensure(bad, "mp4", 0) }
        }
        assertFailsWith<IllegalArgumentException> { builder.ensure("qqqqqqqqqqq", "mp3", 0) }
        assertFailsWith<IllegalArgumentException> { builder.ensure("qqqqqqqqqqq", "mp4", -1) }
        assertEquals(1, launcher.starts.get(), "refused requests start nothing")
        builder.close()
    }
}
