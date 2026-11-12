package dev.dialup.app

import dev.t2u.dial.DialAccessMode
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.upnp.RecentLanSources
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestNotifierTest {
    private val own = OwnDevice("samsung", "SM-S936U")

    @Test
    fun `the text names the sender's device and the speaker`() {
        assertEquals("COZYLA CD-8S544F2 started a cast on Kitchen", guestNoticeText("COZYLA CD-8S544F2", "Kitchen"))
    }

    @Test
    fun `one name keeps one id whatever its case, another name gets another, and none is the bridge notification`() {
        assertEquals(guestNoticeId("COZYLA CD-8S544F2"), guestNoticeId("cozyla cd-8s544f2"))
        assertNotEquals(guestNoticeId("COZYLA CD-8S544F2"), guestNoticeId("Pixel 7"))
        assertTrue(guestNoticeId("COZYLA CD-8S544F2") > CastNotifier.NOTIFICATION_ID)
    }

    @Test
    fun `this phone's own sender is never a guest, by exact name in any case or by containing the model`() {
        assertNull(guestNoticeFor(true, "SAMSUNG SM-S936U", "Kitchen", own, recentGuest = "192.168.86.31"))
        assertNull(guestNoticeFor(true, "samsung sm-s936u", "Kitchen", own, recentGuest = null))
        assertNull(guestNoticeFor(true, "Marcello's SM-S936U", "Kitchen", own, recentGuest = null))
        assertTrue(own.isOwnName("  SAMSUNG SM-S936U "))
        assertFalse(own.isOwnName("COZYLA CD-8S544F2"))
        assertFalse(own.isOwnName(""))
        assertFalse(OwnDevice("", "").isOwnName("COZYLA CD-8S544F2"), "an unknown model matches nothing")
    }

    @Test
    fun `a guest seen on the LAN lately is announced by its device name`() {
        val notice = guestNoticeFor(true, "COZYLA CD-8S544F2", "Lamp", own, recentGuest = "192.168.86.31")
        assertEquals(GuestNotice("COZYLA CD-8S544F2 started a cast on Lamp", seenOnLan = true), notice)
    }

    @Test
    fun `a sender that is not this phone is announced by name even when no address was seen`() {
        val notice = guestNoticeFor(true, "COZYLA CD-8S544F2", "Lamp", own, recentGuest = null)
        assertEquals(GuestNotice("COZYLA CD-8S544F2 started a cast on Lamp", seenOnLan = false), notice)
    }

    @Test
    fun `the switch decides whether there is a notice at all`() {
        assertNull(guestNoticeFor(false, "COZYLA CD-8S544F2", "Lamp", own, recentGuest = "192.168.86.31"))
        assertNull(guestNoticeFor(true, "  ", "Lamp", own, recentGuest = "192.168.86.31"), "a nameless sender has nothing to announce")
    }

    @Test
    fun `the same device name is announced once per thirty seconds and another name is independent`() {
        var now = 0L
        val decider = GuestNoticeDecider(own, { true }, GuestNoticeGate({ now }, 30_000L))
        assertEquals("COZYLA CD-8S544F2 started a cast on Lamp", decider.decide("Lamp", "COZYLA CD-8S544F2", null)?.text)
        now = 11_000L
        assertNull(decider.decide("Lamp", "COZYLA CD-8S544F2", "192.168.86.31"), "a reconnect 11 s later is not announced again")
        assertNull(decider.decide("Kitchen", "cozyla cd-8s544f2", null), "same device, same window, even on another speaker")
        assertEquals("Pixel 7 started a cast on Lamp", decider.decide("Lamp", "Pixel 7", null)?.text)
        now = 30_000L
        assertEquals("COZYLA CD-8S544F2 started a cast on Lamp", decider.decide("Lamp", "COZYLA CD-8S544F2", null)?.text)
    }

    @Test
    fun `a switch that is off or the phone's own sender does not use up the window`() {
        var now = 0L
        var on = false
        val decider = GuestNoticeDecider(own, { on }, GuestNoticeGate({ now }, 30_000L))
        assertNull(decider.decide("Lamp", "COZYLA CD-8S544F2", null))
        assertNull(decider.decide("Lamp", "SAMSUNG SM-S936U", null))
        on = true
        now = 1_000L
        assertEquals("COZYLA CD-8S544F2 started a cast on Lamp", decider.decide("Lamp", "COZYLA CD-8S544F2", null)?.text)
        assertNull(decider.decide("Lamp", "SAMSUNG SM-S936U", null))
    }

    @Test
    fun `the gate forgets old names when it is full`() {
        var now = 0L
        val gate = GuestNoticeGate({ now }, 30_000L, maxEntries = 3)
        for (i in 1..3) {
            now += 1
            assertTrue(gate.due("d$i"))
        }
        now = 40_000L
        assertTrue(gate.due("d4"), "a full gate drops expired names to make room")
        assertFalse(gate.due("d4"))
    }

    /** The pieces as the bridge wires them: the access policy decides who may be remembered, the tracker feeds the decider. */
    private class Wiring {
        var now = 0L
        val policy = DialAccessPolicy({ DialAccessMode.LAN }, { setOf("192.168.86.20", "127.0.0.1", "::1") })
        val sources = RecentLanSources(accept = policy::isGuest, clockMs = { now })
        val decider = GuestNoticeDecider(OwnDevice("samsung", "SM-S936U"), { true }, GuestNoticeGate({ now }))

        fun connect(senderName: String) = decider.decide("Lamp", senderName, sources.mostRecent())
    }

    @Test
    fun `a guest that searched a moment before it connected is recognised by its address`() {
        val w = Wiring()
        w.sources.note("192.168.86.31")
        w.now = 5_000L
        assertEquals(GuestNotice("COZYLA CD-8S544F2 started a cast on Lamp", seenOnLan = true), w.connect("COZYLA CD-8S544F2"))
    }

    @Test
    fun `the phone's own sender is not announced although its own app was seen calling the receiver`() {
        val w = Wiring()
        w.sources.note("192.168.86.20")
        w.sources.note("127.0.0.1")
        assertNull(w.sources.mostRecent(), "the phone's own addresses are not remembered")
        assertNull(w.connect("SAMSUNG SM-S936U"))
    }

    @Test
    fun `an address older than three minutes is unknown and the name alone announces the guest`() {
        val w = Wiring()
        w.sources.note("192.168.86.31")
        w.now = 180_000L
        assertEquals(GuestNotice("COZYLA CD-8S544F2 started a cast on Lamp", seenOnLan = false), w.connect("COZYLA CD-8S544F2"))
    }

    @Test
    fun `a guest with no address seen at all is announced by name`() {
        val w = Wiring()
        assertEquals(GuestNotice("COZYLA CD-8S544F2 started a cast on Lamp", seenOnLan = false), w.connect("COZYLA CD-8S544F2"))
    }
}
