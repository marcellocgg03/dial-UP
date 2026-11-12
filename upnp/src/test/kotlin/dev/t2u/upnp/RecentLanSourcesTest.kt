package dev.t2u.upnp

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentLanSourcesTest {
    private val phone = "192.168.50.239"
    private var now = 0L

    /** Accepts what the access policy would call a guest: anything but the phone's own addresses and loopback. */
    private fun sources(windowMs: Long = 180_000L, maxEntries: Int = 64) = RecentLanSources(
        accept = { it != phone && it != "127.0.0.1" && it != "0:0:0:0:0:0:0:1" },
        clockMs = { now },
        windowMs = windowMs,
        maxEntries = maxEntries,
    )

    @Test
    fun `an address of another device on the LAN is remembered and the newest one wins`() {
        val s = sources()
        s.note("192.168.50.45")
        now = 1_000
        s.note(InetAddress.getByName("192.168.50.46"))
        assertEquals("192.168.50.46", s.mostRecent())
        now = 2_000
        s.note("192.168.50.45")
        assertEquals("192.168.50.45", s.mostRecent())
        assertEquals(2, s.size())
    }

    @Test
    fun `the phone's own addresses, loopback, public, name and junk sources are never recorded`() {
        val s = sources()
        for (bad in listOf(phone, "127.0.0.1", "::1", "8.8.8.8", "100.64.0.1", "224.0.0.1", "0.0.0.0", "example.com", "", "  ", "192.168.50", "1.2.3.4.5")) {
            s.note(bad)
        }
        assertNull(s.mostRecent())
        assertEquals(0, s.size())
    }

    @Test
    fun `a bracketed, mapped or link-local IPv6 source is read as an address literal`() {
        val s = sources()
        s.note("[fe80::1]")
        assertEquals("fe80:0:0:0:0:0:0:1", s.mostRecent()?.substringBefore('%'))
        val t = sources()
        t.note("::ffff:192.168.50.45")
        assertEquals("192.168.50.45", t.mostRecent())
    }

    @Test
    fun `an entry older than the window counts for nothing`() {
        val s = sources(windowMs = 180_000L)
        s.note("192.168.50.45")
        now = 179_999
        assertEquals("192.168.50.45", s.mostRecent())
        now = 180_000
        assertNull(s.mostRecent(), "three minutes old is treated as unknown")
        assertEquals(0, s.size(), "an expired entry is dropped")
    }

    @Test
    fun `seeing an address again renews it`() {
        val s = sources()
        s.note("192.168.50.45")
        now = 170_000
        s.note("192.168.50.45")
        now = 300_000
        assertEquals("192.168.50.45", s.mostRecent())
    }

    @Test
    fun `an address that became the phone's own is no longer reported`() {
        var own = false
        val s = RecentLanSources(accept = { !own }, clockMs = { now })
        s.note("192.168.50.45")
        assertEquals("192.168.50.45", s.mostRecent())
        own = true
        assertNull(s.mostRecent(), "the policy is asked again at read time")
    }

    @Test
    fun `no more than maxEntries addresses are kept and the oldest goes first`() {
        val s = sources(maxEntries = 3)
        for (i in 1..5) {
            now += 10
            s.note("192.168.50.$i")
        }
        assertEquals(3, s.size())
        assertEquals("192.168.50.5", s.mostRecent())
        now += 10
        s.note("192.168.50.3")
        assertEquals("192.168.50.3", s.mostRecent())
        assertEquals(3, s.size())
    }
}
