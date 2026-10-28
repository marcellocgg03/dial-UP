package dev.t2u.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryGateTest {
    @Test
    fun `a null intent is a sticky restart`() {
        assertEquals(StartReason.STICKY, startReasonOf(intentIsNull = true, action = null, reasonExtra = null))
    }

    @Test
    fun `the boot extra is a boot start`() {
        assertEquals(StartReason.BOOT, startReasonOf(intentIsNull = false, action = null, reasonExtra = "boot"))
    }

    @Test
    fun `a missing or unknown extra on a real intent is an open`() {
        assertEquals(StartReason.OPEN, startReasonOf(intentIsNull = false, action = null, reasonExtra = null))
        assertEquals(StartReason.OPEN, startReasonOf(intentIsNull = false, action = null, reasonExtra = "whatever"))
    }

    @Test
    fun `the wire names are sticky, boot and open`() {
        assertEquals("sticky", StartReason.STICKY.wire)
        assertEquals("boot", StartReason.BOOT.wire)
        assertEquals("open", StartReason.OPEN.wire)
    }

    @Test
    fun `the gate is open for every reason when the app is exempt`() {
        for (reason in StartReason.entries) assertTrue(gateDecision(exempt = true, reason = reason), reason.name)
    }

    @Test
    fun `the gate is shut for every reason when the app is not exempt`() {
        for (reason in StartReason.entries) assertFalse(gateDecision(exempt = false, reason = reason), reason.name)
    }
}
