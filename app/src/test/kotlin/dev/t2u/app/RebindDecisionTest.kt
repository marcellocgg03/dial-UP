package dev.t2u.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RebindDecisionTest {
    private val networkReasons = listOf("lost", "available", "linkPropertiesChanged")
    private val ip = "192.168.50.23"

    @Test
    fun `reload-config restarts a running bridge whatever the switch and address say`() {
        for (enabled in listOf(true, false)) {
            for (wifi in listOf(null, ip)) {
                assertEquals(RebindAction.RESTART, rebindAction("config", enabled, hasBridge = true, wifiIp = wifi))
            }
        }
    }

    @Test
    fun `reload-config with no bridge starts one`() {
        assertEquals(RebindAction.START, rebindAction("config", true, hasBridge = false, wifiIp = ip))
        assertEquals(RebindAction.START, rebindAction("config", false, hasBridge = false, wifiIp = null))
    }

    @Test
    fun `with the switch off a network event is skipped`() {
        for (reason in networkReasons) {
            for (hasBridge in listOf(true, false)) {
                assertEquals(RebindAction.SKIP_DISABLED, rebindAction(reason, false, hasBridge, ip))
                assertEquals(RebindAction.SKIP_DISABLED, rebindAction(reason, false, hasBridge, null))
            }
        }
    }

    @Test
    fun `no Wi-Fi address only marks the network lost`() {
        for (reason in networkReasons) {
            assertEquals(RebindAction.MARK_LOST_ONLY, rebindAction(reason, true, hasBridge = true, wifiIp = null))
            assertEquals(RebindAction.MARK_LOST_ONLY, rebindAction(reason, true, hasBridge = false, wifiIp = null))
        }
    }

    @Test
    fun `an address with no bridge starts one`() {
        for (reason in networkReasons) {
            assertEquals(RebindAction.START, rebindAction(reason, true, hasBridge = false, wifiIp = ip))
        }
    }

    @Test
    fun `an address with a bridge moves it in place, also when the address is unchanged`() {
        for (reason in networkReasons) {
            assertEquals(RebindAction.REBIND_IN_PLACE, rebindAction(reason, true, hasBridge = true, wifiIp = ip))
        }
    }

    @Test
    fun `a retry with no bridge and an address starts one, also with the switch off`() {
        for (enabled in listOf(true, false)) {
            assertEquals(RebindAction.START, rebindAction("retry", enabled, hasBridge = false, wifiIp = ip))
        }
    }

    @Test
    fun `a retry with a bridge does nothing`() {
        for (enabled in listOf(true, false)) {
            for (wifi in listOf(null, ip)) {
                assertEquals(RebindAction.NOTHING, rebindAction("retry", enabled, hasBridge = true, wifiIp = wifi))
            }
        }
    }

    @Test
    fun `a retry with no address does nothing`() {
        for (enabled in listOf(true, false)) {
            assertEquals(RebindAction.NOTHING, rebindAction("retry", enabled, hasBridge = false, wifiIp = null))
        }
    }
}
