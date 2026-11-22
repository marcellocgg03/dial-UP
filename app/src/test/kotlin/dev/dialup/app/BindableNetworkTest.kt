package dev.dialup.app

import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Phase 12 (APP WR-05): a Wi-Fi network without an IPv4 address must not hide a later network with one, or the hotspot. GPL-3.0-only.
class BindableNetworkTest {
    private fun v4(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address

    @Test
    fun aWifiNetworkWithoutIpv4DoesNotHideALaterOneWithAnAddress() {
        var tetherCalls = 0
        val links = sequenceOf(WifiLink(null, "wlan1"), WifiLink(v4("10.0.0.20"), "wlan0"))
        val got = pickBindable(links) { tetherCalls++; v4("192.168.43.1") to "swlan0" }
        assertEquals(v4("10.0.0.20") to "wlan0", got)
        assertEquals(0, tetherCalls)
    }

    @Test
    fun whenNoWifiNetworkHasAnAddressTheHotspotIsChosen() {
        var tetherCalls = 0
        val got = pickBindable(sequenceOf(WifiLink(null, "wlan0"))) { tetherCalls++; v4("192.168.43.1") to "swlan0" }
        assertEquals(v4("192.168.43.1") to "swlan0", got)
        assertEquals(1, tetherCalls)
    }

    @Test
    fun noNetworkAndNoHotspotGivesNullToNull() {
        val got = pickBindable(emptySequence()) { null }
        assertNull(got.first)
        assertNull(got.second)
    }

    @Test
    fun theSequenceIsNotEvaluatedPastTheFirstLinkWithAnAddress() {
        var produced = 0
        var tetherCalls = 0
        val links = generateSequence(0) { it + 1 }.map {
            produced++
            if (it == 0) WifiLink(v4("10.0.0.20"), "wlan0") else WifiLink(v4("10.0.1.20"), "wlan1")
        }
        val got = pickBindable(links) { tetherCalls++; null }
        assertEquals(v4("10.0.0.20") to "wlan0", got)
        assertEquals(1, produced)
        assertEquals(0, tetherCalls)
    }

    @Test
    fun twoLinksWithAddressesGiveTheFirstInAllNetworksOrder() {
        val links = sequenceOf(WifiLink(v4("10.0.0.20"), "wlan0"), WifiLink(v4("172.16.0.5"), "wlan1"))
        assertEquals(v4("10.0.0.20") to "wlan0", pickBindable(links) { null })
    }
}
