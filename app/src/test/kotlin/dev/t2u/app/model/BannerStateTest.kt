// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
