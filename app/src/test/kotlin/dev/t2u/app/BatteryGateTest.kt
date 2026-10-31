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
    fun `the gate is open for every reason when the app is exempt and enabled`() {
        for (reason in StartReason.entries) assertTrue(gateDecision(exempt = true, enabled = true, reason = reason), reason.name)
    }

    @Test
    fun `the gate is shut for every reason when the app is not exempt`() {
        for (reason in StartReason.entries) {
            assertFalse(gateDecision(exempt = false, enabled = true, reason = reason), reason.name)
            assertFalse(gateDecision(exempt = false, enabled = false, reason = reason), reason.name)
        }
    }

    @Test
    fun `the gate is shut for every reason when the switch is off even with the exemption`() {
        for (reason in StartReason.entries) assertFalse(gateDecision(exempt = true, enabled = false, reason = reason), reason.name)
    }

    @Test
    fun `a boot starts the bridge only when the switch is on and the exemption is held`() {
        assertTrue(bootStartDecision(enabled = true, exempt = true))
        assertFalse(bootStartDecision(enabled = false, exempt = true))
        assertFalse(bootStartDecision(enabled = true, exempt = false))
        assertFalse(bootStartDecision(enabled = false, exempt = false))
    }

    @Test
    fun `first launch without the exemption asks notifications then the exemption`() {
        assertEquals(
            listOf(LaunchStep.ASK_NOTIFICATIONS, LaunchStep.ASK_EXEMPTION),
            launchSteps(markerExists = false, sdk = 36, notificationsGranted = false, exempt = false),
        )
    }

    @Test
    fun `first launch with the exemption asks notifications then starts the service`() {
        assertEquals(
            listOf(LaunchStep.ASK_NOTIFICATIONS, LaunchStep.START_SERVICE),
            launchSteps(markerExists = false, sdk = 36, notificationsGranted = false, exempt = true),
        )
    }

    @Test
    fun `notifications are never asked twice`() {
        assertEquals(
            listOf(LaunchStep.ASK_EXEMPTION),
            launchSteps(markerExists = true, sdk = 36, notificationsGranted = false, exempt = false),
        )
        assertEquals(
            listOf(LaunchStep.START_SERVICE),
            launchSteps(markerExists = true, sdk = 36, notificationsGranted = false, exempt = true),
        )
    }

    @Test
    fun `below API 33 or with notifications granted the notification step is skipped`() {
        assertEquals(
            listOf(LaunchStep.ASK_EXEMPTION),
            launchSteps(markerExists = false, sdk = 32, notificationsGranted = false, exempt = false),
        )
        assertEquals(
            listOf(LaunchStep.ASK_EXEMPTION),
            launchSteps(markerExists = false, sdk = 36, notificationsGranted = true, exempt = false),
        )
    }

    @Test
    fun `after the exemption dialog only the exemption decides`() {
        assertEquals(LaunchStep.START_SERVICE, afterExemptionDialog(exempt = true))
        assertEquals(LaunchStep.FINISH_WITHOUT_SERVICE, afterExemptionDialog(exempt = false))
    }
}
