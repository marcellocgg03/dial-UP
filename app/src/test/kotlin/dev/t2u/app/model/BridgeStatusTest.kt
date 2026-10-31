// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

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
}
