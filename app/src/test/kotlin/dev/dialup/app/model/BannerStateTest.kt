// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BannerStateTest {
    @Test
    fun `a missing exemption alone shows the battery banner`() {
        assertEquals(listOf(Banner.BATTERY), bannerState(sdk = 36, notificationsGranted = true, exempt = false))
    }

    @Test
    fun `nothing is missing means no banner`() {
        assertEquals(emptyList(), bannerState(sdk = 36, notificationsGranted = true, exempt = true))
    }

    @Test
    fun `both missing lists notifications first`() {
        assertEquals(
            listOf(Banner.NOTIFICATIONS, Banner.BATTERY),
            bannerState(sdk = 36, notificationsGranted = false, exempt = false),
        )
    }

    @Test
    fun `below API 33 notifications are never listed`() {
        assertEquals(listOf(Banner.BATTERY), bannerState(sdk = 32, notificationsGranted = false, exempt = false))
        assertEquals(emptyList(), bannerState(sdk = 32, notificationsGranted = false, exempt = true))
    }

    @Test
    fun `the first ask and an ask with a rationale request the permission`() {
        assertEquals(GrantAction.REQUEST, notificationGrantAction(askedBefore = false, rationale = false))
        assertEquals(GrantAction.REQUEST, notificationGrantAction(askedBefore = true, rationale = true))
    }

    @Test
    fun `asked before without a rationale means denied for good so the settings page opens`() {
        assertEquals(GrantAction.OPEN_SETTINGS, notificationGrantAction(askedBefore = true, rationale = false))
    }

    @Test
    fun `the battery banner text names Enable and Grant and says the bridge cannot run`() {
        assertTrue("Enable" in BATTERY_BANNER_TEXT, BATTERY_BANNER_TEXT)
        assertTrue("Grant" in BATTERY_BANNER_TEXT, BATTERY_BANNER_TEXT)
        assertTrue("cannot run" in BATTERY_BANNER_TEXT, BATTERY_BANNER_TEXT)
    }
}
