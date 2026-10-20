package dev.t2u.media

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolverTest {
    private fun fixture(name: String): String = ResolverTest::class.java.getResource("/ytdlp/$name")!!.readText()

    private class FakeGateway(private val result: GatewayResult) : YtDlpGateway {
        val calls = mutableListOf<YtDlpCall>()

        override fun run(call: YtDlpCall): GatewayResult {
            calls += call
            return result
        }

        override fun version(): String? = "fake"

        override fun update(channel: String): String = "DONE"
    }

    @Test
    fun validIdsPass() {
        assertEquals("dQw4w9WgXcQ", requireVideoId("dQw4w9WgXcQ"))
        assertEquals("4NRXx6U8ABQ", requireVideoId("4NRXx6U8ABQ"))
        assertEquals("lp-EO5I60KA", requireVideoId("lp-EO5I60KA"))
    }

    @Test
    fun injectionShapedIdsAreRefused() {
        val bad = listOf(
            "dQw4w9WgXc", // 10 characters
            "dQw4w9WgXcQQ", // 12 characters
            "dQw4w9WgXc;", // shell metacharacter
            "dQw4w9WgXc ", // trailing space
            "../../etc/x", // path traversal
            "dQw4w9WgXcé", // non-ASCII letter
            "--exec=id12", // option-shaped, 11 chars with '='
        )
        for (id in bad) {
            val e = assertFailsWith<IllegalArgumentException>("must refuse <$id>") { requireVideoId(id) }
            assertTrue(e.message!!.startsWith("Refusing invalid video ID"))
            assertFalse(isValidVideoId(id))
        }
    }

    @Test
    fun refusalMessageCapsTheOffendingValue() {
        val e = assertFailsWith<IllegalArgumentException> { requireVideoId("x".repeat(500)) }
        assertTrue(e.message!!.length < 80, e.message)
    }

    @Test
    fun dumpJsonCallPutsUrlLastAndNeverInsideAnOption() {
        val call = dumpJsonCall("dQw4w9WgXcQ", cacheDir = null, verbose = false)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", call.url)
        assertTrue(call.options.none { it.name.contains("youtube.com") || it.value?.contains("youtube.com") == true })
        assertEquals(listOf("--dump-json", "--no-playlist", "--no-warnings", "--no-progress", "-f"), call.options.map { it.name })
        assertEquals(NODE_FORMAT, call.options.single { it.name == "-f" }.value)
        assertEquals("bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best", NODE_FORMAT)
    }

    @Test
    fun cacheDirAndVerboseAreOptIn() {
        val plain = dumpJsonCall("dQw4w9WgXcQ", null, false)
        assertTrue(plain.options.none { it.name == "--cache-dir" || it.name == "-v" })

        val cached = dumpJsonCall("dQw4w9WgXcQ", "/data/cache/yt-dlp", true)
        assertEquals("/data/cache/yt-dlp", cached.options.single { it.name == "--cache-dir" }.value)
        assertTrue(cached.options.any { it.name == "-v" && it.value == null })
    }

    @Test
    fun callBuildersValidateTheIdFirst() {
        assertFailsWith<IllegalArgumentException> { dumpJsonCall("bad", null, false) }
        assertFailsWith<IllegalArgumentException> { downloadCall("bad;rm -rf /", "/x/%(id)s.%(ext)s", null) }
        val d = downloadCall("dQw4w9WgXcQ", "/x/dQw4w9WgXcQ.%(ext)s", null)
        assertEquals("never", d.options.single { it.name == "--fixup" }.value)
        assertEquals("/x/dQw4w9WgXcQ.%(ext)s", d.options.single { it.name == "-o" }.value)
        assertTrue(d.options.any { it.name == "--no-part" } && d.options.any { it.name == "--force-overwrites" })
    }

    @Test
    fun parseInfoSingleFormat() {
        val info = parseInfo(fixture("single-format.json"), "dQw4w9WgXcQ")
        assertEquals("https://rr1---sn-test.googlevideo.com/videoplayback?expire=1760900000&itag=140", info.url)
        assertEquals("m4a", info.ext)
        assertEquals("audio/mp4", info.mime)
        assertEquals("140", info.formatId)
        assertEquals("Mozilla/5.0 (synthetic)", info.headers["User-Agent"])
        assertEquals("Synthetic Track", info.title) // track wins over title
        assertEquals("Synthetic Artist", info.artist)
        assertEquals(212.5, info.duration)
        assertEquals(3432100L, info.filesize)
    }

    @Test
    fun parseInfoRequestedFormatsUsesFirstEntryWithUrl() {
        val info = parseInfo(fixture("requested-formats.json"), "4NRXx6U8ABQ")
        assertEquals("webm", info.ext)
        assertEquals("audio/webm", info.mime)
        assertEquals("251", info.formatId)
        assertEquals("Mozilla/5.0 (format synthetic)", info.headers["User-Agent"]) // the format's own headers
        assertEquals("Synthetic merged-format title", info.title) // no track
        assertEquals("Synthetic Channel Only", info.artist) // no artist: channel
        assertEquals(98.0, info.duration) // string duration is coerced
        assertEquals(1500000L, info.filesize)
    }

    @Test
    fun parseInfoFallsBackToTopLevelHeadersAndId() {
        val json = """{"requested_formats":[{"url":"https://h.example/v?x=1","ext":"mp3"}],"http_headers":{"User-Agent":"top"}}"""
        val info = parseInfo(json, "abcdefghijk")
        assertEquals("top", info.headers["User-Agent"])
        assertEquals("abcdefghijk", info.title)
        assertEquals("", info.artist)
        assertEquals("audio/mpeg", info.mime)
        assertNull(info.formatId)
    }

    @Test
    fun parseInfoWithoutUrlThrows() {
        val e = assertFailsWith<IllegalStateException> { parseInfo("""{"id":"x","requested_formats":[{"ext":"mp4"}]}""", "dQw4w9WgXcQ") }
        assertEquals("yt-dlp returned no direct URL", e.message)
        assertFailsWith<IllegalStateException> { parseInfo("{}", "dQw4w9WgXcQ") }
    }

    @Test
    fun mimeTable() {
        assertEquals("video/webm", mimeFor("webm", "vp9"))
        assertEquals("audio/webm", mimeFor("webm", "none"))
        assertEquals("audio/webm", mimeFor("webm", null))
        assertEquals("audio/ogg", mimeFor("opus", null))
        assertEquals("application/octet-stream", mimeFor("weird", null))
        assertEquals("application/octet-stream", mimeFor(null, null))
    }

    @Test
    fun urlExpireReadsTheQueryParameter() {
        assertEquals(1760900000L, urlExpire("https://rr1---sn-test.googlevideo.com/videoplayback?expire=1760900000&itag=140"))
        assertEquals(1760903600L, urlExpire("https://h.example/v?itag=251&expire=1760903600&mime=audio%2Fwebm"))
        assertNull(urlExpire("https://h.example/v?itag=251"))
        assertNull(urlExpire("https://h.example/v?expire=notanumber"))
        assertNull(urlExpire("not a url at all ::"))
    }

    @Test
    fun resolveOnceNeverCallsTheGatewayForAnInvalidId() {
        val gw = FakeGateway(GatewayResult(0, "{}", "", 1))
        val (record, info) = Resolver(gw).resolveOnce("../../etc/x", "default", 3, null, false)
        assertTrue(gw.calls.isEmpty())
        assertFalse(record.ok)
        assertEquals("invalid id", record.error)
        assertNull(info)
        assertEquals(3, record.index)
    }

    @Test
    fun resolveOnceMapsNonZeroExitToTheStderrTail() {
        val stderr = "ERROR: one\n\nERROR: two\nERROR: three\nERROR: four\n"
        val gw = FakeGateway(GatewayResult(1, "", stderr, 1234))
        val (record, info) = Resolver(gw).resolveOnce("dQw4w9WgXcQ", "default", 0, null, false)
        assertEquals(1, gw.calls.size)
        assertFalse(record.ok)
        assertEquals("ERROR: two | ERROR: three | ERROR: four", record.error)
        assertEquals(1234L, record.wallMs)
        assertNull(info)
    }

    @Test
    fun errorTextKeepsOnlyTheHostOfUrls() {
        val gw = FakeGateway(GatewayResult(1, "", "ERROR: unable to download https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&sig=SECRET: HTTP Error 403", 10))
        val (record, _) = Resolver(gw).resolveOnce("dQw4w9WgXcQ", "default", 0, null, false)
        val err = record.error!!
        assertTrue(err.contains("https://rr1---sn-x.googlevideo.com/..."), err)
        assertFalse(err.contains("SECRET"))
    }

    @Test
    fun resolveOnceRecordsHostAndExpireNeverTheFullUrl() {
        val gw = FakeGateway(
            GatewayResult(0, fixture("single-format.json"), "", 2750, stderrHead = "[debug] Python 3.12\n[debug] JS runtimes: quickjs-ng 0.10.1\n"),
        )
        val (record, info) = Resolver(gw).resolveOnce("dQw4w9WgXcQ", "cache", 1, "/c/yt-dlp", true)
        assertTrue(record.ok)
        assertNotNull(info)
        assertEquals("rr1---sn-test.googlevideo.com", record.urlHost)
        assertEquals(1760900000L, record.expire)
        assertEquals("m4a", record.ext)
        assertEquals("140", record.formatId)
        assertEquals("[debug] JS runtimes: quickjs-ng 0.10.1", record.jsRuntimeLine)
        assertEquals("/c/yt-dlp", gw.calls.single().options.single { it.name == "--cache-dir" }.value)
        // the serialised record is what lands in evidence files: no URL, no query string
        val json = record.toJson().toString()
        assertFalse(json.contains("videoplayback"))
        assertFalse(json.contains("itag"))
        assertTrue(json.contains("rr1---sn-test.googlevideo.com"))
    }

    @Test
    fun resolveOnceReportsUnparseableOutputAsFailure() {
        val gw = FakeGateway(GatewayResult(0, "this is not json", "", 5))
        val (record, info) = Resolver(gw).resolveOnce("dQw4w9WgXcQ", "default", 0, null, false)
        assertFalse(record.ok)
        assertNotNull(record.error)
        assertNull(info)
    }
}
