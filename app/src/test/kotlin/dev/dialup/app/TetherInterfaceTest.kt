package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Quick 261102-jmg: the tether fallback for a phone with no Wi-Fi client address. GPL-3.0-only.
class TetherInterfaceTest {
    private fun cand(
        name: String,
        ip: String = "192.168.43.1",
        up: Boolean = true,
        multicast: Boolean = true,
        loopback: Boolean = false,
        p2p: Boolean = false,
    ) = TetherCandidate(name, ip, up, multicast, loopback, p2p)

    @Test
    fun aHotspotInterfaceIsChosen() {
        assertEquals("swlan0", selectTether(listOf(cand("swlan0")))?.name)
    }

    @Test
    fun wifiHotspotBeatsUsbTether() {
        assertEquals("ap0", selectTether(listOf(cand("rndis0", "192.168.42.129"), cand("ap0")))?.name)
    }

    @Test
    fun usbTetherIsUsedWhenThereIsNoHotspot() {
        assertEquals("rndis0", selectTether(listOf(cand("rndis0", "192.168.42.129")))?.name)
    }

    @Test
    fun mobileDataLoopbackVpnAndDownInterfacesAreSkipped() {
        val all = listOf(
            cand("rmnet_data0", "10.20.30.40"),
            cand("lo", "127.0.0.1", loopback = true),
            cand("tun0", "10.8.0.2", p2p = true),
            cand("ap0", up = false),
            cand("swlan0", multicast = false),
        )
        assertNull(selectTether(all))
    }

    @Test
    fun nothingGivesNull() {
        assertNull(selectTether(emptyList()))
    }

    @Test
    fun trackerUsesTheTetherAddressOnlyWhenWifiHasNone() {
        val tracker = WifiAddressTracker()
        assertTrue(tracker.onFallback("192.168.43.1"))
        assertEquals("192.168.43.1", tracker.currentIp)
        assertFalse(tracker.onFallback("192.168.43.1"))

        // Wi-Fi comes back: it wins, and that is a change
        assertTrue(tracker.onReport(1L, "192.168.50.41"))
        assertEquals("192.168.50.41", tracker.currentIp)
        assertFalse(tracker.onFallback("192.168.43.1"))

        // Wi-Fi lost: back to the tether address
        assertTrue(tracker.onLost(1L))
        assertEquals("192.168.43.1", tracker.currentIp)

        // hotspot switched off
        assertTrue(tracker.onFallback(null))
        assertNull(tracker.currentIp)
    }
}
