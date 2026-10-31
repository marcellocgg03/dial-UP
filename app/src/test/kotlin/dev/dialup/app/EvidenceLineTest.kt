package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceLineTest {
    private val ts = "2026-10-19T20:00:00.000+01:00"

    @Test
    fun `control characters are escaped so a line never contains a raw CR or LF`() {
        val line = EvidenceLine.format(ts, 123L, "ssdp-rx", mapOf("raw" to "M-SEARCH * HTTP/1.1\r\nST: x\r\n"))
        assertFalse(line.contains('\r'), "raw CR in line")
        assertFalse(line.contains('\n'), "raw LF in line")
        assertTrue(line.contains("\\r\\n"), "expected escaped CRLF in: $line")
        assertTrue(
            line.startsWith("{\"ts\":\"2026-10-19T20:00:00.000+01:00\",\"mono\":123,\"kind\":\"ssdp-rx\""),
            "unexpected prefix: $line",
        )
    }

    @Test
    fun `a string longer than 2048 chars is cut with a dropped-count suffix`() {
        val line = EvidenceLine.format(ts, 1L, "http-req", mapOf("raw" to "a".repeat(2048 + 7)))
        assertTrue(line.contains("a".repeat(2048) + "…[+7]"), "missing cut suffix")
        assertFalse(line.contains("a".repeat(2049)), "string was not cut at 2048")
    }

    @Test
    fun `quotes backslashes NUL and the unicode line separators are escaped`() {
        val line = EvidenceLine.format(ts, 1L, "k", mapOf("v" to "q\"b\\n\u0000  "))
        assertTrue(line.contains("\"v\":\"q\\\"b\\\\n\\u0000\\u2028\\u2029\""), "got: $line")
    }

    @Test
    fun `scalars are literals and nested maps and lists become objects and arrays`() {
        val line = EvidenceLine.format(
            ts, 1L, "heartbeat",
            linkedMapOf(
                "b" to true,
                "i" to 5,
                "l" to 6L,
                "d" to 1.5,
                "n" to null,
                "m" to linkedMapOf("x" to 1, "y" to "z"),
                "a" to listOf(1, "two", null, listOf(3)),
            ),
        )
        assertTrue(line.contains("\"b\":true"), line)
        assertTrue(line.contains("\"i\":5"), line)
        assertTrue(line.contains("\"l\":6"), line)
        assertTrue(line.contains("\"d\":1.5"), line)
        assertTrue(line.contains("\"n\":null"), line)
        assertTrue(line.contains("\"m\":{\"x\":1,\"y\":\"z\"}"), line)
        assertTrue(line.contains("\"a\":[1,\"two\",null,[3]]"), line)
    }

    @Test
    fun `a line over 8192 bytes is replaced by a truncation marker`() {
        val many = (0 until 8).associate { "f$it" to "x".repeat(2048) }
        val line = EvidenceLine.format(ts, 9L, "ssdp-rx", many)
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= 8192, "marker line still too long")
        assertTrue(line.startsWith("{\"ts\":\"$ts\",\"mono\":9,\"kind\":\"ssdp-rx\",\"truncated\":true,\"bytes\":"), line)
        assertTrue(line.endsWith("}"))
    }

    @Test
    fun `a normal line is a single JSON object`() {
        val line = EvidenceLine.format(ts, 2L, "lock", mapOf("name" to "t2u:mcast", "isHeld" to true))
        assertEquals("{\"ts\":\"$ts\",\"mono\":2,\"kind\":\"lock\",\"name\":\"t2u:mcast\",\"isHeld\":true}", line)
    }
}
