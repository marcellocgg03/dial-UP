package dev.dialup.app

import dev.t2u.media.ProcessExit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegRunnerLogTest {
    private fun exit(args: List<String>, tail: String) = ProcessExit(args, 1, 12L, tail)

    @Test
    fun `a signed url in the stderr tail keeps only scheme and host`() {
        val tail = "https://rr1---sn-x.googlevideo.com/videoplayback?sig=SECRET&lsig=SECRET2&ip=203.0.113.9: HTTP error 403 Forbidden"
        val text = FfmpegRunner.exitFields(exit(listOf("-i", "x"), tail)).toString()
        assertTrue(text.contains("rr1---sn-x.googlevideo.com"))
        assertTrue(text.contains("403"))
        assertFalse(text.contains("SECRET"))
        assertFalse(text.contains("lsig"))
        assertFalse(text.contains("203.0.113.9"))
    }

    @Test
    fun `the tail is capped at 512 characters after redaction and plain text is unchanged`() {
        val plain = "Conversion failed!"
        assertEquals(plain, FfmpegRunner.exitFields(exit(emptyList(), plain))["stderrTail"])
        val long = "x".repeat(2000) + " https://h.example.com/a?sig=SECRET"
        val tail = FfmpegRunner.exitFields(exit(emptyList(), long))["stderrTail"] as String
        assertEquals(512, tail.length)
        assertFalse(tail.contains("SECRET"))
    }

    @Test
    fun `args are still redacted`() {
        val args = listOf("-headers", "Cookie: a=b", "-i", "https://h.example.com/v?sig=SECRET", "out.mp4")
        val fields = FfmpegRunner.exitFields(exit(args, ""))
        assertEquals(listOf("-headers", "[headers]", "-i", "url:h.example.com", "out.mp4"), fields["args"])
        assertEquals(1, fields["exitCode"])
        assertEquals(12L, fields["ms"])
    }
}
