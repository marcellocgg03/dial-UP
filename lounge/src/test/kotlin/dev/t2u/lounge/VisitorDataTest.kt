package dev.t2u.lounge

import java.net.URLDecoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VisitorDataTest {
    @Test
    fun encodeHasTheProtobufLayoutAndIsUrlEncoded() {
        val seconds = 1_761_000_000L
        val encoded = VisitorData.encode("abcdefghijk", seconds)
        assertTrue(encoded.endsWith("%3D") || !encoded.contains('='), "padding is URL-encoded")
        assertTrue('=' !in encoded)
        val bytes = Base64.getUrlDecoder().decode(URLDecoder.decode(encoded, "UTF-8"))
        assertEquals(0x0a, bytes[0].toInt())
        assertEquals(11, bytes[1].toInt())
        assertEquals("abcdefghijk", String(bytes, 2, 11, Charsets.UTF_8))
        assertEquals(0x28, bytes[13].toInt())
        var value = 0L
        var shift = 0
        var i = 14
        while (true) {
            val b = bytes[i++].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        assertEquals(seconds, value)
        assertEquals(bytes.size, i)
    }

    @Test
    fun generateGivesDifferentValuesWithAnElevenCharacterId() {
        val a = VisitorData.generate()
        val b = VisitorData.generate()
        assertNotEquals(a, b)
        val bytes = Base64.getUrlDecoder().decode(URLDecoder.decode(a, "UTF-8"))
        assertEquals(11, bytes[1].toInt())
        assertTrue(String(bytes, 2, 11, Charsets.UTF_8).all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
