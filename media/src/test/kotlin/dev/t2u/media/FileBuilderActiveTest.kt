package dev.t2u.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WR-03: the file builder reports running source downloads, which the yt-dlp updater counts as busy. */
class FileBuilderActiveTest {
    @TempDir lateinit var dir: File

    private val videoId = "aaaaaaaaaaa"

    private fun info() = ResolvedInfo(
        "https://rr1.googlevideo.com/videoplayback?sig=SECRET", "m4a", "audio/mp4", mapOf("User-Agent" to "ua"),
        "Title", "Artist", "Album", "", 60.0, 0, "140", null,
    )

    @Test
    fun activeCountIsZeroAtRestPositiveWhileASourceDownloadRunsAndZeroAfter() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builder = FileBuilder(
            dir = File(dir, "build"),
            launcher = PipeLauncher { error("ffmpeg must not start") },
            resolver = object : VideoResolver {
                override suspend fun resolve(videoId: String): ResolvedInfo = info()

                override fun invalidate(videoId: String) {}
            },
            downloader = SourceDownloader { _, _, target ->
                started.countDown()
                release.await()
                target.writeText("audio")
            },
        )
        try {
            assertEquals(0, builder.activeCount())
            val file = async(Dispatchers.Default) { builder.sourceFile(videoId) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(builder.activeCount() >= 1, "a running download counts")
            release.countDown()
            file.await()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (builder.activeCount() != 0 && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(0, builder.activeCount())
        } finally {
            release.countDown()
            builder.close()
        }
    }
}
