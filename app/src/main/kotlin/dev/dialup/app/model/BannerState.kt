// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

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

/**
 * The battery banner's sentence. Shown whenever the exemption is missing, also when it was revoked later in system
 * settings: the Enable switch is greyed out until Grant is used.
 */
const val BATTERY_BANNER_TEXT =
    "Unrestricted battery use is not allowed, so the bridge cannot run and Enable is greyed out. Tap Grant to allow it"

/** What the notifications banner's Grant button does. */
enum class GrantAction { REQUEST, OPEN_SETTINGS }

/**
 * After two denials Android stops showing the permission dialog and the request returns at once. A request that was made before and now has no rationale to show is that case, so Grant opens the
 * app's notification settings instead. The first ask, and an ask where the system still offers a rationale,
 * request the permission.
 */
fun notificationGrantAction(askedBefore: Boolean, rationale: Boolean): GrantAction =
    if (askedBefore && !rationale) GrantAction.OPEN_SETTINGS else GrantAction.REQUEST
