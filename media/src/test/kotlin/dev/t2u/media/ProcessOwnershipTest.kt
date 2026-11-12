package dev.t2u.media

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-06: a process started inside withContext is killed when the caller is cancelled as the start returns. */
class ProcessOwnershipTest {
    @TempDir lateinit var dir: File

    /** A process that only records that it was destroyed; its stdout blocks until then. */
    private class FakeProcess : Process() {
        val destroyed = AtomicBoolean(false)
        private val gone = CountDownLatch(1)

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                gone.await()
                return -1
            }
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            gone.await()
            return 137
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = gone.await(timeout, unit)

        override fun exitValue(): Int = if (gone.count == 0L) 137 else throw IllegalThreadStateException()

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            destroyed.set(true)
            gone.countDown()
            return this
        }

        override fun isAlive(): Boolean = gone.count > 0
    }

    /** A launcher whose start runs [onStart] first (the test cancels or disconnects there), then returns a recording process. */
    private class RecordingLauncher(private val onStart: () -> Unit) : PipeLauncher {
        val processes = CopyOnWriteArrayList<FakeProcess>()

        override fun start(args: List<String>): Process {
            onStart()
            return FakeProcess().also { processes.add(it) }
        }
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cond() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(cond(), what)
    }

    @Test
    fun startOwnedKillsTheProcessWhenTheCallerIsCancelledAsTheStartReturns() = runBlocking<Unit> {
        lateinit var caller: Job
        val launcher = RecordingLauncher { caller.cancel() }
        caller = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { launcher.startOwned(listOf("x")) }
        caller.start()
        withTimeout(10_000) { caller.join() }
        assertTrue(caller.isCancelled)
        assertEquals(1, launcher.processes.size)
        assertTrue(launcher.processes.single().destroyed.get(), "the process of a cancelled caller was left running")
    }

    @Test
    fun startOwnedHandsAnUncancelledCallerAliveProcess() = runBlocking<Unit> {
        val launcher = RecordingLauncher {}
        val p = async(Dispatchers.Default) { launcher.startOwned(listOf("x")) }.await()
        assertFalse((p as FakeProcess).destroyed.get())
        p.destroyForcibly()
    }

    @Test
    fun aFileBuildClosedWhileFfmpegStartsDestroysTheProcess() {
        val inStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launcher = RecordingLauncher {
            inStart.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val builder = FileBuilder(
            dir = File(dir, "build"),
            launcher = launcher,
            resolver = object : VideoResolver {
                override suspend fun resolve(videoId: String): ResolvedInfo =
                    ResolvedInfo("https://rr1.googlevideo.com/v", "m4a", "audio/mp4", emptyMap(), "T", "A", "", "", 60.0, 0, "140", null)

                override fun invalidate(videoId: String) {}
            },
            downloader = SourceDownloader { _, _, target -> target.writeText("audio") },
        )
        var buildError: Throwable? = null
        try {
            runBlocking {
                val build = launch(Dispatchers.Default) { runCatching { builder.ensure("aaaaaaaaaaa", "mp4", 0) }.onFailure { buildError = it } }
                assertTrue(inStart.await(10, TimeUnit.SECONDS), "ffmpeg never started: $buildError")
                builder.close() // cancels the build job while launcher.start is still running
                release.countDown()
                withTimeout(10_000) { build.join() }
            }
            assertEquals(1, launcher.processes.size)
            awaitTrue("the build's ffmpeg was left running") { launcher.processes.single().destroyed.get() }
        } finally {
            release.countDown()
        }
    }

    /**
     * Sends [request] for a token of [register] to a server whose launcher blocks in start, stops the server while it blocks
     * (which cancels the call), lets the start return and says whether every process that was started got destroyed.
     */
    private fun cutOffDuringStart(request: (String) -> String, register: (MediaServer) -> String, resolver: VideoResolver? = null) {
        val inStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launcher = RecordingLauncher {
            inStart.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val server = MediaServer("127.0.0.1", 0, launcher, { }, File(dir, "buffer"), resolver, PipeRangeConfig(enabled = true))
        server.start()
        val stopper = Thread { server.stop() }
        try {
            val path = register(server)
            Socket("127.0.0.1", server.resolvedPort()).use { sock ->
                sock.getOutputStream().write(request(path).toByteArray())
                sock.getOutputStream().flush()
                assertTrue(inStart.await(10, TimeUnit.SECONDS), "ffmpeg never started")
                stopper.start() // cancels the call while launcher.start is still running
                Thread.sleep(1500) // past the stop's grace period
                release.countDown()
                awaitTrue("ffmpeg was not started") { launcher.processes.isNotEmpty() }
                awaitTrue("an ffmpeg process was left running") { launcher.processes.all { it.destroyed.get() } }
            }
        } finally {
            release.countDown()
            stopper.join(5000)
        }
    }

    private val get = { path: String -> "GET $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n" }

    private val pipeEntry = StreamEntry(StreamSource.PipeSource(listOf("-i", "x")), "adts", "audio/aac", "unit")

    private val resolver = object : VideoResolver {
        override suspend fun resolve(videoId: String): ResolvedInfo =
            ResolvedInfo("https://rr1.googlevideo.com/v", "m4a", "audio/mp4", emptyMap(), "T", "A", "", "", 60.0, 0, "140", null)

        override fun invalidate(videoId: String) {}
    }

    @Test
    fun aPipeGetCutOffByAServerStopWhileFfmpegStartsDestroysTheProcess() {
        cutOffDuringStart(get, { it.register(pipeEntry) })
    }

    @Test
    fun aBufferedHttp10GetCutOffWhileFfmpegStartsDestroysTheProcess() {
        cutOffDuringStart({ "GET $it HTTP/1.0\r\n\r\n" }, { it.register(pipeEntry) })
    }

    @Test
    fun aColdRangeFillCutOffWhileFfmpegStartsDestroysTheProcess() {
        cutOffDuringStart(
            { "GET $it HTTP/1.1\r\nHost: x\r\nRange: bytes=100000-\r\nConnection: close\r\n\r\n" },
            { it.registerVideo(VideoEntry("aaaaaaaaaaa", "adts", startAt = 0)) },
            resolver,
        )
    }
}
