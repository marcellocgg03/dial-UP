package dev.t2u.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the resolver.js downloadWithYtdlp tests: the info JSON of the earlier lookup is handed to yt-dlp through a
 * private file (threats T-03-22 and T-03-23). The gateway is a fake that records what yt-dlp would see.
 */
class YtDlpSourceDownloaderTest {
    @TempDir lateinit var dir: File

    private val rawJson = """{"id":"aaaaaaaaaaa","url":"https://rr1.googlevideo.com/videoplayback?sig=SECRET"}"""
    private val videoId = "aaaaaaaaaaa"

    private fun info(raw: String? = rawJson, formatId: String? = "140") = ResolvedInfo(
        "https://rr1.googlevideo.com/videoplayback?sig=SECRET", "m4a", "audio/mp4", mapOf("User-Agent" to "ua"),
        "Title", "Artist", "Album", "", 60.0, 0, formatId, raw,
    )

    /** What the fake saw at call time, before the downloader had a chance to clean up. */
    private class Seen(
        val call: YtDlpCall,
        val infoPath: String?,
        val infoPerms: String?,
        val infoContent: String?,
    )

    private class FakeGateway(private val behave: (YtDlpCall, File?) -> GatewayResult) : YtDlpGateway {
        val seen = CopyOnWriteArrayList<Seen>()

        override fun run(call: YtDlpCall): GatewayResult {
            val infoPath = call.options.firstOrNull { it.name == "--load-info-json" }?.value
            val infoFile = infoPath?.let(::File)
            val perms = infoFile?.takeIf { it.exists() }?.let {
                PosixFilePermissions.toString(Files.getPosixFilePermissions(it.toPath()))
            }
            seen += Seen(call, infoPath, perms, infoFile?.takeIf { it.exists() }?.readText())
            val out = call.options.first { it.name == "-o" }.value!!
            return behave(call, File(out))
        }

        override fun version(): String? = "fake"

        override fun update(channel: String): String = "DONE"
    }

    private fun writesAudio() = FakeGateway { _, out ->
        out.writeText("audio\n")
        GatewayResult(0, "", "", 1)
    }

    private fun option(call: YtDlpCall, name: String): String? = call.options.firstOrNull { it.name == name }?.value

    @Test
    @DisplayName("yt-dlp info JSON is private and never written through a planted symlink")
    fun ytDlpInfoJsonIsPrivateAndNeverWrittenThroughAPlantedSymlink() = runBlocking {
        val target = File(dir, "$videoId.source")
        val victim = File(dir, "victim").also { it.writeText("keep me") }
        try {
            Files.createSymbolicLink(File(target.path + ".info.json").toPath(), victim.toPath())
        } catch (e: Exception) {
            assumeTrue(false, "this platform cannot create a symbolic link: $e")
        }
        val gw = writesAudio()
        YtDlpSourceDownloader(gw).download(videoId, info(), target)

        val seen = gw.seen.single()
        assertEquals("rw-------", seen.infoPerms)
        assertEquals(rawJson, seen.infoContent)
        assertEquals("keep me", victim.readText(), "the symlink target must be untouched")
        assertEquals("audio", target.readText().trim())
        assertFalse(File(target.path + ".info.json").exists(), "removed afterwards")
        assertFalse(Files.isSymbolicLink(File(target.path + ".info.json").toPath()))
    }

    @Test
    fun theLoadInfoJsonCallHasTheNodeOptionsAndNoUrl() = runBlocking {
        val target = File(dir, "$videoId.source")
        val gw = writesAudio()
        YtDlpSourceDownloader(gw).download(videoId, info(), target)

        val call = gw.seen.single().call
        assertNull(call.url)
        assertEquals(target.path + ".info.json", option(call, "--load-info-json"))
        assertEquals("140", option(call, "-f"))
        assertEquals(target.path, option(call, "-o"))
        val names = call.options.map { it.name }
        for (n in listOf("--no-playlist", "--no-warnings", "--no-progress", "--no-part", "--force-overwrites")) {
            assertTrue(n in names, "missing $n in $names")
        }
        assertEquals("never", option(call, "--fixup"))
    }

    @Test
    fun aMissingFormatIdFallsBackToTheNodeFormat() = runBlocking {
        val gw = writesAudio()
        YtDlpSourceDownloader(gw).download(videoId, info(formatId = null), File(dir, "$videoId.source"))
        assertEquals(NODE_FORMAT, option(gw.seen.single().call, "-f"))
    }

    @Test
    fun withoutRawJsonTheCallIsTheUrlForm() = runBlocking {
        val target = File(dir, "$videoId.source")
        val gw = writesAudio()
        YtDlpSourceDownloader(gw, cacheDir = "/c/yt-dlp").download(videoId, info(raw = null), target)

        val seen = gw.seen.single()
        assertNull(seen.infoPath)
        assertEquals("https://www.youtube.com/watch?v=$videoId", seen.call.url)
        assertEquals(NODE_FORMAT, option(seen.call, "-f"))
        assertEquals(target.path, option(seen.call, "-o"))
        assertEquals("/c/yt-dlp", option(seen.call, "--cache-dir"))
        assertFalse(File(target.path + ".info.json").exists())
    }

    @Test
    fun theInfoFileIsRemovedWhenYtDlpFailsAndTheMessageCarriesTheLastTwoLines() = runBlocking {
        val target = File(dir, "$videoId.source")
        val stderr = "first line\nsecond line\nERROR: unable to download https://rr1.googlevideo.com/videoplayback?sig=SECRET: HTTP Error 403: Forbidden\n"
        val gw = FakeGateway { _, _ -> GatewayResult(1, "", stderr, 1) }
        val e = assertFailsWith<DownloadException> { YtDlpSourceDownloader(gw).download(videoId, info(), target) }
        assertTrue(e.forbidden)
        assertTrue(e.message!!.contains("second line | ERROR"), e.message)
        assertFalse(e.message!!.contains("first line"), e.message)
        assertFalse(e.message!!.contains("SECRET"), "signed query must be redacted: ${e.message}")
        assertFalse(File(target.path + ".info.json").exists(), "removed after a failure")
    }

    @Test
    fun aFailureWithoutA403IsNotForbidden() = runBlocking {
        val gw = FakeGateway { _, _ -> GatewayResult(1, "", "ERROR: boom", 1) }
        val e = assertFailsWith<DownloadException> {
            YtDlpSourceDownloader(gw).download(videoId, info(), File(dir, "$videoId.source"))
        }
        assertFalse(e.forbidden)
    }

    @Test
    fun aSuccessfulExitWithNoFileOrAnEmptyFileThrows() = runBlocking {
        val none = FakeGateway { _, _ -> GatewayResult(0, "", "", 1) }
        val t1 = File(dir, "one.source")
        assertFailsWith<DownloadException> { YtDlpSourceDownloader(none).download(videoId, info(), t1) }
        assertFalse(File(t1.path + ".info.json").exists())

        val empty = FakeGateway { _, out ->
            out.writeBytes(ByteArray(0))
            GatewayResult(0, "", "", 1)
        }
        assertFailsWith<DownloadException> { YtDlpSourceDownloader(empty).download(videoId, info(), File(dir, "two.source")) }
    }

    @Test
    fun anInvalidVideoIdNeverReachesTheGateway() {
        val gw = writesAudio()
        assertFailsWith<IllegalArgumentException> {
            runBlocking { YtDlpSourceDownloader(gw).download("--exec=evil", info(), File(dir, "x.source")) }
        }
        assertEquals(0, gw.seen.size)
    }

    @Test
    fun aLeftoverInfoFileIsReplacedNotAppendedTo() = runBlocking {
        val target = File(dir, "$videoId.source")
        File(target.path + ".info.json").writeText("stale content that is longer than the new json ".repeat(10))
        val gw = writesAudio()
        YtDlpSourceDownloader(gw).download(videoId, info(), target)
        assertEquals(rawJson, gw.seen.single().infoContent)
    }

    @Test
    fun resolvedInfoToStringHidesTheSignedUrlTheHeadersAndTheRawJson() {
        val text = info().toString()
        assertFalse(text.contains("SECRET"), text)
        assertFalse(text.contains("googlevideo"), text)
        assertFalse(text.contains("User-Agent"), text)
        assertFalse(text.contains("\"id\""), text)
        assertTrue(text.contains("Title"), text)
        assertTrue(text.contains("140"), text)
    }

    @Test
    fun aCachingResolverLookupAttachesTheStdoutJsonAsRawJson() = runBlocking {
        val stdout = """{"url":"https://h.googlevideo.com/videoplayback?sig=SECRET","ext":"m4a","format_id":"140","title":"T","duration":60}"""
        val gw = object : YtDlpGateway {
            override fun run(call: YtDlpCall) = GatewayResult(0, stdout, "", 1)

            override fun version(): String? = null

            override fun update(channel: String) = "DONE"
        }
        val got = CachingResolver(gw, null).resolve(videoId)
        assertNotNull(got.rawJson)
        assertEquals(stdout, got.rawJson)
        assertEquals("140", got.formatId)
    }

    @Test
    fun parseInfoStoresTheTextItWasGiven() {
        val json = """{"url":"https://h/x","ext":"m4a"}"""
        assertEquals(json, parseInfo(json, videoId).rawJson)
    }
}
