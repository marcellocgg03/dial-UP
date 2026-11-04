package dev.t2u.renderer

// WR-08 (plan 08-05): isOwnStreamUri matches host, port and path prefix from the start of the string, not as a substring.
// GPL-3.0-only.

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnStreamTest {
    private val hosts = listOf("192.168.50.7", "192.168.50.9")

    private fun own(uri: String) = isOwnStreamUri(uri, hosts, 8000)

    @Test
    fun ourStreamUrlsMatchWhateverTheSchemeIs() {
        assertTrue(own("http://192.168.50.7:8000/stream/ab.aac"))
        assertTrue(own("x-rincon-mp3radio://192.168.50.7:8000/stream/ab.aac"))
        assertTrue(own("192.168.50.7:8000/stream/ab.aac"))
        assertTrue(own("http://192.168.50.9:8000/stream/ab.aac"), "a second own host (the address before a move)")
    }

    @Test
    fun foreignUrlsDoNotMatch() {
        assertFalse(own("http://other.example/x?u=http://192.168.50.7:8000/stream/ab.aac"), "ours inside a query string")
        assertFalse(own("http://192.168.50.7:8001/stream/ab.aac"), "another port")
        assertFalse(own("http://192.168.50.7:80000/stream/ab.aac"), "a port that only starts with ours")
        assertFalse(own("http://1192.168.50.7:8000/stream/ab.aac"), "a host that only ends like ours")
        assertFalse(own("http://192.168.50.77:8000/stream/ab.aac"), "a host that only starts like ours")
        assertFalse(own("http://192.168.50.7:8000@evil.example/stream/x"), "a userinfo trick")
        assertFalse(own("http://192.168.50.7:8000/other/ab.aac"), "another path")
        assertFalse(own("http://192.168.50.7:8000/streams/ab.aac"), "a path that only starts with /stream")
        assertFalse(own(""))
        assertFalse(isOwnStreamUri("http://192.168.50.7:8000/stream/a", emptyList(), 8000))
    }
}
