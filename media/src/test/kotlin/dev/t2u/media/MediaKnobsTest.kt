package dev.t2u.media

// The debug switches of plan 03-10 (SVC-07): ytdlpVerbose, ffmpegLogLevel and mp3Bitrate change what the code does.
// No process of yt-dlp or ffmpeg is started: a recording gateway and a recording launcher stand in. GPL-3.0-only.
//
// Every test is block-bodied or `run<Unit>`: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

class MediaKnobsTest {
    private val json = """{"url":"https://rr1---sn-test.googlevideo.com/videoplayback?expire=1999999999&itag=140","ext":"m4a","format_id":"140","title":"T","http_headers":{"User-Agent":"ua"},"duration":60}"""

    private class RecordingGateway(private val stdout: String) : YtDlpGateway {
        val seen = CopyOnWriteArrayList<YtDlpCall>()

        override fun run(call: YtDlpCall): GatewayResult {
            seen += call
            return GatewayResult(0, stdout, "", 5)
        }

        override fun version(): String? = "fake"

        override fun update(channel: String): String = "DONE"
    }

    private fun YtDlpCall.verbose(): Boolean = options.any { it.name == "-v" }

    private fun info() = ResolvedInfo("https://rr1---sn-test.googlevideo.com/videoplayback?itag=140", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")

    @Test
    @DisplayName("ytdlpVerbose reaches the yt-dlp call; the default call and the one-shot benchmark resolve do not carry it")
    fun verboseFlagReachesTheYtDlpCall() {
        runBlocking<Unit> {
            val verbose = RecordingGateway(json)
            CachingResolver(verbose, "/c/yt-dlp", verbose = true).resolve(VID)
            assertTrue(verbose.seen.single().verbose(), "verbose = true adds -v")

            val quiet = RecordingGateway(json)
            CachingResolver(quiet, "/c/yt-dlp").resolve(VID)
            assertFalse(quiet.seen.single().verbose(), "the default call has no -v")

            // the benchmark/warm path (Resolver.resolveOnce) passes its own flag and is untouched by the config switch
            val bench = RecordingGateway(json)
            Resolver(bench).resolveOnce(VID, "cold", 0, "/c/yt-dlp", verbose = false)
            assertFalse(bench.seen.single().verbose())
        }
    }

    @Test
    @DisplayName("urlInputArgs and fileInputArgs carry -loglevel followed by the level they are given, error by default")
    fun inputArgsCarryTheLogLevel() {
        fun levelOf(args: List<String>): String = args[args.indexOf("-loglevel") + 1]
        assertEquals("error", levelOf(urlInputArgs(info(), 0)))
        assertEquals("error", levelOf(fileInputArgs("/tmp/x.source", 0)))
        assertEquals("debug", levelOf(urlInputArgs(info(), 30, "debug")))
        assertEquals("warning", levelOf(fileInputArgs("/tmp/x.source", 30, "warning")))
        assertEquals(1, urlInputArgs(info(), 0, "info").count { it == "-loglevel" })
    }

    @Test
    @DisplayName("a media server built with ffmpegLogLevel debug and mp3Bitrate 256k passes both to the launcher for an mp3 pipe request")
    fun mediaServerPassesTheKnobsToTheLauncher() {
        val argLists = CopyOnWriteArrayList<List<String>>()
        // a harmless process that exits at once without output: the server answers 502 and the arguments were recorded
        val launcher = PipeLauncher { args ->
            argLists.add(args)
            ProcessBuilder("true").start()
        }
        val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo = info()

            override fun invalidate(videoId: String) {}
        }
        val server = MediaServer("127.0.0.1", 0, launcher, {}, null, resolver, mp3Bitrate = "256k", ffmpegLogLevel = "debug")
        server.start()
        try {
            val path = server.registerVideo(VideoEntry(VID, "mp3"))
            val conn = URI("http://127.0.0.1:${server.resolvedPort()}$path").toURL().openConnection() as HttpURLConnection
            assertEquals(502, conn.responseCode)
            conn.disconnect()
        } finally {
            server.stop()
        }
        assertTrue(argLists.isNotEmpty(), "ffmpeg would have been launched")
        for (args in argLists) {
            assertEquals("debug", args[args.indexOf("-loglevel") + 1], "$args")
            assertEquals("256k", args[args.indexOf("-b:a") + 1], "$args")
        }
    }
}
