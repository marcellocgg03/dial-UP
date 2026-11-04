package dev.t2u.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WR-02: the downloader's own time limit is an error the file builder retries and reports. An outer limit or a
 * cancellation from the caller still ends as a cancellation.
 */
class YtDlpSourceDownloaderTimeoutTest {
    @TempDir lateinit var dir: File

    private val videoId = "aaaaaaaaaaa"
    private val rawJson = """{"id":"aaaaaaaaaaa","url":"https://rr1.googlevideo.com/videoplayback?sig=SECRET"}"""

    private fun info() = ResolvedInfo(
        "https://rr1.googlevideo.com/videoplayback?sig=SECRET", "m4a", "audio/mp4", mapOf("User-Agent" to "ua"),
        "Title", "Artist", "Album", "", 60.0, 0, "140", rawJson,
    )

    /** A gateway whose run blocks until its thread is interrupted, like a hung yt-dlp process. */
    private class HangingGateway : YtDlpGateway {
        val entered = CountDownLatch(1)

        override fun run(call: YtDlpCall): GatewayResult {
            entered.countDown()
            CountDownLatch(1).await()
            return GatewayResult(0, "", "", 1)
        }

        override fun version(): String? = "fake"

        override fun update(channel: String): String = "DONE"
    }

    @Test
    fun ownTimeoutIsADownloadExceptionWithoutUrlAndTheInfoFileIsGone() = runBlocking<Unit> {
        val target = File(dir, "$videoId.source")
        val e = assertFailsWith<DownloadException> {
            YtDlpSourceDownloader(HangingGateway(), timeoutMs = 100).download(videoId, info(), target)
        }
        assertTrue(e.message!!.contains("timed out"), e.message)
        assertTrue(e.message!!.contains(videoId), e.message)
        assertFalse(e.message!!.contains("googlevideo"), "no URL in the message")
        assertFalse(e.forbidden)
        assertFalse(File(target.path + ".info.json").exists(), "the info file is removed afterwards")
    }

    @Test
    fun cancellingTheCallerStillThrowsCancellation() = runBlocking<Unit> {
        val gw = HangingGateway()
        val target = File(dir, "$videoId.source")
        val job = async(Dispatchers.Default) {
            YtDlpSourceDownloader(gw, timeoutMs = 60_000).download(videoId, info(), target)
        }
        assertTrue(gw.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun anOuterTimeoutStaysATimeoutCancellation() = runBlocking<Unit> {
        val target = File(dir, "$videoId.source")
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100) {
                YtDlpSourceDownloader(HangingGateway(), timeoutMs = 60_000).download(videoId, info(), target)
            }
        }
    }

    @Test
    fun fileBuilderRetriesAndReportsTheTimeoutAsABuildFailure() = runBlocking<Unit> {
        val events = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo = info()

            override fun invalidate(videoId: String) {}
        }
        val builder = FileBuilder(
            dir = File(dir, "build"),
            launcher = PipeLauncher { error("ffmpeg must not start") },
            resolver = resolver,
            downloader = YtDlpSourceDownloader(HangingGateway(), timeoutMs = 80),
            retryDelaysMs = listOf(10L, 10L),
            onEvent = { events += it },
        )
        try {
            val e = assertFailsWith<FileBuildException> { builder.sourceFile(videoId) }
            assertTrue(e.message!!.contains("timed out"), e.message)
            // coroutine stack-trace recovery may wrap the exception once, so look anywhere in the cause chain
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is DownloadException })
            assertEquals(2, events.count { it["event"] == "download-retry" })
        } finally {
            builder.close()
        }
    }
}
