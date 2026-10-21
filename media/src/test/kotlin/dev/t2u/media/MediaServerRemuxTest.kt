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
    private var server: MediaServer? = null
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
        server?.stop()
    }

    private fun serve(
        downloader: SourceDownloader,
        resolver: VideoResolver = StubResolver(),
        flacBits: Int = 16,
        maxKept: Int = 6,
        retryDelaysMs: List<Long> = listOf(10L, 10L),
    ): MediaServer {
        val builder = FileBuilder(
            dir = buildDir,
            launcher = launcher,
            resolver = resolver,
            downloader = downloader,
            flacBits = flacBits,
            maxKept = maxKept,
            retryDelaysMs = retryDelaysMs,
        )
        return MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, File(dir, "pipe"), resolver, files = builder).also {
            it.start()
            server = it
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
}
