package dev.t2u.renderer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsdpOfflineErrorTest {
    @Test
    fun aNoticeThatCannotLeaveWhileTheNetworkIsDownIsNotAnError() {
        assertTrue(isExpectedOffline("notify", networkDown = true))
    }

    @Test
    fun aNoticeFailureWhileTheNetworkIsUpIsStillReported() {
        assertFalse(isExpectedOffline("notify", networkDown = false))
    }

    @Test
    fun otherSsdpFailuresAreReportedEvenWhileTheNetworkIsDown() {
        assertFalse(isExpectedOffline("reply", networkDown = true))
        assertFalse(isExpectedOffline("receive:239.255.255.250", networkDown = true))
    }
}
