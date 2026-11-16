// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

/** The card's overflow menu item and the title of its dialog. */
internal const val PAIR_MENU_LABEL = "Pair manually with code"

/** What the pairing dialog says about itself. */
internal const val PAIR_DESCRIPTION = "Useful if your LAN blocks YouTube/YouTube Music from discovering renderers, even if the bridge is enabled."

/** Added to the Add renderer by address prompt: adding by address does not always make the apps list the renderer. */
internal const val ADD_BY_ADDRESS_PAIR_HINT =
    "If YouTube/YouTube Music still cannot discover the renderer after adding it, use \"$PAIR_MENU_LABEL\" in its menu."

/** The label of the code of the app [app]: each code works only in its own app. */
internal fun pairingCodeLabel(app: String): String = "$app code"

/** What goes on the clipboard: the digits alone, the way the apps take them. */
internal fun pairingCodeForClipboard(code: String): String = code.filterNot { it.isWhitespace() }
