// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

/** A permission the bridge needs and the screen explains inline (D-01). */
enum class Banner { NOTIFICATIONS, BATTERY }

/** What the system currently grants; re-read from the platform every time the screen resumes (T-07-44). */
data class PermissionState(val notificationsGranted: Boolean, val exempt: Boolean)

/**
 * The banners to show, in display order. Notifications are a runtime permission only from API 33, so below
 * that they are never listed; the battery exemption is listed whenever it is missing, because the bridge does
 * not start without it (D-12).
 */
fun bannerState(sdk: Int, notificationsGranted: Boolean, exempt: Boolean): List<Banner> = buildList {
    if (sdk >= 33 && !notificationsGranted) add(Banner.NOTIFICATIONS)
    if (!exempt) add(Banner.BATTERY)
}
