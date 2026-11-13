package dev.t2u.media

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-09: ffmpeg's network input is held to the direct proxy's googlevideo rule and a protocol whitelist. */
class FfmpegUrlPolicyTest {
    private fun info(url: String, headers: Map<String, String> = emptyMap()) =
        ResolvedInfo(url, "m4a", "audio/mp4", headers, "t", "a", "", "", 60.0, 0, "140")

    @Test
    fun onlyHttpsGooglevideoOnThePlainPortIsAllowed() {
        for (ok in listOf(
            "https://rr1---sn-test.googlevideo.com/videoplayback?itag=140",
            "https://googlevideo.com/x",
            "HTTPS://RR1.GoogleVideo.com:443/x",
        )) assertTrue(isGoogleVideoUrl(ok), ok)
        for (no in listOf(
            "http://rr1.googlevideo.com/x", "file:///data/data/x/files/secret", "concat:a|b", "ftp://rr1.googlevideo.com/x",
            "https://rr1.googlevideo.com:8443/x", "https://evilgooglevideo.com/x", "https://googlevideo.com.evil.example/x",
            "https://user@rr1.googlevideo.com/x", "https://192.168.1.5/x", "https://rr1.googlevideo.com@evil.example/x",
            "rtsp://rr1.googlevideo.com/x", "not a url", "",
        )) assertFalse(isGoogleVideoUrl(no), no)
    }

    @Test
    fun aRefusedUrlBuildsNoArgsAndTheMessageDoesNotCarryIt() {
        val e = assertFailsWith<IllegalArgumentException> { urlInputArgs(info("file:///etc/hosts?sig=secret"), 0) }
        assertFalse("secret" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { urlInputArgs(info("http://192.168.1.5/a.m4a"), 0) }
    }

    @Test
    fun theNetworkInputCarriesTheProtocolWhitelistBeforeTheInput() {
        val args = urlInputArgs(info("https://rr1.googlevideo.com/v"), 0)
        val at = args.indexOf("-protocol_whitelist")
        assertEquals("https,tls,tcp,crypto", args[at + 1])
        assertTrue(at < args.indexOf("-i"))
    }

    @Test
    fun theLocalFileInputHasNoWhitelistSoItStillReadsTheFile() {
        assertFalse("-protocol_whitelist" in fileInputArgs("/data/cache/a.m4a", 0))
    }

    @Test
    fun lineBreaksInHeaderNamesAndValuesAreStripped() {
        val args = urlInputArgs(info("https://rr1.googlevideo.com/v", linkedMapOf("User-Agent" to "ua\r\nX-Evil: 1", "Bad\nName" to "v")), 0)
        val headers = args[args.indexOf("-headers") + 1]
        assertEquals("User-Agent: uaX-Evil: 1\r\nBadName: v\r\n", headers)
    }
}
