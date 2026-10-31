package dev.dialup.app

// GUI-10 (plan 07-09): the Wi-Fi rebind is always on, so the decision table has no switch input.

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RebindDecisionTest {
    private val networkReasons = listOf("lost", "available", "linkPropertiesChanged")
    private val ip = "192.168.50.23"

    @Test
    fun `reload-config restarts a running bridge whatever the address says`() {
        for (wifi in listOf(null, ip)) {
            assertEquals(RebindAction.RESTART, rebindAction("config", hasBridge = true, wifiIp = wifi))
        }
    }

    @Test
    fun `reload-config with no bridge starts one`() {
        assertEquals(RebindAction.START, rebindAction("config", hasBridge = false, wifiIp = ip))
        assertEquals(RebindAction.START, rebindAction("config", hasBridge = false, wifiIp = null))
    }

    @Test
    fun `no Wi-Fi address only marks the network lost`() {
        for (reason in networkReasons) {
            assertEquals(RebindAction.MARK_LOST_ONLY, rebindAction(reason, hasBridge = true, wifiIp = null))
            assertEquals(RebindAction.MARK_LOST_ONLY, rebindAction(reason, hasBridge = false, wifiIp = null))
        }
    }

    @Test
    fun `an address with no bridge starts one`() {
        for (reason in networkReasons) {
            assertEquals(RebindAction.START, rebindAction(reason, hasBridge = false, wifiIp = ip))
        }
    }

    @Test
    fun `an address with a bridge moves it in place, also when the address is unchanged`() {
        for (reason in networkReasons) {
            assertEquals(RebindAction.REBIND_IN_PLACE, rebindAction(reason, hasBridge = true, wifiIp = ip))
        }
    }

    @Test
    fun `a retry with no bridge and an address starts one`() {
        assertEquals(RebindAction.START, rebindAction("retry", hasBridge = false, wifiIp = ip))
    }

    @Test
    fun `a retry with a bridge does nothing`() {
        for (wifi in listOf(null, ip)) {
            assertEquals(RebindAction.NOTHING, rebindAction("retry", hasBridge = true, wifiIp = wifi))
        }
    }

    @Test
    fun `a retry with no address does nothing`() {
        assertEquals(RebindAction.NOTHING, rebindAction("retry", hasBridge = false, wifiIp = null))
    }

    @Test
    fun `there is no skipped-because-disabled action any more`() {
        assertEquals(
            setOf("MARK_LOST_ONLY", "REBIND_IN_PLACE", "START", "RESTART", "NOTHING"),
            RebindAction.entries.map { it.name }.toSet(),
        )
    }
}
