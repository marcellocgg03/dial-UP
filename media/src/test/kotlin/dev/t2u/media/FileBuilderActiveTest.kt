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
import kotlin.test.assertFalse
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

    @Test
    fun theFinalPinIsReleasedWhenTheSourceUnpinThrows() = runBlocking<Unit> {
        val build = File(dir, "build").also { it.mkdirs() }
        // the source name is a transient file whose disposal fails: a non-empty directory under that name
        File(build, ".transient").writeText("$videoId.source\n")
        File(build, "$videoId.source").also { it.mkdirs() }.let { File(it, "child").writeText("x") }
        val builder = FileBuilder(
            dir = build,
            launcher = PipeLauncher { error("ffmpeg must not start") },
            resolver = object : VideoResolver {
                override suspend fun resolve(videoId: String): ResolvedInfo = throw IllegalStateException("resolve failed")

                override fun invalidate(videoId: String) {}
            },
            downloader = SourceDownloader { _, _, _ -> },
            retryDelaysMs = emptyList(),
        )
        try {
            val failure = runCatching { builder.ensure(videoId, "mp4", 0) }.exceptionOrNull()
            assertTrue(failure != null, "the build fails")
            assertFalse(builder.isPinned(builder.pathFor(videoId, "mp4", 0)), "the final file must not stay pinned")
        } finally {
            File(build, "$videoId.source").deleteRecursively()
            builder.close()
        }
    }
}
