package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Plan 05-16 (review WR-04): the per-network address tracker behind NetworkMonitor. GPL-3.0-only.
class WifiAddressTrackerTest {
    private val ip41 = "192.168.50.41"
    private val ip47 = "192.168.50.47"

    @Test
    fun aLostNetworkThatIsNotTheCurrentOneChangesNothing() {
        val tracker = WifiAddressTracker()
        assertTrue(tracker.onReport(1L, ip41))
        assertTrue(tracker.onReport(2L, ip47))
        assertEquals(ip47, tracker.currentIp)

        assertFalse(tracker.onLost(1L))
        assertEquals(ip47, tracker.currentIp)
    }

    @Test
    fun aSingleNetworkKeepsTheOldBehaviour() {
        val tracker = WifiAddressTracker(ip41)
        assertFalse(tracker.onReport(1L, ip41))
        assertTrue(tracker.onLost(1L))
        assertNull(tracker.currentIp)
        assertTrue(tracker.onReport(1L, ip41))
        assertFalse(tracker.onReport(1L, ip41))
    }

    @Test
    fun aNewAddressOnTheSameNetworkIsAChange() {
        val tracker = WifiAddressTracker()
        assertTrue(tracker.onReport(1L, ip41))
        assertTrue(tracker.onReport(1L, ip47))
        assertEquals(ip47, tracker.currentIp)
    }

    @Test
    fun losingTheCurrentNetworkFallsBackToTheOtherOne() {
        val tracker = WifiAddressTracker()
        tracker.onReport(1L, ip41)
        tracker.onReport(2L, ip47)

        assertTrue(tracker.onLost(2L))
        assertEquals(ip41, tracker.currentIp)
        assertTrue(tracker.onLost(1L))
        assertNull(tracker.currentIp)
    }

    @Test
    fun aLostCallbackForAnUnseenNetworkIsIgnored() {
        val tracker = WifiAddressTracker(ip41)
        assertFalse(tracker.onLost(99L))
        assertEquals(ip41, tracker.currentIp)
    }

    @Test
    fun aNetworkWithoutAnIpv4DoesNotHideTheOtherOne() {
        val tracker = WifiAddressTracker()
        tracker.onReport(1L, ip41)
        assertFalse(tracker.onReport(2L, null))
        assertEquals(ip41, tracker.currentIp)
        assertTrue(tracker.onLost(1L))
        assertNull(tracker.currentIp)
    }

    @Test
    fun trackedCountsTheKnownNetworks() {
        val tracker = WifiAddressTracker()
        assertEquals(0, tracker.tracked)
        tracker.onReport(1L, ip41)
        tracker.onReport(2L, ip47)
        assertEquals(2, tracker.tracked)
        tracker.onLost(1L)
        assertEquals(1, tracker.tracked)
        tracker.onLost(99L)
        assertEquals(1, tracker.tracked)
    }

    @Test
    fun theSameAddressOnTwoNetworksIsOneAddress() {
        val tracker = WifiAddressTracker()
        tracker.onReport(1L, ip41)
        assertFalse(tracker.onReport(2L, ip41))
        assertFalse(tracker.onLost(2L))
        assertEquals(ip41, tracker.currentIp)
    }
}
