// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.dial.DialAccessMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BridgeStatusTest {
    @Test
    fun `off reads Bridge off`() {
        assertEquals("Bridge off", statusLine(BridgeStatus.OFF))
    }

    @Test
    fun `running with the phone only mode and no address`() {
        assertEquals("Running · This phone only", statusLine(BridgeStatus(true, null, DialAccessMode.PHONE)))
    }

    @Test
    fun `running with the whole network mode and no address`() {
        assertEquals("Running · Whole network", statusLine(BridgeStatus(true, null, DialAccessMode.LAN)))
    }

    @Test
    fun `running with a known Wi-Fi address names it`() {
        assertEquals(
            "Running · Wi-Fi 192.168.1.20 · This phone only",
            statusLine(BridgeStatus(true, "192.168.1.20", DialAccessMode.PHONE)),
        )
    }

    @Test
    fun `off ignores address and mode`() {
        assertEquals("Bridge off", statusLine(BridgeStatus(false, "192.168.1.20", DialAccessMode.LAN)))
    }

    private val up = BridgeStatus(true, "192.168.1.20", DialAccessMode.PHONE)
    private val down = BridgeStatus.OFF

    @Test
    fun `a switched-off bridge reads Bridge off whatever else holds`() {
        for (exempt in listOf(true, false)) for (serviceUp in listOf(true, false)) for (s in listOf(up, down)) {
            assertEquals("Bridge off", statusLine(s, enabled = false, exempt = exempt, serviceUp = serviceUp))
        }
    }

    @Test
    fun `enabled without the exemption waits for it`() {
        assertEquals("Waiting for the battery exemption", statusLine(down, enabled = true, exempt = false, serviceUp = false))
        assertEquals("Waiting for the battery exemption", statusLine(up, enabled = true, exempt = false, serviceUp = true))
    }

    @Test
    fun `enabled and exempt but no service yet reads Starting`() {
        assertEquals("Starting", statusLine(down, enabled = true, exempt = true, serviceUp = false))
    }

    @Test
    fun `the service up without a running bridge waits for Wi-Fi`() {
        assertEquals("Waiting for Wi-Fi", statusLine(down, enabled = true, exempt = true, serviceUp = true))
    }

    @Test
    fun `a running bridge keeps the running texts`() {
        assertEquals(
            "Running · Wi-Fi 192.168.1.20 · This phone only",
            statusLine(up, enabled = true, exempt = true, serviceUp = true),
        )
        assertEquals(
            "Running · Whole network",
            statusLine(BridgeStatus(true, null, DialAccessMode.LAN), enabled = true, exempt = true, serviceUp = true),
        )
    }
}
